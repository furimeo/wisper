package backup

import (
	"encoding/xml"
	"errors"
	"fmt"
	"strings"
)

// The handful of XML shapes S3 answers with.
//
// Five of them, because this package makes six kinds of request. They are written out rather
// than generated because the alternative is a code generator and a schema for four hundred
// operations, of which these are the ones a backup uses - and because a struct with the two
// fields that are read is a better statement of the coupling than one with ninety that are
// not.
//
// Nothing here is namespace-qualified. Every store answers with the same
// http://s3.amazonaws.com/doc/2006-03-01/ namespace, and encoding/xml matches on the local
// name when the struct does not name one, which is what makes the same code work against
// MinIO and Backblaze without a special case.

// initiateMultipartUpload is the answer to POST ?uploads.
type initiateMultipartUpload struct {
	XMLName  xml.Name `xml:"InitiateMultipartUploadResult"`
	Bucket   string   `xml:"Bucket"`
	Key      string   `xml:"Key"`
	UploadID string   `xml:"UploadId"`
}

// completeMultipartUpload is the request body of POST ?uploadId=, listing the parts in order.
// A store that receives them out of order rejects the whole upload, so the order is the
// caller's responsibility and multipart.go sorts before it sends.
type completeMultipartUpload struct {
	XMLName xml.Name          `xml:"CompleteMultipartUpload"`
	Parts   []completePartXML `xml:"Part"`
}

type completePartXML struct {
	PartNumber int    `xml:"PartNumber"`
	ETag       string `xml:"ETag"`
}

// completeMultipartUploadResult is the answer to it.
//
// Worth parsing for one reason that catches people out: S3 answers 200 and then reports a
// failure inside the body, because the response starts streaming while the store is still
// assembling the object. A caller that only looked at the status code would record a backup
// that is not there.
type completeMultipartUploadResult struct {
	XMLName xml.Name `xml:"CompleteMultipartUploadResult"`
	Bucket  string   `xml:"Bucket"`
	Key     string   `xml:"Key"`
	ETag    string   `xml:"ETag"`
	Code    string   `xml:"Code"`
	Message string   `xml:"Message"`
}

// listBucketResult is the answer to GET ?list-type=2.
type listBucketResult struct {
	XMLName               xml.Name       `xml:"ListBucketResult"`
	IsTruncated           bool           `xml:"IsTruncated"`
	NextContinuationToken string         `xml:"NextContinuationToken"`
	Contents              []listEntryXML `xml:"Contents"`
}

type listEntryXML struct {
	Key  string `xml:"Key"`
	Size int64  `xml:"Size"`
	ETag string `xml:"ETag"`
}

// s3Error is the body of any failed request.
type s3Error struct {
	XMLName   xml.Name `xml:"Error"`
	Code      string   `xml:"Code"`
	Message   string   `xml:"Message"`
	Resource  string   `xml:"Resource"`
	RequestID string   `xml:"RequestId"`
}

// responseError is a request the store refused, with the code it refused it by.
//
// The code is kept as a field rather than folded into the message because one caller branches
// on it: a multipart upload resumed from a journal has to tell "the upload id you remembered
// has been garbage-collected" apart from every other failure, since the first is answered by
// starting again and the rest are not.
type responseError struct {
	Operation string
	Status    int
	Code      string
	Message   string
	text      string
}

func (e *responseError) Error() string { return e.text }

// newResponseError turns a status code and a body into one line worth putting in front of a
// customer.
//
// The body is parsed when it is XML and quoted when it is not: half the S3-compatible stores
// answer a misconfigured endpoint with an HTML error page from a reverse proxy, and "502 Bad
// Gateway" with the first line of that page is far more use than a bare status.
func newResponseError(operation string, status int, body []byte) *responseError {
	failure := &responseError{Operation: operation, Status: status}

	var parsed s3Error
	if err := xml.Unmarshal(body, &parsed); err == nil && parsed.Code != "" {
		failure.Code = parsed.Code
		failure.Message = parsed.Message
		failure.text = fmt.Sprintf("%s: the object store answered %d %s: %s",
			operation, status, parsed.Code, parsed.Message)
		return failure
	}

	excerpt := strings.TrimSpace(string(body))
	if len(excerpt) > 200 {
		excerpt = excerpt[:200] + "..."
	}
	if excerpt == "" {
		failure.text = fmt.Sprintf("%s: the object store answered %d with no explanation", operation, status)
		return failure
	}
	failure.Message = excerpt
	failure.text = fmt.Sprintf("%s: the object store answered %d: %s", operation, status, excerpt)
	return failure
}

// uploadGone reports whether a failure means the multipart upload a journal remembers no
// longer exists at the store - aborted by a lifecycle rule, or by an operator.
func uploadGone(err error) bool {
	var failure *responseError
	if !errors.As(err, &failure) {
		return false
	}
	return failure.Code == "NoSuchUpload" || failure.Code == "InvalidPart"
}

// unquoteETag strips the quotes an ETag arrives wrapped in. They are part of the header's
// syntax and not part of the value, and a CompleteMultipartUpload body that repeats them
// verbatim from a listing is one some stores reject.
func unquoteETag(value string) string {
	return strings.Trim(strings.TrimSpace(value), `"`)
}
