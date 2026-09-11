package state

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestBeginUploadOpensASession(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()
	wanted := sampleSession("up-1")

	opened, err := store.BeginUpload(ctx, wanted)
	if err != nil {
		t.Fatalf("begin the upload: %v", err)
	}
	if opened != wanted {
		t.Fatalf("the session that came back is not the one that went in:\n got %+v\nwant %+v", opened, wanted)
	}

	read, err := store.Upload(ctx, "up-1")
	if err != nil {
		t.Fatalf("read the session: %v", err)
	}
	if read != wanted {
		t.Fatalf("the stored session differs:\n got %+v\nwant %+v", read, wanted)
	}
	if read.Complete() {
		t.Fatal("a session with no chunks reported itself complete")
	}
}

func TestBeginUploadIsIdempotentAndKeepsTheRanges(t *testing.T) {
	// Every chunk repeats the session description, because the node may have restarted
	// between two of them. The second call is the normal case, not a mistake, and discarding
	// the ranges would restart a phone's upload from zero on a dropped connection.
	store := openStore(t)
	ctx := context.Background()
	session := sampleSession("up-2")

	if _, err := store.BeginUpload(ctx, session); err != nil {
		t.Fatalf("open the session: %v", err)
	}
	if _, err := store.RecordChunk(ctx, "up-2", ByteRange{0, 1 << 20}, noon); err != nil {
		t.Fatalf("record the first chunk: %v", err)
	}

	session.UpdatedAt = noon.Add(time.Hour)
	session.ExpiresAt = noon.Add(25 * time.Hour)
	reopened, err := store.BeginUpload(ctx, session)
	if err != nil {
		t.Fatalf("reopen the session: %v", err)
	}
	// Reopening extends the life of the session: a customer who is still uploading is not an
	// orphan, however long the file is taking on a slow connection.
	if !reopened.ExpiresAt.Equal(noon.Add(25 * time.Hour)) {
		t.Fatalf("reopening did not extend the expiry: %s", reopened.ExpiresAt)
	}
	if !reopened.CreatedAt.Equal(noon) {
		t.Fatalf("reopening rewrote created_at to %s", reopened.CreatedAt)
	}

	progress, err := store.UploadProgress(ctx, "up-2")
	if err != nil {
		t.Fatalf("read the progress: %v", err)
	}
	if progress.ReceivedBytes != 1<<20 {
		t.Fatalf("reopening lost %d bytes of progress", (1<<20)-progress.ReceivedBytes)
	}
}

func TestBeginUploadRefusesADifferentFileUnderTheSameID(t *testing.T) {
	// Either the browser reused an id or two uploads collided. Continuing would interleave
	// two files' chunks into one staging file.
	store := openStore(t)
	ctx := context.Background()

	if _, err := store.BeginUpload(ctx, sampleSession("up-3")); err != nil {
		t.Fatalf("open the session: %v", err)
	}

	for _, change := range []func(*UploadSession){
		func(s *UploadSession) { s.Path = "uploads/other.jpg" },
		func(s *UploadSession) { s.RootID = "root-other" },
		func(s *UploadSession) { s.TotalBytes = 11 << 20 },
		func(s *UploadSession) { s.ChunkSize = 2 << 20 },
		func(s *UploadSession) {
			s.ContentSHA256 = "d4735e3a265e16eee03f59718b9b5d03019c07d8b6c51f90da3a666eec13ab35"
		},
	} {
		claimed := sampleSession("up-3")
		change(&claimed)
		if _, err := store.BeginUpload(ctx, claimed); !errors.Is(err, ErrUploadConflict) {
			t.Fatalf("reopening with %+v returned %v, want ErrUploadConflict", claimed, err)
		}
	}
}

func TestBeginUploadValidatesTheSession(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()
	cases := map[string]func(*UploadSession){
		"no id":           func(s *UploadSession) { s.SessionID = "" },
		"no root":         func(s *UploadSession) { s.RootID = "" },
		"no destination":  func(s *UploadSession) { s.Path = "" },
		"no staging file": func(s *UploadSession) { s.StagingPath = "" },
		"negative size":   func(s *UploadSession) { s.TotalBytes = -1 },
		"no chunk size":   func(s *UploadSession) { s.ChunkSize = 0 },
		"negative chunks": func(s *UploadSession) { s.ChunkSize = -1 },
	}
	for name, change := range cases {
		t.Run(name, func(t *testing.T) {
			session := sampleSession("up-invalid")
			change(&session)
			if _, err := store.BeginUpload(ctx, session); err == nil {
				t.Fatalf("an upload session with %s was accepted", name)
			}
		})
	}
}

