package backup

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"math/rand/v2"
	"net/http"
	"os"
	"testing"
)

// The object-store half: multipart uploads, resuming one that was interrupted, and the
// listing that retention depends on being complete.
//
// A little over three hundred lines, and kept together because every test in it is a statement
// about the same protocol against the same fake server; splitting by which method is under
// test would separate the resume test from the upload test it is the other half of.

// incompressible is a block of bytes gzip cannot shrink, so a test that wants four parts gets
// four parts rather than one very small one.
func incompressible(length int) string {
	source := rand.NewChaCha8([32]byte{7})
	block := make([]byte, length)
	source.Read(block)
	return string(block)
}

// s3For builds a destination pointed at the fake store, with the part size a test wants.
func s3For(t *testing.T, store *fakeS3, prefix string, partSize int64, attempts int) *s3Destination {
	t.Helper()
	destination, err := newS3Destination(store.destination(prefix).GetS3(), http.DefaultClient,
		retryPolicy{Attempts: attempts, Sleep: noSleep}, partSize, testLogger())
	if err != nil {
		t.Fatalf("open the S3 destination: %v", err)
	}
	return destination
}

// stagedOf writes contents into the work directory the way a snapshot would.
func stagedOf(t *testing.T, stateDir, runID, contents string) staged {
	t.Helper()
	archive, err := stageArchive(stateDir, runID, volumeExtension, func(out io.Writer) error {
		_, err := io.WriteString(out, contents)
		return err
	})
	if err != nil {
		t.Fatalf("stage the archive: %v", err)
	}
	return archive
}

func openStaged(t *testing.T, archive staged) *os.File {
	t.Helper()
	file, err := os.Open(archive.Path)
	if err != nil {
		t.Fatalf("open the staged archive: %v", err)
	}
	t.Cleanup(func() { file.Close() })
	return file
}

// An upload that dies part way is resumed rather than restarted: the parts the store already
// accepted are not sent a second time.
func TestAnInterruptedMultipartUploadResumesFromTheJournal(t *testing.T) {
	h := newHarness(t)
	store := newFakeS3(t)
	destination := s3For(t, store, "nightly", 4096, 1)
	archive := stagedOf(t, h.StateDir, "b1", incompressible(20<<10))
	if archive.Size <= 3*4096 {
		t.Fatalf("the archive is %d bytes, which is not enough parts to interrupt", archive.Size)
	}

	journal, err := openJournal(h.StateDir, "b1")
	if err != nil {
		t.Fatalf("open the journal: %v", err)
	}
	const key = "nightly/vol1/20260911T100001Z-b1.tar.gz"

	// The third part refuses, once and for all, so the first attempt gets exactly two parts in.
	store.failPart[3] = 1000
	err = destination.Upload(context.Background(), key, openStaged(t, archive), archive.Size, journal)
	if err == nil {
		t.Fatal("the upload reported success with a part the store never accepted")
	}
	if got := len(journal.parts()); got != 2 {
		t.Fatalf("the journal remembers %d parts, want the two that were accepted", got)
	}

	// The store recovers and the same command comes round again.
	store.failPart[3] = 0
	if err := destination.Upload(context.Background(), key, openStaged(t, archive), archive.Size, journal); err != nil {
		t.Fatalf("resume the upload: %v", err)
	}

	counts := store.counts()
	if counts[1] != 1 || counts[2] != 1 {
		t.Errorf("parts already at the store were sent again: %v", counts)
	}
	if counts[3] != 1 {
		t.Errorf("the part that failed was accepted %d times, want once", counts[3])
	}

	body, found := store.object(key)
	if !found {
		t.Fatal("the object is not at the store")
	}
	if int64(len(body)) != archive.Size {
		t.Errorf("the assembled object is %d bytes, want %d", len(body), archive.Size)
	}
	if got := sha256Hex(body); got != archive.SHA256 {
		t.Errorf("the assembled object hashes to %s, want %s", got, archive.SHA256)
	}
	if store.unfinishedUploads() != 0 {
		t.Errorf("%d multipart uploads were left open", store.unfinishedUploads())
	}
}

// An archive that fits in one part is one request. Three round trips to store four kilobytes
// is three times the latency for nothing.
func TestASmallArchiveIsUploadedInOneRequest(t *testing.T) {
	h := newHarness(t)
	store := newFakeS3(t)
	destination := s3For(t, store, "nightly", 1<<20, 1)
	archive := stagedOf(t, h.StateDir, "b1", "a very small archive")

	journal, err := openJournal(h.StateDir, "b1")
	if err != nil {
		t.Fatalf("open the journal: %v", err)
	}
	const key = "nightly/vol1/20260911T100001Z-b1.tar.gz"
	if err := destination.Upload(context.Background(), key, openStaged(t, archive), archive.Size, journal); err != nil {
		t.Fatalf("upload: %v", err)
	}

	if got := store.counts(); len(got) != 0 {
		t.Errorf("a small archive went through the multipart protocol: %v", got)
	}
	if _, found := store.object(key); !found {
		t.Fatal("the object is not at the store")
	}
}

// Retention deletes what a listing does not contain, so a listing that stopped at the first
// page would decide that generations it never saw are not there.
func TestListingFollowsEveryContinuationToken(t *testing.T) {
	store := newFakeS3(t)
	store.pageSize = 2
	destination := s3For(t, store, "nightly", 1<<20, 1)
	ctx := context.Background()

	for index := range 7 {
		key := fmt.Sprintf("nightly/vol1/object-%d.txt", index)
		if err := destination.Put(ctx, key, []byte("x")); err != nil {
			t.Fatalf("put %s: %v", key, err)
		}
	}

	found, err := destination.List(ctx, "nightly/vol1/")
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(found) != 7 {
		t.Errorf("the listing returned %d of 7 objects across pages of 2", len(found))
	}
}

