package files

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Reclaiming abandoned uploads, which nothing else on the node would ever notice.

func TestSweepingRemovesExpiredSessionsAndLeavesLiveOnes(t *testing.T) {
	test := newHarness(t)
	content := []byte(strings.Repeat("x", 2048))

	abandoned := session("sess-abandoned", "abandoned.bin", content, 1024)
	test.sendChunk(t, abandoned, chunkOf(content, 1024, 0))

	// An hour later - the retention policy in the test spec - the first is past its time.
	// The second has just started, so it is not.
	test.clock = noon.Add(2 * time.Hour)
	live := session("sess-live", "live.bin", content, 1024)
	test.sendChunk(t, live, chunkOf(content, 1024, 0))

	report, err := test.host.SweepUploads(t.Context(), test.clock)
	if err != nil {
		t.Fatalf("sweep: %v", err)
	}
	if report.Sessions != 1 {
		t.Fatalf("the sweep took %d sessions, want 1", report.Sessions)
	}

	if state := test.resume(t, "sess-abandoned"); state.GetKnown() {
		t.Error("the expired session survived the sweep")
	}
	if state := test.resume(t, "sess-live"); !state.GetKnown() {
		t.Error("a session that is still in progress was swept")
	}

	parts := filepath.Join(test.stateDir, "uploads", "parts")
	if _, err := os.Stat(filepath.Join(parts, "sess-abandoned")); !os.IsNotExist(err) {
		t.Errorf("the expired session's bytes are still on disk: %v", err)
	}
	if _, err := os.Stat(filepath.Join(parts, "sess-live")); err != nil {
		t.Errorf("a live session's bytes were deleted: %v", err)
	}
}

// The window the write order in upload_write.go leaves open on purpose: the daemon is
// killed after the bytes are written and before the range is recorded, so a parts file
// exists with no session behind it.
func TestSweepingRemovesPartsFilesWithNoSession(t *testing.T) {
	test := newHarness(t)
	parts := filepath.Join(test.stateDir, "uploads", "parts")
	if err := os.MkdirAll(parts, 0o700); err != nil {
		t.Fatalf("create %s: %v", parts, err)
	}
	if err := os.WriteFile(filepath.Join(parts, "sess-orphan"), []byte("bytes nobody claims"), 0o600); err != nil {
		t.Fatalf("plant the orphan: %v", err)
	}

	report, err := test.host.SweepUploads(t.Context(), test.clock)
	if err != nil {
		t.Fatalf("sweep: %v", err)
	}
	if report.Orphans != 1 {
		t.Fatalf("the sweep took %d orphans, want 1", report.Orphans)
	}
	if _, err := os.Stat(filepath.Join(parts, "sess-orphan")); !os.IsNotExist(err) {
		t.Error("the orphaned parts file is still there")
	}
}

func TestSweepingRemovesStagedArchivesNobodyBuiltFrom(t *testing.T) {
	test := newHarness(t)
	staging := filepath.Join(test.stateDir, "uploads", "staging")
	if err := os.MkdirAll(staging, 0o700); err != nil {
		t.Fatalf("create %s: %v", staging, err)
	}
	for _, name := range []string{"old.zip", "fresh.zip"} {
		if err := os.WriteFile(filepath.Join(staging, name), []byte("PK"), 0o600); err != nil {
			t.Fatalf("plant %s: %v", name, err)
		}
	}
	old := noon.Add(-48 * time.Hour)
	if err := os.Chtimes(filepath.Join(staging, "old.zip"), old, old); err != nil {
		t.Fatalf("age the archive: %v", err)
	}

	report, err := test.host.SweepUploads(t.Context(), test.clock)
	if err != nil {
		t.Fatalf("sweep: %v", err)
	}
	if report.StagedArchives != 1 {
		t.Fatalf("the sweep took %d staged archives, want 1", report.StagedArchives)
	}
	if _, err := os.Stat(filepath.Join(staging, "old.zip")); !os.IsNotExist(err) {
		t.Error("the stale archive is still there")
	}
	if _, err := os.Stat(filepath.Join(staging, "fresh.zip")); err != nil {
		t.Errorf("a fresh archive was swept: %v", err)
	}
}

func TestSweepingANodeThatHasNeverSeenAnUploadIsQuiet(t *testing.T) {
	test := newHarness(t)
	report, err := test.host.SweepUploads(t.Context(), test.clock)
	if err != nil {
		t.Fatalf("sweep: %v", err)
	}
	if report != (SweepReport{}) {
		t.Fatalf("the sweep claimed to reclaim %+v on an empty node", report)
	}
}

// The builder's view of a finished upload (internal/build, ports.go, Uploads).
func TestArchivePathAnswersTheBuilderForAFinishedUpload(t *testing.T) {
	test := newHarness(t)
	content := []byte("PK a deployment archive would be here")
	upload := session("sess-deploy", "deploy-412.zip", content, 1<<20)

	if _, err := test.host.ArchivePath(t.Context(), "sess-deploy"); err == nil {
		t.Fatal("a session that does not exist produced a path")
	}

	test.run(t, &wisperpb.FileRequest{
		RootId: stagingRootID,
		Op: &wisperpb.FileRequest_Write{Write: &wisperpb.WriteChunk{
			Session: upload, Chunk: chunkOf(content, 1<<20, 0),
		}},
	})
	if _, err := test.host.ArchivePath(t.Context(), "sess-deploy"); err == nil {
		t.Fatal("an unfinished upload produced a path, and a build would compile half a zip")
	}

	wantDone(t, test.run(t, &wisperpb.FileRequest{
		RootId: stagingRootID,
		Op:     &wisperpb.FileRequest_Complete{Complete: &wisperpb.CompleteUpload{SessionId: "sess-deploy"}},
	}))

	path, err := test.host.ArchivePath(t.Context(), "sess-deploy")
	if err != nil {
		t.Fatalf("the finished archive has no path: %v", err)
	}
	written, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	if string(written) != string(content) {
		t.Fatalf("the archive holds %q", written)
	}

	if err := os.Remove(path); err != nil {
		t.Fatalf("remove the archive: %v", err)
	}
	if _, err := test.host.ArchivePath(t.Context(), "sess-deploy"); err == nil {
		t.Fatal("a path was produced for an archive that is no longer on disk")
	}
}
