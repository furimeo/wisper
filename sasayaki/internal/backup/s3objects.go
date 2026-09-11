package backup

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/xml"
	"fmt"
	"io"
	"net/http"
	"net/url"
)

// The four verbs a backup asks of an object store, on top of the signed request in s3store.go.
//
// Each is wrapped in the retry policy rather than retried by its caller, because the decision
// about what is worth trying again belongs next to the status code that says so - and because
// a caller that had to remember to wrap would eventually not.

func (s *s3Destination) Put(ctx context.Context, key string, body []byte) error {
	if err := checkKey(key); err != nil {
		return err
	}
	sum := sha256.Sum256(body)
	digest := hex.EncodeToString(sum[:])

	return s.retry.do(ctx, "put "+key, func(ctx context.Context) error {
		response, err := s.send(ctx, s3Request{
			Operation: "put " + key,
			Method:    http.MethodPut,
			Key:       key,
			Header:    s.encryptionHeader(),
			Body:      bytes.NewReader(body),
			Length:    int64(len(body)),
			Digest:    digest,
		})
		if err != nil {
			return err
		}
		io.Copy(io.Discard, io.LimitReader(response.Body, 4<<10))
		return response.Body.Close()
	})
}

func (s *s3Destination) Get(ctx context.Context, key string) (io.ReadCloser, error) {
	if err := checkKey(key); err != nil {
		return nil, err
	}
	var opened io.ReadCloser
	err := s.retry.do(ctx, "get "+key, func(ctx context.Context) error {
		response, err := s.send(ctx, s3Request{
			Operation: "get " + key,
			Method:    http.MethodGet,
			Key:       key,
		})
		if err != nil {
			return err
		}
		opened = response.Body
		return nil
	})
	if err != nil {
		return nil, err
	}
	return opened, nil
}

func (s *s3Destination) Delete(ctx context.Context, key string) error {
	if err := checkKey(key); err != nil {
		return err
	}
	return s.retry.do(ctx, "delete "+key, func(ctx context.Context) error {
		response, err := s.send(ctx, s3Request{
			Operation: "delete " + key,
			Method:    http.MethodDelete,
			Key:       key,
		})
		if err != nil {
			return err
		}
		io.Copy(io.Discard, io.LimitReader(response.Body, 4<<10))
		return response.Body.Close()
	})
}

// List follows continuation tokens to the end.
//
// Not optional. Retention deletes what a listing does not contain, so a listing that stopped
// at the first thousand objects would decide that generations it never saw are not there and
// keep the newest thousand of a subject that has two thousand - which is the right answer by
// accident and the wrong one the moment a prefix holds more than one subject.
func (s *s3Destination) List(ctx context.Context, prefix string) ([]storedObject, error) {
	var (
		found []storedObject
		token string
	)
	for page := 0; ; page++ {
		query := url.Values{"list-type": {"2"}, "max-keys": {"1000"}}
		if prefix != "" {
			query.Set("prefix", prefix)
		}
		if token != "" {
			query.Set("continuation-token", token)
		}

		var parsed listBucketResult
		err := s.retry.do(ctx, "list "+prefix, func(ctx context.Context) error {
			response, err := s.send(ctx, s3Request{
				Operation: "list " + prefix,
				Method:    http.MethodGet,
				Query:     query,
			})
			if err != nil {
				return err
			}
			defer response.Body.Close()

			body, err := io.ReadAll(io.LimitReader(response.Body, 8<<20))
			if err != nil {
				return transient(fmt.Errorf("backup: list %s: read the answer: %w", prefix, err))
			}
			parsed = listBucketResult{}
			if err := xml.Unmarshal(body, &parsed); err != nil {
				return fmt.Errorf("backup: list %s: the answer is not a listing: %w", prefix, err)
			}
			return nil
		})
		if err != nil {
			return nil, err
		}

		for _, entry := range parsed.Contents {
			found = append(found, storedObject{Key: entry.Key, Size: entry.Size})
		}
		if !parsed.IsTruncated || parsed.NextContinuationToken == "" {
			return found, nil
		}
		token = parsed.NextContinuationToken

		if page > 1000 {
			return nil, fmt.Errorf("backup: list %s: the store kept asking for another page after "+
				"a million objects, which is a loop rather than a listing", prefix)
		}
	}
}
