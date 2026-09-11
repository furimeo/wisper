package backup

import (
	"context"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The fakes every test in this package builds on.
//
// No object store, no Docker and no SQLite. What is deliberately real is the filesystem and
// the archive format: the tar writer, the extractor, the digest and the two renames of a
// restore are the parts most worth testing, and a fake filesystem would test the fake.
//
// The local destination is real too, which is what lets a whole backup and a whole restore run
// end to end in a temporary directory. The S3 path gets its own fake server in s3_test.go,
// where the thing being tested is the protocol rather than the sequence.

func testLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// stateRoot is an absolute temporary state directory.
func stateRoot(t *testing.T) string {
	t.Helper()
	root, err := filepath.Abs(t.TempDir())
	if err != nil {
		t.Fatalf("resolve the temporary state directory: %v", err)
	}
	return root
}

// writeTree creates files from a map of relative path to contents. A name ending in "/" is an
// empty directory.
func writeTree(t *testing.T, root string, files map[string]string) {
	t.Helper()
	for name, contents := range files {
		target := filepath.Join(root, filepath.FromSlash(name))
		if strings.HasSuffix(name, "/") {
			if err := os.MkdirAll(target, 0o755); err != nil {
				t.Fatalf("create %s: %v", target, err)
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			t.Fatalf("create %s: %v", filepath.Dir(target), err)
		}
		if err := os.WriteFile(target, []byte(contents), 0o644); err != nil {
			t.Fatalf("write %s: %v", target, err)
		}
	}
}

// readTree is every regular file under root, keyed by slash-separated relative path.
func readTree(t *testing.T, root string) map[string]string {
	t.Helper()
	found := make(map[string]string)
	err := filepath.WalkDir(root, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if !entry.Type().IsRegular() {
			return nil
		}
		relative, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		contents, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		found[filepath.ToSlash(relative)] = string(contents)
		return nil
	})
	if err != nil {
		t.Fatalf("read the tree at %s: %v", root, err)
	}
	return found
}

// ---------------------------------------------------------------------------
// A runner wired to all of the above.
// ---------------------------------------------------------------------------

type harness struct {
	Runner    *Runner
	Store     *fakeStore
	Workloads *fakeWorkloads
	Dumps     *fakeDumps
	StateDir  string

	mu    sync.Mutex
	clock time.Time
}

func newHarness(t *testing.T) *harness {
	t.Helper()

	h := &harness{
		Store:     newFakeStore(),
		Workloads: newFakeWorkloads(),
		Dumps:     newFakeDumps(),
		StateDir:  stateRoot(t),
		clock:     time.Date(2026, 9, 11, 10, 0, 0, 0, time.UTC),
	}

	runner, err := New(Options{
		StateDir:  h.StateDir,
		Store:     h.Store,
		Workloads: h.Workloads,
		Databases: h.Dumps,
		Logger:    testLogger(),
		Now:       h.now,
		Retry:     retryPolicy{Attempts: 2, Base: time.Millisecond, Sleep: noSleep},
	})
	if err != nil {
		t.Fatalf("open the runner: %v", err)
	}
	h.Runner = runner
	return h
}

// now advances a second on every read, so two backups taken in one test still sort by which
// one landed second - and so a quiesce window is a defined number rather than whatever the
// machine's clock did.
func (h *harness) now() time.Time {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.clock = h.clock.Add(time.Second)
	return h.clock
}

// noSleep makes a retry schedule cost no wall-clock time.
func noSleep(ctx context.Context, _ time.Duration) error { return ctx.Err() }

// seedVolume puts a volume on disk, as a customer's application would have left it.
func (h *harness) seedVolume(t *testing.T, workloadID, volumeID string, files map[string]string) string {
	t.Helper()
	path, err := volumePath(h.StateDir, workloadID, volumeID)
	if err != nil {
		t.Fatalf("resolve the volume: %v", err)
	}
	if err := os.MkdirAll(path, 0o755); err != nil {
		t.Fatalf("create the volume: %v", err)
	}
	writeTree(t, path, files)
	return path
}

// localDestination is a DESTINATION_KIND_LOCAL destination under the node's backup directory.
func localTarget(prefix string) *wisperpb.BackupDestination {
	return &wisperpb.BackupDestination{
		Kind:        wisperpb.DestinationKind_DESTINATION_KIND_LOCAL,
		LocalPrefix: prefix,
	}
}

// objectsAt is every file under the local backup root, keyed by object key.
func (h *harness) objectsAt(t *testing.T) map[string]int64 {
	t.Helper()
	root := localRoot(h.StateDir)
	found := make(map[string]int64)
	err := filepath.WalkDir(root, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			if os.IsNotExist(err) {
				return nil
			}
			return err
		}
		if !entry.Type().IsRegular() {
			return nil
		}
		relative, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		found[filepath.ToSlash(relative)] = info.Size()
		return nil
	})
	if err != nil {
		t.Fatalf("list the local backup directory: %v", err)
	}
	return found
}

// backupRunFor and restoreRunFor are the rows a killed daemon would have left behind, for the
// recovery tests that start from that state rather than by killing anything.
func backupRunFor(backupID string) state.BackupRun {
	return state.BackupRun{
		BackupID:   backupID,
		Kind:       wisperpb.BackupTargetKind_BACKUP_TARGET_KIND_VOLUME,
		SubjectID:  "vol1",
		WorkloadID: "w1",
		Stage:      wisperpb.BackupStage_BACKUP_STAGE_QUIESCE,
		StartedAt:  time.Date(2026, 9, 11, 3, 0, 0, 0, time.UTC),
		Detail:     "quiesce",
	}
}

func restoreRunFor(restoreID string) state.RestoreRun {
	return state.RestoreRun{
		RestoreID:      restoreID,
		RestorePointID: "rp-20260911T100001Z-b1",
		Kind:           wisperpb.BackupTargetKind_BACKUP_TARGET_KIND_VOLUME,
		SubjectID:      "vol1",
		WorkloadID:     "w1",
		StartedAt:      time.Date(2026, 9, 11, 3, 0, 0, 0, time.UTC),
		Detail:         "unpacking the archive",
	}
}

// archiveKeys is the keys of the archives this package wrote, ignoring their checksum sidecars
// and anything in the destination that did not come from here.
func archiveKeys(objects map[string]int64) []string {
	var keys []string
	for key, size := range objects {
		if _, ours := parseGeneration(key, size); !ours {
			continue
		}
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}
