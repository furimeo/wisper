package backup

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"log/slog"
	"net/http"
)

// Uploading an archive, in one request or in many.
//
// The threshold is the part size: anything that fits in one part is a single PUT, because
// three round trips to store four megabytes is three times the latency for no benefit.
// Anything larger goes in parts, and parts are the only reason a backup over a domestic
// uplink ever finishes - a forty-minute PUT that fails at minute thirty-nine has to start
// again, while a part that fails is sixteen megabytes retried.
//
// Every part is hashed and signed individually, so a proxy that mangles one produces a
// signature failure at that part rather than an object that is silently wrong. Every part
// that the store accepts is written to the journal before the next one starts, which is what
// makes a killed daemon resume rather than restart.

const (
	// defaultPartSize is what the panel gets when it says nothing. Sixteen mebibytes is over
	// S3's five-mebibyte floor with room to spare, keeps a ten-gigabyte archive well inside
	// the ten-thousand-part ceiling, and is small enough that losing one to a reset costs
	// seconds.
	defaultPartSize int64 = 16 << 20

	// maxParts is the limit every S3 implementation shares. An archive large enough to need
	// more parts than this is refused with the arithmetic rather than failing on part 10001,
	// which is a failure four hours into an upload.
	maxParts = 10000
)

// Upload writes the archive at key.
func (s *s3Destination) Upload(ctx context.Context, key string, body io.ReaderAt, size int64, journal *uploadJournal) error {
	if err := checkKey(key); err != nil {
		return err
	}
	if size < 0 {
		return fmt.Errorf("backup: cannot upload %s with a length of %d", key, size)
	}

	part := s.partSize
	if part <= 0 {
		part = defaultPartSize
	}
	if size <= part {
		return s.putWhole(ctx, key, body, size)
	}
	if parts := (size + part - 1) / part; parts > maxParts {
		return fmt.Errorf("backup: %s is %d bytes, which needs %d parts of %d and the protocol "+
			"allows %d; the node's part size is too small for an archive this large",
			key, size, parts, part, maxParts)
	}

	if err := journal.begin(key, size); err != nil {
		return err
	}
	err := s.uploadInParts(ctx, key, body, size, part, journal)
	if err != nil && uploadGone(err) {
		// The store threw the parts away - a lifecycle rule for incomplete uploads, or an
		// operator with a broom. Not a failure to report: the archive is still on local disk,
		// so the honest response is to start the upload again from nothing.
		s.log.Warn("the object store no longer has the parts of an interrupted upload; starting again",
			slog.String("key", key), slog.String("error", err.Error()))
		if resetErr := journal.reset(); resetErr != nil {
			return resetErr
		}
		err = s.uploadInParts(ctx, key, body, size, part, journal)
	}
	if err != nil {
		return err
	}
	return journal.discard()
}

// putWhole is the single-request path.
func (s *s3Destination) putWhole(ctx context.Context, key string, body io.ReaderAt, size int64) error {
	digest, err := digestOfSection(body, 0, size)
	if err != nil {
		return err
	}
	return s.retry.do(ctx, "put "+key, func(ctx context.Context) error {
		response, err := s.send(ctx, s3Request{
			Operation: "put " + key,
			Method:    http.MethodPut,
			Key:       key,
			Header:    s.encryptionHeader(),
			Body:      io.NewSectionReader(body, 0, size),
			Length:    size,
			Digest:    digest,
		})
		if err != nil {
			return err
		}
		io.Copy(io.Discard, io.LimitReader(response.Body, 4<<10))
		return response.Body.Close()
	})
}

// uploadInParts creates the upload if the journal has none, sends the parts that are missing,
// and completes.
func (s *s3Destination) uploadInParts(ctx context.Context, key string, body io.ReaderAt,
	size, partSize int64, journal *uploadJournal) error {

	uploadID := journal.uploadID()
	if uploadID == "" {
		created, err := s.createUpload(ctx, key)
		if err != nil {
			return err
		}
		if err := journal.setUploadID(created); err != nil {
			return err
		}
		uploadID = created
	} else {
		s.log.Info("resuming an interrupted upload",
			slog.String("key", key),
			slog.Int("parts_already_sent", len(journal.parts())))
	}

	for offset, number := int64(0), 1; offset < size; offset, number = offset+partSize, number+1 {
		length := partSize
		if remaining := size - offset; remaining < length {
			length = remaining
		}
		if _, already := journal.done(number, length); already {
			continue
		}

		etag, err := s.uploadPart(ctx, key, uploadID, number, io.NewSectionReader(body, offset, length), length)
		if err != nil {
			return err
		}
		if err := journal.add(journalPart{Number: number, ETag: etag, Size: length}); err != nil {
			return err
		}
	}

	return s.completeUpload(ctx, key, uploadID, journal.parts())
}

// digestOfSection hashes a range of the archive without loading it into memory.
func digestOfSection(body io.ReaderAt, offset, length int64) (string, error) {
	digest := sha256.New()
	if _, err := io.Copy(digest, io.NewSectionReader(body, offset, length)); err != nil {
		return "", fmt.Errorf("backup: hash %d bytes of the archive at offset %d: %w", length, offset, err)
	}
	return hex.EncodeToString(digest.Sum(nil)), nil
}
