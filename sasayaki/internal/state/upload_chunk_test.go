package state

import (
	"context"
	"errors"
	"path/filepath"
	"reflect"
	"testing"
	"time"
)

const megabyte = 1 << 20

func TestRecordChunkCoalescesAsChunksArrive(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()
	if _, err := store.BeginUpload(ctx, sampleSession("up-a")); err != nil {
		t.Fatalf("open the session: %v", err)
	}

	// Out of order, with a hole, which is what parallel chunks on a flaky connection look
	// like.
	for _, run := range []ByteRange{{2 * megabyte, 3 * megabyte}, {0, megabyte}, {megabyte, 2 * megabyte}} {
		if _, err := store.RecordChunk(ctx, "up-a", run, noon); err != nil {
			t.Fatalf("record %v: %v", run, err)
		}
	}

	progress, err := store.UploadProgress(ctx, "up-a")
	if err != nil {
		t.Fatalf("read the progress: %v", err)
	}
	want := []ByteRange{{0, 3 * megabyte}}
	if !reflect.DeepEqual(progress.Received, want) {
		t.Fatalf("received = %v, want one coalesced run %v", progress.Received, want)
	}
	if progress.ReceivedBytes != 3*megabyte {
		t.Fatalf("received %d bytes, want %d", progress.ReceivedBytes, 3*megabyte)
	}
	if progress.NextOffset != 3*megabyte {
		t.Fatalf("next offset = %d, want %d", progress.NextOffset, 3*megabyte)
	}
	if progress.Covered {
		t.Fatal("three of ten megabytes reported as covered")
	}
}

func TestRecordChunkIsIdempotent(t *testing.T) {
	// An acknowledgement that was lost makes the browser send the chunk again. On a
	// connection that drops often enough for retries to be routine, this has to be free.
	store := openStore(t)
	ctx := context.Background()
	if _, err := store.BeginUpload(ctx, sampleSession("up-b")); err != nil {
		t.Fatalf("open the session: %v", err)
	}

	for i := 0; i < 3; i++ {
		progress, err := store.RecordChunk(ctx, "up-b", ByteRange{0, megabyte}, noon)
		if err != nil {
			t.Fatalf("record the chunk on attempt %d: %v", i+1, err)
		}
		if progress.ReceivedBytes != megabyte {
			t.Fatalf("attempt %d counted %d bytes, want %d", i+1, progress.ReceivedBytes, megabyte)
		}
	}

	var rows int
	if err := store.db.QueryRowContext(ctx,
		`SELECT count(*) FROM upload_range WHERE session_id = ?`, "up-b").Scan(&rows); err != nil {
		t.Fatalf("count the ranges: %v", err)
	}
	if rows != 1 {
		t.Fatalf("three retries of one chunk left %d rows", rows)
	}
}

func TestRecordChunkRefusesBytesOutsideTheDeclaredFile(t *testing.T) {
	// Trusting a client-supplied offset is how a resumed upload gets talked into writing
	// outside the file it declared.
	store := openStore(t)
	ctx := context.Background()
	session := sampleSession("up-c")
	if _, err := store.BeginUpload(ctx, session); err != nil {
		t.Fatalf("open the session: %v", err)
	}

	if _, err := store.RecordChunk(ctx, "up-c", ByteRange{session.TotalBytes - 1, session.TotalBytes + 1}, noon); err == nil {
		t.Fatal("a range past the end of the declared file was recorded")
	}
	if _, err := store.RecordChunk(ctx, "up-c", ByteRange{4, 4}, noon); err == nil {
		t.Fatal("an empty range was recorded, which would let an upload complete with a hole")
	}
	if _, err := store.RecordChunk(ctx, "up-c", ByteRange{-1, 8}, noon); err == nil {
		t.Fatal("a negative offset was recorded")
	}

	progress, err := store.UploadProgress(ctx, "up-c")
	if err != nil {
		t.Fatalf("read the progress: %v", err)
	}
	if progress.ReceivedBytes != 0 {
		t.Fatalf("the refused chunks left %d bytes recorded", progress.ReceivedBytes)
	}
}