// A whole backup and a whole restore through the object store, with the archive large enough
// to go in parts and `verify` asked for, so the digest makes a round trip.
func TestBackupAndRestoreThroughAMultipartUpload(t *testing.T) {
	h := newHarness(t)
	h.Runner.partSize = 4096
	h.Workloads.running["w1"] = true
	store := newFakeS3(t)

	original := map[string]string{"blob.bin": incompressible(20 << 10), "notes.txt": "readable"}
	h.seedVolume(t, "w1", "vol1", original)

	completed, err := h.Runner.RunBackup(context.Background(), volumeBackup(store.destination("nightly")))
	if err != nil {
		t.Fatalf("run the backup: %v", err)
	}
	if !completed.GetSuccess() {
		t.Fatalf("the backup failed: %s (stage %s)", completed.GetDetail(), completed.GetFailedStage())
	}
	if !completed.GetVerified() {
		t.Error("verify was asked for and the result does not say it passed")
	}
	if len(store.counts()) < 2 {
		t.Errorf("a %d byte archive with a 4096 byte part size produced %v",
			completed.GetSizeBytes(), store.counts())
	}

	live, err := volumePath(h.StateDir, "w1", "vol1")
	if err != nil {
		t.Fatalf("resolve the volume: %v", err)
	}
	if err := os.RemoveAll(live); err != nil {
		t.Fatalf("remove the volume: %v", err)
	}

	request := volumeRestore(completed.GetLocation())
	request.Source = store.destination("nightly")
	restored, err := h.Runner.RestoreBackup(context.Background(), request)
	if err != nil {
		t.Fatalf("run the restore: %v", err)
	}
	if !restored.GetSuccess() {
		t.Fatalf("the restore failed: %s", restored.GetDetail())
	}
	if got := readTree(t, live); got["blob.bin"] != original["blob.bin"] || got["notes.txt"] != "readable" {
		t.Error("the restored volume does not match what was backed up")
	}
}

// The daemon being stopped mid-upload is not a failed backup. The row stays open, the archive
// stays staged, and the same command carries on - which is the difference between a resumable
// interruption and pausing the customer's application a second time.
func TestABackupInterruptedMidUploadCarriesOnWhenTheCommandComesAgain(t *testing.T) {
	h := newHarness(t)
	h.Runner.partSize = 4096
	h.Workloads.running["w1"] = true
	store := newFakeS3(t)
	h.seedVolume(t, "w1", "vol1", map[string]string{"blob.bin": incompressible(20 << 10)})

	shuttingDown, stop := context.WithCancel(context.Background())
	store.onPart = func(number int) {
		if number == 2 {
			stop()
		}
	}

	_, err := h.Runner.RunBackup(shuttingDown, volumeBackup(store.destination("nightly")))
	if err == nil {
		t.Fatal("an interrupted backup reported an outcome; it has to fail the command so the " +
			"panel sends the same one again")
	}
	stop()

	run, err := h.Store.Backup(context.Background(), "b1")
	if err != nil {
		t.Fatalf("read the run back: %v", err)
	}
	if run.Finished {
		t.Fatal("the interrupted run was closed out, so nothing can resume it")
	}
	staged, found, err := loadStagedArchive(h.StateDir, "b1", volumeExtension)
	if err != nil || !found {
		t.Fatalf("the staged archive did not survive the interruption: found=%v err=%v", found, err)
	}
	pausesBefore := len(h.Workloads.seen())

	store.onPart = nil
	completed, err := h.Runner.RunBackup(context.Background(), volumeBackup(store.destination("nightly")))
	if err != nil {
		t.Fatalf("carry the backup on: %v", err)
	}
	if !completed.GetSuccess() {
		t.Fatalf("the resumed backup failed: %s (stage %s)", completed.GetDetail(), completed.GetFailedStage())
	}

	if got := len(h.Workloads.seen()); got != pausesBefore {
		t.Errorf("the resumed run touched the workload again (%d events, was %d); the archive was "+
			"already on disk", got, pausesBefore)
	}
	if got := store.counts()[1]; got != 1 {
		t.Errorf("part 1 was uploaded %d times; a resume must not send what the store already has", got)
	}
	if completed.GetSha256() != staged.SHA256 {
		t.Errorf("the resumed run reported %s and the staged archive is %s",
			completed.GetSha256(), staged.SHA256)
	}
	body, present := store.object(completed.GetLocation())
	if !present {
		t.Fatalf("the object is not at %s", completed.GetLocation())
	}
	if sha256Hex(body) != staged.SHA256 {
		t.Error("the assembled object does not hash to the archive that was staged")
	}
}

func sha256Hex(body []byte) string {
	sum := sha256.Sum256(body)
	return hex.EncodeToString(sum[:])
}

// Signing is over what is actually sent, including the query string, which is the part people
// leave out. Asserted here rather than only implicitly by the fake store, so a failure names
// the cause.
func TestTheCanonicalQueryStringIsSortedAndEncoded(t *testing.T) {
	request, err := http.NewRequest(http.MethodPut,
		"https://example.invalid/bucket/key?uploadId=b+c&partNumber=2", bytes.NewReader(nil))
	if err != nil {
		t.Fatalf("build the request: %v", err)
	}
	if got, want := canonicalQuery(request), "partNumber=2&uploadId=b%20c"; got != want {
		t.Errorf("canonical query is %q, want %q", got, want)
	}
}

func TestPathsAreEncodedWithoutTouchingTheSeparators(t *testing.T) {
	if got, want := escapePath("/bucket/a b/c~d"), "/bucket/a%20b/c~d"; got != want {
		t.Errorf("escaped %q, want %q", got, want)
	}
}
