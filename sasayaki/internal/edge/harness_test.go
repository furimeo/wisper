package edge

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/netip"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/state"
)

// What every test in this package builds on.
//
// No Caddy is started anywhere in here, and that is the point of the shape the package
// has: the route table, the on-demand decision and the request handling are all reachable
// without a listener, a certificate or a Docker socket. What Caddy is left owning - the
// sockets and the handshake - is the part a unit test could not prove anything about
// anyway, and docs/verify-on-linux.md is where it gets exercised.

// fakeBackends answers for the container addresses.
type fakeBackends struct {
	mu        sync.Mutex
	addresses map[string]netip.AddrPort
	failure   error
	calls     int
}

func newFakeBackends() *fakeBackends {
	return &fakeBackends{addresses: make(map[string]netip.AddrPort)}
}

func (f *fakeBackends) at(workloadID string, address string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.addresses[workloadID] = netip.MustParseAddrPort(address)
}

func (f *fakeBackends) Address(_ context.Context, workloadID string, port uint16) (netip.AddrPort, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls++
	if f.failure != nil {
		return netip.AddrPort{}, f.failure
	}
	address, known := f.addresses[workloadID]
	if !known {
		return netip.AddrPort{}, errors.New("no container for " + workloadID + " is running on this node")
	}
	if address.Port() == 0 {
		address = netip.AddrPortFrom(address.Addr(), port)
	}
	return address, nil
}

// fakeStore is the certificate bookkeeping, in memory.
type fakeStore struct {
	mu      sync.Mutex
	records map[string]state.Certificate
	seeded  []state.Certificate
	// readErr and writeErr let a test prove that a broken disk does not stop the edge
	// from serving or from reporting.
	readErr  error
	writeErr error
	pruneErr error
	pruned   [][]string
}

func newFakeStore() *fakeStore {
	return &fakeStore{records: make(map[string]state.Certificate)}
}

func (f *fakeStore) SaveCertificate(_ context.Context, certificate state.Certificate) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.writeErr != nil {
		return f.writeErr
	}
	f.records[certificate.Domain] = certificate
	return nil
}

func (f *fakeStore) Certificates(context.Context) ([]state.Certificate, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.readErr != nil {
		return nil, f.readErr
	}
	return f.seeded, nil
}

func (f *fakeStore) PruneCertificates(_ context.Context, keep []string) (int64, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.pruned = append(f.pruned, append([]string(nil), keep...))
	if f.pruneErr != nil {
		return 0, f.pruneErr
	}

	wanted := make(map[string]struct{}, len(keep))
	for _, domain := range keep {
		wanted[domain] = struct{}{}
	}
	removed := int64(0)
	for domain := range f.records {
		if _, kept := wanted[domain]; !kept {
			delete(f.records, domain)
			removed++
		}
	}
	return removed, nil
}

func (f *fakeStore) saved(domain string) (state.Certificate, bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	record, found := f.records[domain]
	return record, found
}

// harness is one edge and the fakes behind it.
type harness struct {
	t        *testing.T
	edge     *Edge
	backends *fakeBackends
	store    *fakeStore
	stateDir string
	clock    time.Time
}

func newHarness(t *testing.T) *harness {
	t.Helper()
	return newHarnessIn(t, t.TempDir())
}

// looseTempDir is a state directory whose removal is allowed to fail.
//
// t.TempDir insists on being able to delete what it made, and Windows will not unlink a
// file another handle still has open - which is exactly the state the access log is in
// for a moment after Caddy has stopped. On a node the log is meant to be held open for
// the life of the daemon, so that is the test's problem rather than the edge's.
func looseTempDir(t *testing.T) string {
	t.Helper()
	directory, err := os.MkdirTemp("", "wisper-edge-")
	if err != nil {
		t.Fatalf("making a state directory: %v", err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(directory) })
	return directory
}

func newHarnessIn(t *testing.T, stateDir string) *harness {
	t.Helper()

	h := &harness{
		t:        t,
		backends: newFakeBackends(),
		store:    newFakeStore(),
		stateDir: stateDir,
		clock:    time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC),
	}

	edge, err := New(Options{
		StateDir: h.stateDir,
		Backends: h.backends,
		Store:    h.store,
		// Discarded rather than sent to the test's own output: several of these tests
		// deliberately break things, and a warning per broken thing would bury the
		// failures that matter.
		Logger:    slog.New(slog.NewTextHandler(io.Discard, nil)),
		HTTPPort:  18080,
		HTTPSPort: 18443,
		Now:       func() time.Time { return h.clock },
	})
	if err != nil {
		t.Fatalf("building an edge: %v", err)
	}
	h.edge = edge
	return h
}

// sync loads a spec, failing the test if the edge refuses it.
func (h *harness) sync(routes []spec.Route, workloads []spec.Workload) {
	h.t.Helper()
	if err := h.edge.Sync(context.Background(), spec.Spec{Routes: routes, Workloads: workloads}); err != nil {
		h.t.Fatalf("syncing the route table: %v", err)
	}
}

// publishSite writes a release for a site workload and points `current` at it.
//
// A directory rather than a symlink, because Windows needs a privilege for one and these
// tests have to run on a developer's machine. What the edge opens is the path; whether it
// is a link is the builder's business and the kernel's.
func (h *harness) publishSite(workloadID string, files map[string]string) string {
	h.t.Helper()

	directory := filepath.Join(h.stateDir, sitesDirName, workloadID, publishedLinkName)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		h.t.Fatalf("creating the release directory: %v", err)
	}
	for name, body := range files {
		full := filepath.Join(directory, filepath.FromSlash(name))
		if err := os.MkdirAll(filepath.Dir(full), 0o700); err != nil {
			h.t.Fatalf("creating %s: %v", filepath.Dir(full), err)
		}
		if err := os.WriteFile(full, []byte(body), 0o600); err != nil {
			h.t.Fatalf("writing %s: %v", full, err)
		}
	}
	return directory
}

// appRoute and siteRoute are the two shapes every test needs.
func appRoute(domain, workloadID string, port uint32) spec.Route {
	return spec.Route{
		Domain:     domain,
		WorkloadID: workloadID,
		Port:       port,
		TLSMode:    spec.TLSOnDemand,
		ForceHTTPS: true,
	}
}

func siteRoute(domain, workloadID string) spec.Route {
	return spec.Route{
		Domain:     domain,
		WorkloadID: workloadID,
		TLSMode:    spec.TLSOnDemand,
		ForceHTTPS: true,
	}
}

func appWorkload(id string) spec.Workload {
	return spec.Workload{ID: id, Kind: spec.KindApp, Name: id, Desired: spec.DesiredRunning}
}

func siteWorkload(id string, options spec.SiteOptions) spec.Workload {
	return spec.Workload{
		ID:      id,
		Kind:    spec.KindSite,
		Name:    id,
		Desired: spec.DesiredRunning,
		Site:    options,
	}
}
