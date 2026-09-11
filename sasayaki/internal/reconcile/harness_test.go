package reconcile

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// What every test in this package shares.
//
// The runtime, the edge, the sites and the panel are fakes; the store is the real SQLite
// one, on a temporary path. That combination is deliberate: the interesting behaviour of
// this package is what it decides to do to containers, and the interesting failure of its
// predecessor was forgetting everything on restart - so the collaborator that has to be
// real is the disk.
//
// Nothing here reads the clock. A test that sleeps to make a timestamp move is slow when it
// passes and flaky when it does not.

var noon = time.Date(2026, time.March, 4, 12, 0, 0, 0, time.UTC)

// errEngineDown is what the fake runtime answers with when a test takes Docker away.
var errEngineDown = errors.New("cannot connect to the docker daemon at unix:///var/run/docker.sock")

type clock struct {
	mu  sync.Mutex
	now time.Time
}

func (c *clock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *clock) advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = c.now.Add(d)
}

// harness is a loop and everything it was built from, so a test can reach in and break one
// collaborator without rebuilding the rest.
type harness struct {
	loop    *Loop
	runtime *fakeRuntime
	sites   *fakeSites
	edge    *fakeEdge
	panel   *fakeReporter
	engines *fakeDatabases
	store   *state.Store
	clock   *clock

	// Set by a test to make the real store's LoadSpec fail, which is the one thing a
	// checksum failure or an unreadable file looks like from in here.
	loadSpecErr error

	mu       sync.Mutex
	notified []string
}

// gatedStore is the real store with one method a test can take away.
//
// Everything else goes straight through, so the pass under test is still writing to and
// reading from real SQLite - which is the point of using it at all.
type gatedStore struct {
	Store
	harness *harness
}

func (g gatedStore) LoadSpec(ctx context.Context) (state.StoredSpec, error) {
	if g.harness.loadSpecErr != nil {
		return state.StoredSpec{}, g.harness.loadSpecErr
	}
	return g.Store.LoadSpec(ctx)
}

func newHarness(t *testing.T) *harness {
	t.Helper()

	store, err := state.Open(context.Background(), filepath.Join(t.TempDir(), state.FileName))
	if err != nil {
		t.Fatalf("open the state database: %v", err)
	}
	t.Cleanup(func() { store.Close() })

	return buildHarness(t, store)
}

// buildHarness wires a loop onto an existing store, which is how the restart test proves
// that a second daemon over the same file converges from what the first one wrote.
func buildHarness(t *testing.T, store *state.Store) *harness {
	t.Helper()

	h := &harness{
		runtime: newFakeRuntime(),
		sites:   newFakeSites(),
		edge:    &fakeEdge{},
		panel:   &fakeReporter{},
		engines: &fakeDatabases{},
		store:   store,
		clock:   &clock{now: noon},
	}

	loop, err := New(Options{
		Runtime:   h.runtime,
		Sites:     h.sites,
		Edge:      h.edge,
		Store:     gatedStore{Store: store, harness: h},
		Databases: h.engines,
		Reporter:  h.panel,
		Logger:    discardLogger(),
		Now:       h.clock.Now,
		// Negative disables the watchdog, so a test that is not about systemd does not
		// have to think about it.
		WatchdogInterval: -1,
		Notify: func(notification string) error {
			h.mu.Lock()
			defer h.mu.Unlock()
			h.notified = append(h.notified, notification)
			return nil
		},
	})
	if err != nil {
		t.Fatalf("build the loop: %v", err)
	}
	h.loop = loop
	return h
}

// publish stores a spec the way the control stream would have.
func (h *harness) publish(t *testing.T, message *wisperpb.NodeSpec) {
	t.Helper()
	if err := h.store.SaveSpec(context.Background(), message, "a test", h.clock.Now()); err != nil {
		t.Fatalf("save the spec at generation %d: %v", message.GetGeneration(), err)
	}
}

// pass runs one reconcile pass and fails the test if it did not converge.
func (h *harness) pass(t *testing.T) {
	t.Helper()
	if _, err := h.loop.pass(context.Background(), "a test"); err != nil {
		t.Fatalf("reconcile pass: %v", err)
	}
}

// failingPass runs one pass that is expected not to converge, and returns why.
func (h *harness) failingPass(t *testing.T) error {
	t.Helper()
	_, err := h.loop.pass(context.Background(), "a test")
	if err == nil {
		t.Fatal("the pass reported success where a failure was expected")
	}
	return err
}

func (h *harness) statusOf(t *testing.T, workloadID string) *wisperpb.WorkloadStatus {
	t.Helper()
	for _, status := range h.panel.latest(t).GetWorkloads() {
		if status.GetWorkloadId() == workloadID {
			return status
		}
	}
	t.Fatalf("no status was reported for workload %s", workloadID)
	return nil
}

// appSpec is one app, one site and one route: enough shape that a pass exercises both
// halves of convergence without a test having to read forty lines of fixture.
func appSpec(generation uint64) *wisperpb.NodeSpec {
	return &wisperpb.NodeSpec{
		Generation: generation,
		IssuedAt:   timestamppb.New(noon),
		Workloads: []*wisperpb.Workload{{
			Id:            "wl-api",
			Kind:          wisperpb.WorkloadKind_WORKLOAD_KIND_APP,
			Name:          "api",
			Image:         "docker.io/library/node:24-alpine",
			Command:       []string{"server.js"},
			Env:           []*wisperpb.EnvVar{{Name: "NODE_ENV", Value: "production"}},
			Limits:        &wisperpb.ResourceLimits{NanoCpus: 500_000_000, MemoryBytes: 512 << 20},
			Mounts:        []*wisperpb.Mount{{VolumeId: "vol-data", Kind: wisperpb.MountKind_MOUNT_KIND_VOLUME, Target: "/srv/data"}},
			Restart:       &wisperpb.RestartPolicy{Mode: wisperpb.RestartPolicyMode_RESTART_POLICY_MODE_UNLESS_STOPPED},
			Runtime:       wisperpb.ContainerRuntime_CONTAINER_RUNTIME_RUNSC,
			DesiredState:  wisperpb.DesiredState_DESIRED_STATE_RUNNING,
			TenantNetwork: "wisper-tenant-7",
		}, {
			Id:           "wl-site",
			Kind:         wisperpb.WorkloadKind_WORKLOAD_KIND_SITE,
			Name:         "marketing",
			DesiredState: wisperpb.DesiredState_DESIRED_STATE_RUNNING,
			ReleaseId:    "dep-1042",
		}},
		Routes: []*wisperpb.Route{{
			Domain:     "api.example.test",
			WorkloadId: "wl-api",
			Port:       8080,
			TlsMode:    wisperpb.TlsMode_TLS_MODE_ON_DEMAND,
			ForceHttps: true,
		}},
		ReconcileIntervalSeconds: 15,
	}
}

// workloadOf reads one workload out of a spec message so a test can change a field without
// rebuilding the whole fixture.
func workloadOf(message *wisperpb.NodeSpec, id string) *wisperpb.Workload {
	for _, workload := range message.GetWorkloads() {
		if workload.GetId() == id {
			return workload
		}
	}
	return nil
}

// discardLogger is for the handful of tests that call a function taking a logger without
// caring what it writes.
func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}
