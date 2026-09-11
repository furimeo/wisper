package files

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"testing"
	"time"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// What every test in this package shares: a real state database, a real directory tree and
// a clock that does not move unless a test moves it.
//
// The store is the real one rather than a fake. It is pure-Go SQLite on a temporary path,
// so it costs milliseconds, and the interesting half of an upload is precisely the
// agreement between the bytes on disk and the ranges in that database - a fake would
// remove the thing being tested.
//
// The filesystem is real for the same reason, and more so: this package exists to be
// correct about symlinks, O_EXCL and openat, and none of those mean anything against an
// in-memory tree.

var noon = time.Date(2026, time.March, 4, 12, 0, 0, 0, time.UTC)

const (
	volumeRootID  = "root-data"
	siteRootID    = "root-site"
	stagingRootID = "upload-staging"
)

// harness is one node's worth of file manager, with the paths a test needs to look behind
// it.
type harness struct {
	host     *Host
	store    *state.Store
	stateDir string
	// volume is the directory behind volumeRootID, for a test that needs to plant
	// something the file manager did not create - a symlink, an unreadable file, a tree
	// that was already there.
	volume string
	clock  time.Time
}

func newHarness(t *testing.T) *harness {
	t.Helper()
	stateDir := t.TempDir()

	store, err := state.Open(t.Context(), state.Path(stateDir))
	if err != nil {
		t.Fatalf("open the state database: %v", err)
	}
	t.Cleanup(func() { store.Close() })

	if err := store.SaveSpec(t.Context(), specWithRoots(), "test", noon); err != nil {
		t.Fatalf("save the spec: %v", err)
	}

	test := &harness{store: store, stateDir: stateDir, clock: noon}
	test.volume = filepath.Join(stateDir, "volumes", "wl-api", "vol-data")
	if err := os.MkdirAll(test.volume, 0o755); err != nil {
		t.Fatalf("create the volume directory: %v", err)
	}

	host, err := New(Options{
		StateDir: stateDir,
		Store:    store,
		Now:      func() time.Time { return test.clock },
		// Discarded: the daemon's log is not what any of these tests assert on, and a
		// package whose passing suite prints two hundred lines trains everybody to ignore
		// the output that matters.
		Logger: slog.New(slog.DiscardHandler),
	})
	if err != nil {
		t.Fatalf("open the file host: %v", err)
	}
	test.host = host
	return test
}

// specWithRoots publishes one writable volume, one read-only site and the node's staging
// root - the three kinds that exist (docs/contracts/node-spec.md section 3.10).
func specWithRoots() *wisperpb.NodeSpec {
	return &wisperpb.NodeSpec{
		Generation: 7,
		IssuedAt:   timestamppb.New(noon),
		FileRoots: []*wisperpb.FileRoot{
			{
				Id:       stagingRootID,
				Kind:     wisperpb.FileRootKind_FILE_ROOT_KIND_UPLOAD_STAGING,
				Label:    "Upload staging",
				Writable: true,
			},
			{
				Id:         volumeRootID,
				Kind:       wisperpb.FileRootKind_FILE_ROOT_KIND_VOLUME,
				WorkloadId: "wl-api",
				VolumeId:   "vol-data",
				Label:      "api / data",
				Writable:   true,
			},
			{
				Id:         siteRootID,
				Kind:       wisperpb.FileRootKind_FILE_ROOT_KIND_SITE,
				WorkloadId: "wl-site",
				Label:      "marketing releases",
				Writable:   false,
			},
		},
		Retention: &wisperpb.RetentionPolicy{OrphanUploadTtlSeconds: 3600},
	}
}

// run performs one request and returns everything the node said about it.
func (h *harness) run(t *testing.T, request *wisperpb.FileRequest) []*wisperpb.FileEvent {
	t.Helper()
	return h.runWith(t, t.Context(), request)
}

func (h *harness) runWith(t *testing.T, ctx context.Context, request *wisperpb.FileRequest) []*wisperpb.FileEvent {
	t.Helper()
	events, err := h.perform(ctx, request)
	if err != nil {
		t.Fatalf("the file host could not answer at all: %v", err)
	}
	if len(events) == 0 {
		t.Fatal("the operation produced no events, so the panel would wait forever")
	}
	return events
}