func TestRecordChunkRefusesACompletedSession(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()
	session := sampleSession("up-d")
	if _, err := store.BeginUpload(ctx, session); err != nil {
		t.Fatalf("open the session: %v", err)
	}
	if _, err := store.RecordChunk(ctx, "up-d", ByteRange{0, session.TotalBytes}, noon); err != nil {
		t.Fatalf("record the whole file: %v", err)
	}
	if err := store.CompleteUpload(ctx, "up-d", noon); err != nil {
		t.Fatalf("complete the upload: %v", err)
	}

	if _, err := store.RecordChunk(ctx, "up-d", ByteRange{0, megabyte}, noon.Add(time.Minute)); err == nil {
		t.Fatal("a completed session took another chunk, which would rewrite a file already in place")
	}
}

func TestChunksForAnUnknownSessionAreNotFound(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	if _, err := store.RecordChunk(ctx, "up-ghost", ByteRange{0, 8}, noon); !errors.Is(err, ErrNotFound) {
		t.Fatalf("recording against an unknown session returned %v, want ErrNotFound", err)
	}
	if _, err := store.UploadProgress(ctx, "up-ghost"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("progress of an unknown session returned %v, want ErrNotFound", err)
	}
}

func TestAnUploadResumesAcrossADaemonRestart(t *testing.T) {
	// The case this whole package exists for, in miniature: a phone on mobile data has sent
	// half a file, the daemon is restarted underneath it, and the customer must not be told
	// to start again. Section 13.4 names this test explicitly.
	path := filepath.Join(t.TempDir(), FileName)
	ctx := context.Background()
	session := sampleSession("up-resume")

	before := openStoreAt(t, path)
	if _, err := before.BeginUpload(ctx, session); err != nil {
		t.Fatalf("open the session: %v", err)
	}
	// Four megabytes arrive, then the fifth is lost and the sixth gets through: a hole, not a
	// clean prefix.
	for _, run := range []ByteRange{
		{0, megabyte}, {megabyte, 2 * megabyte}, {2 * megabyte, 3 * megabyte}, {3 * megabyte, 4 * megabyte},
		{5 * megabyte, 6 * megabyte},
	} {
		if _, err := before.RecordChunk(ctx, "up-resume", run, noon); err != nil {
			t.Fatalf("record %v: %v", run, err)
		}
	}
	if err := before.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}

	after := openStoreAt(t, path)
	progress, err := after.UploadProgress(ctx, "up-resume")
	if err != nil {
		t.Fatalf("read the progress after the restart: %v", err)
	}
	if progress.ReceivedBytes != 5*megabyte {
		t.Fatalf("the restart lost progress: %d bytes, want %d", progress.ReceivedBytes, 5*megabyte)
	}
	if progress.NextOffset != 4*megabyte {
		t.Fatalf("the client was told to resume at %d, want the first hole at %d", progress.NextOffset, 4*megabyte)
	}
	if progress.Session.StagingPath != session.StagingPath {
		t.Fatalf("the restarted daemon lost the staging path: %q", progress.Session.StagingPath)
	}

	// The browser reopens the session with the same description and finishes the file.
	resumed := sampleSession("up-resume")
	resumed.UpdatedAt = noon.Add(time.Hour)
	resumed.ExpiresAt = noon.Add(25 * time.Hour)
	if _, err := after.BeginUpload(ctx, resumed); err != nil {
		t.Fatalf("reopen the session after the restart: %v", err)
	}
	for _, run := range []ByteRange{
		{4 * megabyte, 5 * megabyte}, {6 * megabyte, 8 * megabyte}, {8 * megabyte, 10 * megabyte},
	} {
		if _, err := after.RecordChunk(ctx, "up-resume", run, noon.Add(time.Hour)); err != nil {
			t.Fatalf("record %v after the restart: %v", run, err)
		}
	}

	final, err := after.UploadProgress(ctx, "up-resume")
	if err != nil {
		t.Fatalf("read the final progress: %v", err)
	}
	if !final.Covered {
		t.Fatalf("the resumed upload is not covered: %v", final.Received)
	}
	if err := after.CompleteUpload(ctx, "up-resume", noon.Add(2*time.Hour)); err != nil {
		t.Fatalf("complete the resumed upload: %v", err)
	}
}
