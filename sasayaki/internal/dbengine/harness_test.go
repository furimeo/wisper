package dbengine

import (
	"context"
	"io"
	"log/slog"
	"path/filepath"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The fakes every test in this package builds on.
//
// No Docker, no SQLite and no database. What is deliberately real is the filesystem: the
// transfer directory a dump passes through is a genuine directory under t.TempDir(), because
// the thing worth testing about dump and restore is that the bytes arrive, and a fake
// filesystem would test the fake.

// fakeStore is the node's disk, holding one spec.
type fakeStore struct {
	Spec *wisperpb.NodeSpec
	// Err is returned instead, for the freshly enrolled node that has never been given one.
	Err error
}

func (s *fakeStore) LoadSpec(context.Context) (state.StoredSpec, error) {
	if s.Err != nil {
		return state.StoredSpec{}, s.Err
	}
	return state.StoredSpec{Spec: s.Spec, Generation: s.Spec.GetGeneration()}, nil
}

type harness struct {
	Engines  *Engines
	Docker   *fakeDocker
	Servers  *fakeServers
	Store    *fakeStore
	StateDir string
	clock    time.Time
}

func newHarness(t *testing.T) *harness {
	t.Helper()

	root, err := filepath.Abs(t.TempDir())
	if err != nil {
		t.Fatalf("resolve the temporary state directory: %v", err)
	}

	h := &harness{
		Docker:   newFakeDocker(),
		Servers:  newFakeServers(root),
		Store:    &fakeStore{Spec: &wisperpb.NodeSpec{Generation: 1}},
		StateDir: root,
		clock:    time.Date(2026, 9, 11, 10, 0, 0, 0, time.UTC),
	}
	h.Docker.onCreate = h.Servers.register

	engines, err := New(Options{
		StateDir: root,
		Engine:   h.Docker,
		Commands: h.Servers,
		Store:    h.Store,
		Logger:   slog.New(slog.NewTextHandler(io.Discard, nil)),
		Now:      h.now,
		Interval: time.Second,
	})
	if err != nil {
		t.Fatalf("open the database layer: %v", err)
	}
	h.Engines = engines
	return h
}

// now advances a step on every read, so two measurements taken in one test are ordered without
// anything having to sleep.
func (h *harness) now() time.Time {
	h.clock = h.clock.Add(time.Second)
	return h.clock
}

// publish replaces the spec on disk.
func (h *harness) publish(engines []*wisperpb.DatabaseEngineSpec, grants []*wisperpb.DatabaseGrant) {
	h.Store.Spec = &wisperpb.NodeSpec{
		Generation: h.Store.Spec.GetGeneration() + 1,
		Engines:    engines,
		Databases:  grants,
	}
}

// converge runs one pass and fails the test if it did not fully succeed.
func (h *harness) converge(t *testing.T) {
	t.Helper()
	if err := h.Engines.Converge(context.Background()); err != nil {
		t.Fatalf("converge: %v", err)
	}
}

// ---------------------------------------------------------------------------
// Spec fragments
// ---------------------------------------------------------------------------

const (
	postgresInstance = "eng-postgres-1"
	mysqlInstance    = "eng-mysql-1"
	adminPassword    = "admin-secret"
)

func postgresEngine() *wisperpb.DatabaseEngineSpec {
	return &wisperpb.DatabaseEngineSpec{
		Engine:         wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES,
		Image:          "postgres:17.2-alpine",
		ListenPort:     5432,
		AdminUsername:  "wisper_admin",
		AdminPassword:  adminPassword,
		DataVolumeId:   postgresInstance,
		NanoCpus:       2_000_000_000,
		MemoryBytes:    2 << 30,
		MaxConnections: 200,
	}
}

func mysqlEngine() *wisperpb.DatabaseEngineSpec {
	return &wisperpb.DatabaseEngineSpec{
		Engine:         wisperpb.DatabaseEngine_DATABASE_ENGINE_MYSQL,
		Image:          "mysql:8.4.3",
		ListenPort:     3306,
		AdminUsername:  "root",
		AdminPassword:  adminPassword,
		DataVolumeId:   mysqlInstance,
		NanoCpus:       2_000_000_000,
		MemoryBytes:    2 << 30,
		MaxConnections: 200,
	}
}

func grant(id, database, user string, engine wisperpb.DatabaseEngine) *wisperpb.DatabaseGrant {
	return &wisperpb.DatabaseGrant{
		Id:           id,
		Engine:       engine,
		DatabaseName: database,
		Username:     user,
		QuotaBytes:   1 << 30,
	}
}

// statusFor picks one grant's status out of a batch.
func statusFor(t *testing.T, statuses []spec.DatabaseStatus, grantID string) spec.DatabaseStatus {
	t.Helper()
	for _, status := range statuses {
		if status.GrantID == grantID {
			return status
		}
	}
	t.Fatalf("no status was reported for the database %s; got %d statuses", grantID, len(statuses))
	return spec.DatabaseStatus{}
}
