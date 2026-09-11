package backup

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// DESTINATION_KIND_S3: any store that speaks the S3 REST API.
//
// The endpoint is a full URL rather than a region, which is what backup.proto specifies and
// what makes MinIO, Backblaze, Wasabi and AWS itself all work with no special case each. The
// region is still needed - it is part of the signature's scope, not part of the address - and
// stores that do not have regions accept "us-east-1", which is what a panel offering a
// destination form should default to.
//
// Addressing is a field because it cannot be guessed. Self-hosted stores are reached as
// endpoint/bucket/key and most hosted ones as bucket.endpoint/key, and getting it wrong
// produces either a 404 for a bucket that exists or a DNS failure for a hostname nobody
// registered.

type s3Destination struct {
	endpoint  *url.URL
	bucket    string
	creds     credentials
	pathStyle bool
	// encryption is the algorithm to ask for, "AES256" or empty. Sent on the requests that
	// create an object, which for a multipart upload is the create call rather than the parts.
	encryption string

	http     *http.Client
	retry    retryPolicy
	partSize int64
	log      *slog.Logger
	now      func() time.Time
}

func newS3Destination(settings *wisperpb.S3Destination, client *http.Client, retry retryPolicy,
	partSize int64, log *slog.Logger) (*s3Destination, error) {

	if settings == nil {
		return nil, fmt.Errorf("backup: the destination says S3 but carries no S3 settings")
	}
	endpoint, err := url.Parse(strings.TrimSpace(settings.GetEndpoint()))
	if err != nil {
		return nil, fmt.Errorf("backup: the S3 endpoint %q is not a URL: %w", settings.GetEndpoint(), err)
	}
	if endpoint.Scheme != "http" && endpoint.Scheme != "https" {
		return nil, fmt.Errorf("backup: the S3 endpoint %q needs an http or https scheme",
			settings.GetEndpoint())
	}
	if endpoint.Host == "" {
		return nil, fmt.Errorf("backup: the S3 endpoint %q names no host", settings.GetEndpoint())
	}
	bucket := strings.Trim(strings.TrimSpace(settings.GetBucket()), "/")
	if err := checkIdentifier("bucket", bucket); err != nil {
		return nil, err
	}
	creds := credentials{
		AccessKeyID:     settings.GetAccessKeyId(),
		SecretAccessKey: settings.GetSecretAccessKey(),
		Region:          settings.GetRegion(),
	}
	if err := creds.validate(); err != nil {
		return nil, err
	}

	return &s3Destination{
		endpoint:   endpoint,
		bucket:     bucket,
		creds:      creds,
		pathStyle:  settings.GetPathStyle(),
		encryption: strings.TrimSpace(settings.GetServerSideEncryption()),
		http:       client,
		retry:      retry,
		partSize:   partSize,
		log:        log,
		now:        time.Now,
	}, nil
}

// address builds the URL of one key, honouring path-style addressing.
func (s *s3Destination) address(key string, query url.Values) *url.URL {
	target := *s.endpoint
	base := strings.TrimSuffix(target.Path, "/")

	if s.pathStyle {
		target.Path = base + "/" + s.bucket
	} else {
		target.Host = s.bucket + "." + target.Host
		target.Path = base
	}
	if key != "" {
		target.Path += "/" + key
	}
	if target.Path == "" {
		target.Path = "/"
	}
	target.RawQuery = ""
	if len(query) > 0 {
		target.RawQuery = query.Encode()
	}
	return &target
}

// s3Request is one signed call. Body is rebuilt by the caller on every retry, because a
// reader that has already been consumed cannot be sent twice and a retry that silently posted
// nothing would produce an empty object.
type s3Request struct {
	Operation string
	Method    string
	Key       string
	Query     url.Values
	Header    http.Header
	Body      io.Reader
	Length    int64
	Digest    string
}

// send signs and performs one request, and turns a non-2xx answer into an error that says
// whether trying again could help.
//
// On success the response is returned with its body still open; the caller closes it.
func (s *s3Destination) send(ctx context.Context, request s3Request) (*http.Response, error) {
	target := s.address(request.Key, request.Query)

	body := request.Body
	if body == nil {
		body = http.NoBody
	}
	call, err := http.NewRequestWithContext(ctx, request.Method, target.String(), body)
	if err != nil {
		return nil, fmt.Errorf("backup: %s: build the request: %w", request.Operation, err)
	}
	call.ContentLength = request.Length
	for name, values := range request.Header {
		for _, value := range values {
			call.Header.Add(name, value)
		}
	}
	digest := request.Digest
	if digest == "" {
		digest = emptyPayload
	}
	sign(call, digest, s.creds, s.now())

	response, err := s.http.Do(call)
	if err != nil {
		// A connection that would not open, was reset, or timed out. Always worth another
		// attempt: this is the failure an upload over a domestic uplink has all the time.
		return nil, transient(fmt.Errorf("backup: %s: %w", request.Operation, err))
	}
	if response.StatusCode >= 200 && response.StatusCode < 300 {
		return response, nil
	}

	failure, _ := io.ReadAll(io.LimitReader(response.Body, 8<<10))
	response.Body.Close()
	described := newResponseError(request.Operation, response.StatusCode, failure)
	if retryableStatus(response.StatusCode) {
		return nil, transient(described)
	}
	return nil, described
}

// retryableStatus is the short list of answers that mean "later".
//
// 500 and 503 are the store having a moment, 429 is it asking for less, 408 is it giving up
// on a slow request. Everything else - 400, 403, 404, 409 - is a fact about the request that
// a second identical request will not change.
func retryableStatus(status int) bool {
	switch status {
	case http.StatusRequestTimeout, http.StatusTooManyRequests:
		return true
	}
	return status >= 500 && status != http.StatusNotImplemented
}

func (s *s3Destination) encryptionHeader() http.Header {
	if s.encryption == "" {
		return nil
	}
	return http.Header{"X-Amz-Server-Side-Encryption": {s.encryption}}
}
