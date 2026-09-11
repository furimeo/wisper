package backup

import (
	"bytes"
	"encoding/xml"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// An object store, in one file.
//
// Enough of the S3 REST API for the six requests this package makes, plus the two things a
// real store has that a map does not: it can fail a part and it can paginate a listing. Those
// are exactly the behaviours the code under test exists to survive, so faking the store rather
// than the destination interface is the only way to test them.
//
// Past three hundred lines and deliberately not split. It is one thing - a server - and the
// half of it that is protocol plumbing is only readable next to the half that is state; a
// handler in one file and the map it writes to in another would be two files nobody can check
// against each other.
//
// It also checks the signature on every request, by re-deriving it from what actually arrived
// and comparing. That does not prove the SigV4 implementation matches Amazon's - only a real
// store proves that - but it does catch the failure that actually happens: a header added
// after signing, a query parameter left out of the canonical request, a path escaped one way
// on the wire and another in the signature.

type fakeS3 struct {
	t      *testing.T
	server *httptest.Server
	creds  credentials
	bucket string

	mu sync.Mutex
	// objects is what has been stored, keyed by object key.
	objects map[string][]byte
	// uploads is the multipart uploads in flight, keyed by upload id.
	uploads map[string]map[int][]byte
	// partsSeen counts how many times each part number was accepted, which is what a resume
	// test asserts on.
	partsSeen map[int]int
	// failPart makes one part number fail with a 500 the given number of times.
	failPart map[int]int
	// onPart is called after each accepted part, so a test can interrupt an upload part way.
	onPart func(number int)
	// pageSize forces a listing to be truncated, so the continuation loop is exercised.
	pageSize int
	// nextUpload numbers the upload ids.
	nextUpload int
}

func newFakeS3(t *testing.T) *fakeS3 {
	t.Helper()
	store := &fakeS3{
		t:         t,
		creds:     credentials{AccessKeyID: "AKIAEXAMPLE", SecretAccessKey: "secret", Region: "us-east-1"},
		bucket:    "backups",
		objects:   make(map[string][]byte),
		uploads:   make(map[string]map[int][]byte),
		partsSeen: make(map[int]int),
		failPart:  make(map[int]int),
		pageSize:  1000,
	}
	store.server = httptest.NewServer(http.HandlerFunc(store.serve))
	t.Cleanup(store.server.Close)
	return store
}

// destination is the message the panel would send for this store.
func (f *fakeS3) destination(prefix string) *wisperpb.BackupDestination {
	return &wisperpb.BackupDestination{
		Kind: wisperpb.DestinationKind_DESTINATION_KIND_S3,
		S3: &wisperpb.S3Destination{
			Endpoint:        f.server.URL,
			Region:          f.creds.Region,
			Bucket:          f.bucket,
			Prefix:          prefix,
			AccessKeyId:     f.creds.AccessKeyID,
			SecretAccessKey: f.creds.SecretAccessKey,
			// httptest serves on 127.0.0.1, and bucket.127.0.0.1 is not a hostname.
			PathStyle: true,
		},
	}
}

func (f *fakeS3) serve(writer http.ResponseWriter, request *http.Request) {
	if err := f.checkSignature(request); err != nil {
		f.t.Errorf("a request arrived that does not match its own signature: %v", err)
		refuse(writer, http.StatusForbidden, "SignatureDoesNotMatch", err.Error())
		return
	}

	prefix := "/" + f.bucket
	if !strings.HasPrefix(request.URL.Path, prefix) {
		refuse(writer, http.StatusNotFound, "NoSuchBucket", request.URL.Path)
		return
	}
	key := strings.TrimPrefix(strings.TrimPrefix(request.URL.Path, prefix), "/")
	query := request.URL.Query()

	switch {
	case request.Method == http.MethodGet && query.Get("list-type") == "2":
		f.list(writer, query)
	case request.Method == http.MethodPost && query.Has("uploads"):
		f.startUpload(writer, key)
	case request.Method == http.MethodPut && query.Get("uploadId") != "":
		f.acceptPart(writer, request, query)
	case request.Method == http.MethodPost && query.Get("uploadId") != "":
		f.completeUpload(writer, request, key, query.Get("uploadId"))
	case request.Method == http.MethodDelete && query.Get("uploadId") != "":
		f.abortUpload(writer, query.Get("uploadId"))
	case request.Method == http.MethodPut:
		f.put(writer, request, key)
	case request.Method == http.MethodGet:
		f.get(writer, key)
	case request.Method == http.MethodDelete:
		f.delete(writer, key)
	default:
		refuse(writer, http.StatusMethodNotAllowed, "MethodNotAllowed", request.Method)
	}
}

// checkSignature re-signs what arrived and compares it with what the client claimed.
func (f *fakeS3) checkSignature(request *http.Request) error {
	claimed := request.Header.Get("Authorization")
	if claimed == "" {
		return fmt.Errorf("no Authorization header")
	}
	stamp, err := time.Parse("20060102T150405Z", request.Header.Get("X-Amz-Date"))
	if err != nil {
		return fmt.Errorf("X-Amz-Date %q: %w", request.Header.Get("X-Amz-Date"), err)
	}

	replica, err := http.NewRequest(request.Method, "http://"+request.Host+request.URL.RequestURI(), nil)
	if err != nil {
		return err
	}
	replica.Host = request.Host
	for name, values := range request.Header {
		lowered := strings.ToLower(name)
		if lowered == "content-type" || strings.HasPrefix(lowered, "x-amz-") {
			replica.Header[name] = values
		}
	}
	sign(replica, request.Header.Get("X-Amz-Content-Sha256"), f.creds, stamp)

	if replica.Header.Get("Authorization") != claimed {
		return fmt.Errorf("signed %q, sent %q", replica.Header.Get("Authorization"), claimed)
	}
	return nil
}

func (f *fakeS3) put(writer http.ResponseWriter, request *http.Request, key string) {
	body, err := io.ReadAll(request.Body)
	if err != nil {
		refuse(writer, http.StatusInternalServerError, "InternalError", err.Error())
		return
	}
	f.mu.Lock()
	f.objects[key] = body
	f.mu.Unlock()
	writer.WriteHeader(http.StatusOK)
}

func (f *fakeS3) get(writer http.ResponseWriter, key string) {
	f.mu.Lock()
	body, found := f.objects[key]
	f.mu.Unlock()
	if !found {
		refuse(writer, http.StatusNotFound, "NoSuchKey", key)
		return
	}
	writer.Header().Set("Content-Length", strconv.Itoa(len(body)))
	writer.WriteHeader(http.StatusOK)
	writer.Write(body)
}

func (f *fakeS3) delete(writer http.ResponseWriter, key string) {
	f.mu.Lock()
	delete(f.objects, key)
	f.mu.Unlock()
	writer.WriteHeader(http.StatusNoContent)
}

func (f *fakeS3) list(writer http.ResponseWriter, query url.Values) {
	f.mu.Lock()
	keys := make([]string, 0, len(f.objects))
	for key := range f.objects {
		if strings.HasPrefix(key, query.Get("prefix")) {
			keys = append(keys, key)
		}
	}
	sizes := make(map[string]int, len(keys))
	for _, key := range keys {
		sizes[key] = len(f.objects[key])
	}
	pageSize := f.pageSize
	f.mu.Unlock()
	sort.Strings(keys)

	from := 0
	if token := query.Get("continuation-token"); token != "" {
		from = sort.SearchStrings(keys, token)
	}
	to := from + pageSize
	truncated := to < len(keys)
	if !truncated {
		to = len(keys)
	}

	answer := listBucketResult{IsTruncated: truncated}
	for _, key := range keys[from:to] {
		answer.Contents = append(answer.Contents, listEntryXML{Key: key, Size: int64(sizes[key])})
	}
	if truncated {
		answer.NextContinuationToken = keys[to]
	}
	writeXML(writer, answer)
}

func (f *fakeS3) startUpload(writer http.ResponseWriter, key string) {
	f.mu.Lock()
	f.nextUpload++
	id := fmt.Sprintf("upload-%d", f.nextUpload)
	f.uploads[id] = make(map[int][]byte)
	f.mu.Unlock()

	writeXML(writer, initiateMultipartUpload{Bucket: f.bucket, Key: key, UploadID: id})
}

func (f *fakeS3) acceptPart(writer http.ResponseWriter, request *http.Request, query url.Values) {
	number, err := strconv.Atoi(query.Get("partNumber"))
	if err != nil {
		refuse(writer, http.StatusBadRequest, "InvalidArgument", "partNumber")
		return
	}
	body, err := io.ReadAll(request.Body)
	if err != nil {
		refuse(writer, http.StatusInternalServerError, "InternalError", err.Error())
		return
	}

	f.mu.Lock()
	if remaining := f.failPart[number]; remaining > 0 {
		f.failPart[number] = remaining - 1
		f.mu.Unlock()
		refuse(writer, http.StatusInternalServerError, "InternalError", "this part is having a bad day")
		return
	}
	upload, found := f.uploads[query.Get("uploadId")]
	if !found {
		f.mu.Unlock()
		refuse(writer, http.StatusNotFound, "NoSuchUpload", query.Get("uploadId"))
		return
	}
	upload[number] = body
	f.partsSeen[number]++
	onPart := f.onPart
	f.mu.Unlock()

	writer.Header().Set("ETag", fmt.Sprintf("%q", fmt.Sprintf("etag-%d", number)))
	writer.WriteHeader(http.StatusOK)

	if onPart != nil {
		onPart(number)
	}
}

func (f *fakeS3) completeUpload(writer http.ResponseWriter, request *http.Request, key, uploadID string) {
	var document completeMultipartUpload
	body, _ := io.ReadAll(request.Body)
	if err := xml.Unmarshal(body, &document); err != nil {
		refuse(writer, http.StatusBadRequest, "MalformedXML", err.Error())
		return
	}

	f.mu.Lock()
	defer f.mu.Unlock()
	upload, found := f.uploads[uploadID]
	if !found {
		refuse(writer, http.StatusNotFound, "NoSuchUpload", uploadID)
		return
	}

	var assembled bytes.Buffer
	for index, part := range document.Parts {
		if part.PartNumber != index+1 {
			refuse(writer, http.StatusBadRequest, "InvalidPartOrder", strconv.Itoa(part.PartNumber))
			return
		}
		contents, held := upload[part.PartNumber]
		if !held {
			refuse(writer, http.StatusBadRequest, "InvalidPart", strconv.Itoa(part.PartNumber))
			return
		}
		if want := fmt.Sprintf(`"etag-%d"`, part.PartNumber); part.ETag != want {
			refuse(writer, http.StatusBadRequest, "InvalidPart", part.ETag)
			return
		}
		assembled.Write(contents)
	}

	f.objects[key] = assembled.Bytes()
	delete(f.uploads, uploadID)
	writeXML(writer, completeMultipartUploadResult{Bucket: f.bucket, Key: key, ETag: `"assembled"`})
}

func (f *fakeS3) abortUpload(writer http.ResponseWriter, uploadID string) {
	f.mu.Lock()
	delete(f.uploads, uploadID)
	f.mu.Unlock()
	writer.WriteHeader(http.StatusNoContent)
}

// counts is how many times each part number was accepted.
func (f *fakeS3) counts() map[int]int {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := make(map[int]int, len(f.partsSeen))
	for number, seen := range f.partsSeen {
		out[number] = seen
	}
	return out
}

func (f *fakeS3) object(key string) ([]byte, bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	body, found := f.objects[key]
	return body, found
}

func (f *fakeS3) unfinishedUploads() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.uploads)
}

func refuse(writer http.ResponseWriter, status int, code, message string) {
	writer.Header().Set("Content-Type", "application/xml")
	writer.WriteHeader(status)
	xml.NewEncoder(writer).Encode(s3Error{Code: code, Message: message})
}

func writeXML(writer http.ResponseWriter, document any) {
	writer.Header().Set("Content-Type", "application/xml")
	writer.WriteHeader(http.StatusOK)
	xml.NewEncoder(writer).Encode(document)
}
