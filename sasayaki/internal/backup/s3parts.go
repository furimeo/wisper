package backup

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/xml"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strconv"
)

// The three calls a multipart upload is made of, and the one that throws it away.
//
// Separate from the sequence in s3multipart.go on purpose: that file is about which parts to
// send and in what order, and this one is about what each request looks like on the wire. The
// two change for different reasons - a resume rule is a decision, an ETag header is a protocol
// detail - and reading either one should not mean scrolling past the other.

func (s *s3Destination) createUpload(ctx context.Context, key string) (string, error) {
	var uploadID string
	err := s.retry.do(ctx, "start the multipart upload of "+key, func(ctx context.Context) error {
		response, err := s.send(ctx, s3Request{
			Operation: "start the multipart upload of " + key,
			Method:    http.MethodPost,
			Key:       key,
			Query:     url.Values{"uploads": {""}},
			Header:    s.encryptionHeader(),
		})
		if err != nil {
			return err
		}
		defer response.Body.Close()

		body, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
		if err != nil {
			return transient(fmt.Errorf("backup: read the answer that starts the upload of %s: %w", key, err))
		}
		var parsed initiateMultipartUpload
		if err := xml.Unmarshal(body, &parsed); err != nil {
			return fmt.Errorf("backup: the answer that starts the upload of %s is not one: %w", key, err)
		}
		if parsed.UploadID == "" {
			return fmt.Errorf("backup: the object store started an upload of %s without giving it an id", key)
		}
		uploadID = parsed.UploadID
		return nil
	})
	return uploadID, err
}

// uploadPart sends one part and returns the ETag the store gave it, which the complete call
// has to repeat back.
func (s *s3Destination) uploadPart(ctx context.Context, key, uploadID string, number int,
	part *io.SectionReader, length int64) (string, error) {

	digest, err := digestOfSection(part, 0, length)
	if err != nil {
		return "", err
	}
	operation := fmt.Sprintf("upload part %d of %s", number, key)

	var etag string
	err = s.retry.do(ctx, operation, func(ctx context.Context) error {
		if _, err := part.Seek(0, io.SeekStart); err != nil {
			return fmt.Errorf("backup: rewind part %d of %s: %w", number, key, err)
		}
		response, err := s.send(ctx, s3Request{
			Operation: operation,
			Method:    http.MethodPut,
			Key:       key,
			Query: url.Values{
				"partNumber": {strconv.Itoa(number)},
				"uploadId":   {uploadID},
			},
			Body:   part,
			Length: length,
			Digest: digest,
		})
		if err != nil {
			return err
		}
		defer response.Body.Close()
		io.Copy(io.Discard, io.LimitReader(response.Body, 4<<10))

		etag = unquoteETag(response.Header.Get("ETag"))
		if etag == "" {
			// Without it the complete call cannot name the part, and a store that accepted a
			// part and did not say so has not really accepted it.
			return transient(fmt.Errorf("backup: the object store accepted part %d of %s without "+
				"returning an ETag", number, key))
		}
		return nil
	})
	return etag, err
}

// completeUpload assembles the object.
//
// The 200 is not the answer. S3 begins streaming the response while it is still stitching the
// parts together and reports a failure inside the body, so the body is parsed and a Code in it
// is a failure however encouraging the status line was.
func (s *s3Destination) completeUpload(ctx context.Context, key, uploadID string, parts []journalPart) error {
	if len(parts) == 0 {
		return fmt.Errorf("backup: cannot complete the upload of %s with no parts", key)
	}

	document := completeMultipartUpload{Parts: make([]completePartXML, 0, len(parts))}
	for _, part := range parts {
		document.Parts = append(document.Parts, completePartXML{
			PartNumber: part.Number,
			ETag:       `"` + part.ETag + `"`,
		})
	}
	payload, err := xml.Marshal(document)
	if err != nil {
		return fmt.Errorf("backup: encode the completion of %s: %w", key, err)
	}
	sum := sha256.Sum256(payload)
	digest := hex.EncodeToString(sum[:])
	operation := "complete the upload of " + key

	return s.retry.do(ctx, operation, func(ctx context.Context) error {
		response, err := s.send(ctx, s3Request{
			Operation: operation,
			Method:    http.MethodPost,
			Key:       key,
			Query:     url.Values{"uploadId": {uploadID}},
			Header:    http.Header{"Content-Type": {"application/xml"}},
			Body:      bytes.NewReader(payload),
			Length:    int64(len(payload)),
			Digest:    digest,
		})
		if err != nil {
			return err
		}
		defer response.Body.Close()

		body, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
		if err != nil {
			return transient(fmt.Errorf("backup: read the answer completing %s: %w", key, err))
		}
		var parsed completeMultipartUploadResult
		if err := xml.Unmarshal(body, &parsed); err != nil {
			return fmt.Errorf("backup: the answer completing %s is not one: %w", key, err)
		}
		if parsed.Code != "" {
			return &responseError{
				Operation: operation,
				Status:    response.StatusCode,
				Code:      parsed.Code,
				Message:   parsed.Message,
				text: fmt.Sprintf("backup: %s: the object store answered 200 and then failed with "+
					"%s: %s", operation, parsed.Code, parsed.Message),
			}
		}
		return nil
	})
}

// Abandon throws away the parts of an upload that will not be finished.
//
// Failure to abort is logged and not propagated: the backup has already failed, and reporting
// the cleanup instead of the cause would hide the cause.
func (s *s3Destination) Abandon(ctx context.Context, key string, journal *uploadJournal) {
	uploadID := journal.uploadID()
	if uploadID == "" {
		return
	}
	defer journal.discard()

	operation := "abandon the upload of " + key
	err := s.retry.do(ctx, operation, func(ctx context.Context) error {
		response, err := s.send(ctx, s3Request{
			Operation: operation,
			Method:    http.MethodDelete,
			Key:       key,
			Query:     url.Values{"uploadId": {uploadID}},
		})
		if err != nil {
			return err
		}
		io.Copy(io.Discard, io.LimitReader(response.Body, 4<<10))
		return response.Body.Close()
	})
	if err != nil {
		s.log.Warn("could not abandon an unfinished multipart upload; it will be billed until a "+
			"lifecycle rule removes it",
			slog.String("key", key), slog.String("error", err.Error()))
	}
}
