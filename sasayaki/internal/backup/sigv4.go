package backup

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/http"
	"sort"
	"strings"
	"time"
)

// AWS Signature Version 4, which is what every S3-compatible store speaks.
//
// Ninety lines of HMAC rather than a dependency. The algorithm has not changed since 2012 and
// is fully specified; the SDK that implements it also brings a credential-provider chain that
// would try the EC2 instance metadata endpoint - the exact address the node's own egress
// rules block - and a retry layer that would sit underneath this package's own. What is here
// is the whole of what six requests need.
//
// The one subtlety worth stating: the payload digest is always the real one. Every request
// this package signs has its body either in memory or on disk, so there is no reason to reach
// for UNSIGNED-PAYLOAD, and signing the content means a proxy that alters a part in flight
// produces a signature failure rather than a corrupt object.

const (
	signingAlgorithm = "AWS4-HMAC-SHA256"
	signingService   = "s3"
	// emptyPayload is the SHA-256 of no bytes, which every GET, DELETE and list request uses.
	emptyPayload = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
)

// credentials is what the panel sent with the command. Held for the length of one backup and
// never written to the node's state.
type credentials struct {
	AccessKeyID     string
	SecretAccessKey string
	Region          string
}

func (c credentials) validate() error {
	missing := make([]string, 0, 3)
	if c.AccessKeyID == "" {
		missing = append(missing, "access_key_id")
	}
	if c.SecretAccessKey == "" {
		missing = append(missing, "secret_access_key")
	}
	if c.Region == "" {
		missing = append(missing, "region")
	}
	if len(missing) > 0 {
		return fmt.Errorf("backup: the S3 destination is missing %v, and an unsigned request to "+
			"an object store is a 403 that looks like a network fault", missing)
	}
	return nil
}

// sign fills in the Authorization header for one request.
//
// payloadDigest is the hex SHA-256 of the body. The caller has it already: an upload hashes
// its part on the way past, and a request with no body uses emptyPayload.
func sign(request *http.Request, payloadDigest string, creds credentials, at time.Time) {
	stamp := at.UTC()
	amzDate := stamp.Format("20060102T150405Z")
	dateOnly := stamp.Format("20060102")

	request.Header.Set("X-Amz-Date", amzDate)
	request.Header.Set("X-Amz-Content-Sha256", payloadDigest)
	if request.Host == "" {
		request.Host = request.URL.Host
	}

	signed, canonicalHeaders := headersToSign(request)
	canonicalRequest := strings.Join([]string{
		request.Method,
		escapePath(request.URL.Path),
		canonicalQuery(request),
		canonicalHeaders,
		strings.Join(signed, ";"),
		payloadDigest,
	}, "\n")

	scope := dateOnly + "/" + creds.Region + "/" + signingService + "/aws4_request"
	stringToSign := strings.Join([]string{
		signingAlgorithm,
		amzDate,
		scope,
		hexDigest(canonicalRequest),
	}, "\n")

	key := signingKey(creds.SecretAccessKey, dateOnly, creds.Region)
	signature := hex.EncodeToString(hmacSHA256(key, stringToSign))

	request.Header.Set("Authorization", fmt.Sprintf("%s Credential=%s/%s, SignedHeaders=%s, Signature=%s",
		signingAlgorithm, creds.AccessKeyID, scope, strings.Join(signed, ";"), signature))
}

// headersToSign is host plus every x-amz-* header plus content-type, lowercased, sorted and
// with runs of whitespace in the values collapsed, as the specification requires.
func headersToSign(request *http.Request) ([]string, string) {
	values := map[string]string{"host": request.Host}
	for name, header := range request.Header {
		lowered := strings.ToLower(name)
		if lowered == "content-type" || lowered == "content-md5" || strings.HasPrefix(lowered, "x-amz-") {
			values[lowered] = strings.Join(header, ",")
		}
	}

	names := make([]string, 0, len(values))
	for name := range values {
		names = append(names, name)
	}
	sort.Strings(names)

	var canonical strings.Builder
	for _, name := range names {
		canonical.WriteString(name)
		canonical.WriteByte(':')
		canonical.WriteString(collapseSpaces(values[name]))
		canonical.WriteByte('\n')
	}
	return names, canonical.String()
}

// canonicalQuery is the query string sorted by key and encoded the way the signature expects,
// which is not quite the way net/url encodes it: a space is %20 rather than +.
func canonicalQuery(request *http.Request) string {
	query := request.URL.Query()
	keys := make([]string, 0, len(query))
	for key := range query {
		keys = append(keys, key)
	}
	sort.Strings(keys)

	parts := make([]string, 0, len(keys))
	for _, key := range keys {
		values := append([]string(nil), query[key]...)
		sort.Strings(values)
		for _, value := range values {
			parts = append(parts, escapeComponent(key)+"="+escapeComponent(value))
		}
	}
	return strings.Join(parts, "&")
}

// escapePath encodes a path, leaving the separators alone.
func escapePath(path string) string {
	if path == "" {
		return "/"
	}
	segments := strings.Split(path, "/")
	for i, segment := range segments {
		segments[i] = escapeComponent(segment)
	}
	return strings.Join(segments, "/")
}

// escapeComponent percent-encodes everything outside the unreserved set.
//
// net/url's own escaping is close but not identical - it leaves some sub-delimiters alone in
// paths and turns a space into a plus in queries - and "close" produces a signature mismatch
// with a message that says nothing about which character caused it.
func escapeComponent(value string) string {
	var out strings.Builder
	for i := 0; i < len(value); i++ {
		character := value[i]
		switch {
		case character >= 'A' && character <= 'Z',
			character >= 'a' && character <= 'z',
			character >= '0' && character <= '9',
			character == '-', character == '_', character == '.', character == '~':
			out.WriteByte(character)
		default:
			fmt.Fprintf(&out, "%%%02X", character)
		}
	}
	return out.String()
}

// collapseSpaces trims a header value and reduces internal runs of spaces to one.
func collapseSpaces(value string) string {
	return strings.Join(strings.Fields(value), " ")
}

func signingKey(secret, dateOnly, region string) []byte {
	date := hmacSHA256([]byte("AWS4"+secret), dateOnly)
	regional := hmacSHA256(date, region)
	service := hmacSHA256(regional, signingService)
	return hmacSHA256(service, "aws4_request")
}

func hmacSHA256(key []byte, data string) []byte {
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(data))
	return mac.Sum(nil)
}

func hexDigest(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])
}