func TestCompleteUploadRefusesASessionWithAHole(t *testing.T) {
	// Completion is where the staging file is checksummed and moved. A caller that reached
	// here with bytes missing has a bug the customer would discover as a truncated file
	// weeks later.
	store := openStore(t)
	ctx := context.Background()
	session := sampleSession("up-4")

	if _, err := store.BeginUpload(ctx, session); err != nil {
		t.Fatalf("open the session: %v", err)
	}
	if _, err := store.RecordChunk(ctx, "up-4", ByteRange{0, 1 << 20}, noon); err != nil {
		t.Fatalf("record a chunk: %v", err)
	}
	if _, err := store.RecordChunk(ctx, "up-4", ByteRange{2 << 20, 3 << 20}, noon); err != nil {
		t.Fatalf("record a chunk: %v", err)
	}

	if err := store.CompleteUpload(ctx, "up-4", noon); err == nil {
		t.Fatal("a session with a hole in it was completed")
	}
	read, err := store.Upload(ctx, "up-4")
	if err != nil {
		t.Fatalf("read the session: %v", err)
	}
	if read.Complete() {
		t.Fatal("the refused completion still marked the session complete")
	}
}

func TestCompleteUploadMarksAWholeFile(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()
	session := sampleSession("up-5")
	finished := noon.Add(4 * time.Minute)

	if _, err := store.BeginUpload(ctx, session); err != nil {
		t.Fatalf("open the session: %v", err)
	}
	if _, err := store.RecordChunk(ctx, "up-5", ByteRange{0, session.TotalBytes}, noon); err != nil {
		t.Fatalf("record the whole file: %v", err)
	}
	if err := store.CompleteUpload(ctx, "up-5", finished); err != nil {
		t.Fatalf("complete the upload: %v", err)
	}

	read, err := store.Upload(ctx, "up-5")
	if err != nil {
		t.Fatalf("read the session: %v", err)
	}
	if !read.Complete() {
		t.Fatal("the completed session does not report itself complete")
	}
	if !read.CompletedAt.Equal(finished) {
		t.Fatalf("completed at %s, want %s", read.CompletedAt, finished)
	}
}

func TestForgetUploadReturnsTheSessionAndDropsItsRanges(t *testing.T) {
	// The bytes are the files package's to remove. A caller that dropped the record and no
	// longer knew where the parts were would leak them forever, so the session comes back.
	store := openStore(t)
	ctx := context.Background()

	if _, err := store.BeginUpload(ctx, sampleSession("up-6")); err != nil {
		t.Fatalf("open the session: %v", err)
	}
	if _, err := store.RecordChunk(ctx, "up-6", ByteRange{0, 1 << 20}, noon); err != nil {
		t.Fatalf("record a chunk: %v", err)
	}

	forgotten, err := store.ForgetUpload(ctx, "up-6")
	if err != nil {
		t.Fatalf("forget the session: %v", err)
	}
	if forgotten.StagingPath != sampleSession("up-6").StagingPath {
		t.Fatalf("the caller was not told where the parts are: %q", forgotten.StagingPath)
	}
	if _, err := store.Upload(ctx, "up-6"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("the session survived being forgotten: %v", err)
	}

	// The foreign key is what removes the ranges; without it they outlive their session and
	// the next upload to reuse the id inherits somebody else's progress.
	var orphans int
	if err := store.db.QueryRowContext(ctx,
		`SELECT count(*) FROM upload_range WHERE session_id = ?`, "up-6").Scan(&orphans); err != nil {
		t.Fatalf("count the orphaned ranges: %v", err)
	}
	if orphans != 0 {
		t.Fatalf("%d ranges outlived their session", orphans)
	}

	if _, err := store.ForgetUpload(ctx, "up-6"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("forgetting an unknown session returned %v, want ErrNotFound", err)
	}
}

func TestExpiredUploadsLeavesCompletedSessionsAlone(t *testing.T) {
	// A completed session is still referenced - a build reads the archive its staging file
	// holds - and is removed by whoever asked for it, not swept out from underneath them.
	store := openStore(t)
	ctx := context.Background()

	stale := sampleSession("up-stale")
	stale.ExpiresAt = noon.Add(time.Hour)
	if _, err := store.BeginUpload(ctx, stale); err != nil {
		t.Fatalf("open the stale session: %v", err)
	}

	fresh := sampleSession("up-fresh")
	fresh.ExpiresAt = noon.Add(48 * time.Hour)
	if _, err := store.BeginUpload(ctx, fresh); err != nil {
		t.Fatalf("open the fresh session: %v", err)
	}

	done := sampleSession("up-done")
	done.ExpiresAt = noon.Add(time.Hour)
	if _, err := store.BeginUpload(ctx, done); err != nil {
		t.Fatalf("open the finished session: %v", err)
	}
	if _, err := store.RecordChunk(ctx, "up-done", ByteRange{0, done.TotalBytes}, noon); err != nil {
		t.Fatalf("record the whole file: %v", err)
	}
	if err := store.CompleteUpload(ctx, "up-done", noon); err != nil {
		t.Fatalf("complete the upload: %v", err)
	}

	expired, err := store.ExpiredUploads(ctx, noon.Add(2*time.Hour))
	if err != nil {
		t.Fatalf("list the expired sessions: %v", err)
	}
	if len(expired) != 1 || expired[0].SessionID != "up-stale" {
		t.Fatalf("expired sessions = %+v, want only up-stale", expired)
	}
}
