package build

import (
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The fakes every test in this package builds on.
//
// No Docker, no panel and no SQLite: a build is a state machine over a filesystem and an
// engine, and both stand in for cheaply. What is deliberately real is the filesystem - the
// symlink swap, the release copy and the archive extractor are the parts most worth
// testing, and a fake filesystem would test the fake.

// requireSymlinkSwap skips a test on a platform where a symlink cannot be replaced
// atomically.
//
// That platform is Windows, where rename(2) over an existing symlink is refused, and where
// sasayaki does not run: the daemon is a Linux binary and the swap is the one operation
// whose correctness is a property of the kernel underneath it. Skipping is honest - the
// test really cannot say anything here - and the same test is the load-bearing one when
// `go test ./...` runs in WSL.
func requireSymlinkSwap(t *testing.T) {
	t.Helper()

	directory := t.TempDir()
	if err := os.Mkdir(filepath.Join(directory, "a"), 0o755); err != nil {
		t.Fatalf("prepare the probe: %v", err)
	}
	link := filepath.Join(directory, "link")
	if err := os.Symlink("a", link); err != nil {
		t.Skipf("this platform will not create symlinks (%v); the release swap is Linux-only", err)
	}
	temporary := filepath.Join(directory, "link.tmp")
	if err := os.Symlink("a", temporary); err != nil {
		t.Skipf("this platform will not create symlinks (%v); the release swap is Linux-only", err)
	}
	if err := os.Rename(temporary, link); err != nil {
		t.Skipf("this platform will not replace a symlink atomically (%v); sasayaki runs on "+
			"Linux, where it does, and %s is not it", err, runtime.GOOS)
	}
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

func testLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// writeTree creates files from a map of relative path to contents, making directories on
// the way down. A path ending in "/" is an empty directory.
func writeTree(t *testing.T, root string, files map[string]string) {
	t.Helper()
	for name, contents := range files {
		target := filepath.Join(root, filepath.FromSlash(name))
		if len(name) > 0 && name[len(name)-1] == '/' {
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
//
// The root is resolved through symlinks first, because the interesting root in this package
// is `current` - a link - and a walk that started at the link itself would report an empty
// tree rather than what the edge serves through it.
func readTree(t *testing.T, root string) map[string]string {
	t.Helper()
	resolved, err := filepath.EvalSymlinks(root)
	if err != nil {
		t.Fatalf("resolve %s: %v", root, err)
	}
	root = resolved

	found := make(map[string]string)
	err = filepath.WalkDir(root, func(path string, entry os.DirEntry, err error) error {
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
// A builder wired to all of the above.
// ---------------------------------------------------------------------------

type harness struct {
	Builder  *Builder
	Releases *Releases
	Engine   *fakeEngine
	Store    *fakeStore
	Uploads  fakeUploads
	Sink     *fakeSink
	StateDir string
	clock    time.Time
}

func newHarness(t *testing.T) *harness {
	t.Helper()

	h := &harness{
		Engine:   newFakeEngine(),
		Store:    newFakeStore(),
		Uploads:  fakeUploads{files: make(map[string]string)},
		Sink:     &fakeSink{},
		StateDir: stateRoot(t),
		clock:    time.Date(2026, 9, 11, 10, 0, 0, 0, time.UTC),
	}

	releases, err := NewReleases(h.StateDir, testLogger())
	if err != nil {
		t.Fatalf("open the release tree: %v", err)
	}
	h.Releases = releases

	builder, err := New(Options{
		StateDir: h.StateDir,
		Engine:   h.Engine,
		Store:    h.Store,
		Uploads:  h.Uploads,
		Logs:     h.Sink,
		Releases: releases,
		Logger:   testLogger(),
		Now:      h.now,
	})
	if err != nil {
		t.Fatalf("open the builder: %v", err)
	}
	h.Builder = builder
	return h
}

// now advances a step on every read, so two releases produced in one test still order by
// which one landed second without anything having to sleep.
func (h *harness) now() time.Time {
	h.clock = h.clock.Add(time.Second)
	return h.clock
}

// seedRelease puts a finished release on disk, as a previous build would have left it.
func (h *harness) seedRelease(t *testing.T, workloadID, releaseID string, files map[string]string) string {
	t.Helper()
	directory, err := releaseDir(h.StateDir, workloadID, releaseID)
	if err != nil {
		t.Fatalf("resolve release %s: %v", releaseID, err)
	}
	if err := os.MkdirAll(directory, 0o755); err != nil {
		t.Fatalf("create release %s: %v", releaseID, err)
	}
	writeTree(t, directory, files)
	stamp := h.now()
	if err := os.Chtimes(directory, stamp, stamp); err != nil {
		t.Fatalf("stamp release %s: %v", releaseID, err)
	}
	return directory
}

// archiveSource is a StartBuild's archive half, for a test that only needs the fetch.
func archiveSource(session, digest string) *wisperpb.ArchiveSource {
	return &wisperpb.ArchiveSource{UploadSessionId: session, Sha256: digest}
}