// perform is run without a *testing.T, for the concurrency tests: t.Fatal may only be
// called from the goroutine running the test, so anything spawned collects its error and
// hands it back instead.
func (h *harness) perform(ctx context.Context, request *wisperpb.FileRequest) ([]*wisperpb.FileEvent, error) {
	if request.RequestId == "" {
		request.RequestId = "req-1"
	}
	if request.RootId == "" {
		request.RootId = volumeRootID
	}
	sink := &recorder{}
	if err := h.host.Handle(ctx, request, sink); err != nil {
		return nil, err
	}
	if len(sink.events) == 0 {
		return nil, errors.New("the operation produced no events, so the panel would wait forever")
	}
	return sink.events, nil
}

// write plants a file directly in the volume, bypassing the file manager. Used to build the
// tree a test is about to operate on.
func (h *harness) write(t *testing.T, relative, content string) {
	t.Helper()
	full := filepath.Join(h.volume, filepath.FromSlash(relative))
	if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
		t.Fatalf("create %s: %v", filepath.Dir(full), err)
	}
	if err := os.WriteFile(full, []byte(content), 0o644); err != nil {
		t.Fatalf("write %s: %v", full, err)
	}
}

func (h *harness) mkdir(t *testing.T, relative string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Join(h.volume, filepath.FromSlash(relative)), 0o755); err != nil {
		t.Fatalf("create %s: %v", relative, err)
	}
}

// symlinkOrSkip plants a symbolic link, or skips the test where the host will not make one.
//
// Windows needs either developer mode or an administrator, and a developer running the
// suite on a machine with neither should see the platform-independent tests pass rather
// than a failure they cannot act on. On Linux, where the node runs, this never skips.
func (h *harness) symlinkOrSkip(t *testing.T, target, name string) {
	t.Helper()
	link := filepath.Join(h.volume, filepath.FromSlash(name))
	if err := os.MkdirAll(filepath.Dir(link), 0o755); err != nil {
		t.Fatalf("create %s: %v", filepath.Dir(link), err)
	}
	if err := os.Symlink(target, link); err != nil {
		if runtime.GOOS == "windows" && errors.Is(err, fs.ErrPermission) {
			t.Skipf("this host does not allow creating symbolic links: %v", err)
		}
		t.Fatalf("link %s -> %s: %v", link, target, err)
	}
}

// recorder is the events sink, and the assertion surface for every test here.
type recorder struct {
	mu     sync.Mutex
	events []*wisperpb.FileEvent
}

func (r *recorder) Send(event *wisperpb.FileEvent) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.events = append(r.events, event)
	return nil
}

// only returns the single event an operation produced, failing when there was more or less
// than one. Most operations answer with exactly one, and a test that silently looked at the
// first of three would be testing the wrong thing.
func only(t *testing.T, events []*wisperpb.FileEvent) *wisperpb.FileEvent {
	t.Helper()
	if len(events) != 1 {
		t.Fatalf("expected one event, got %d: %v", len(events), events)
	}
	return events[0]
}

// wantError asserts the operation failed with a particular code, and returns the error so a
// test can look at its detail.
func wantError(t *testing.T, events []*wisperpb.FileEvent, code wisperpb.FileErrorCode) *wisperpb.FileError {
	t.Helper()
	last := events[len(events)-1]
	failed := last.GetError()
	if failed == nil {
		t.Fatalf("expected %s, got %T", code, last.GetResult())
	}
	if failed.GetCode() != code {
		t.Fatalf("expected %s, got %s: %s", code, failed.GetCode(), failed.GetDetail())
	}
	return failed
}

// wantDone asserts the operation succeeded with no payload of its own.
func wantDone(t *testing.T, events []*wisperpb.FileEvent) *wisperpb.OperationDone {
	t.Helper()
	last := events[len(events)-1]
	done := last.GetDone()
	if done == nil {
		if failed := last.GetError(); failed != nil {
			t.Fatalf("expected success, got %s: %s", failed.GetCode(), failed.GetDetail())
		}
		t.Fatalf("expected an OperationDone, got %T", last.GetResult())
	}
	return done
}

func digestOf(content []byte) string {
	sum := sha256.Sum256(content)
	return hex.EncodeToString(sum[:])
}
