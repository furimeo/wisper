package dbengine

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

func TestConvergeCreatesTheServersTheSpecAsksFor(t *testing.T) {
	h := newHarness(t)
	h.publish([]*wisperpb.DatabaseEngineSpec{postgresEngine(), mysqlEngine()}, nil)

	h.converge(t)

	if got := len(h.Docker.Created); got != 2 {
		t.Fatalf("expected two servers to be created, got %d", got)
	}
	if got := h.Docker.running(); got != 2 {
		t.Fatalf("expected two servers to be running, got %d", got)
	}
	if len(h.Docker.Pulled) != 2 {
		t.Fatalf("expected both images to be pulled, got %v", h.Docker.Pulled)
	}

	made, present := h.Docker.containerNamed("wisper-db-postgres-" + postgresInstance)
	if !present {
		t.Fatal("the PostgreSQL server was not created under the name a connection string uses")
	}
	if made.Labels[reconcile.LabelWorkload] != "" {
		t.Fatal("a database server carries wisper.workload, so the reconcile loop would remove " +
			"it as an orphan on its first pass")
	}
	if made.Labels[reconcile.LabelManaged] != reconcile.LabelManagedValue {
		t.Fatal("a database server is not marked as managed by wisper")
	}
	if made.Labels[labelInstance] != postgresInstance {
		t.Fatalf("the instance label is %q", made.Labels[labelInstance])
	}
}

func TestConvergeIsIdempotent(t *testing.T) {
	h := newHarness(t)
	h.publish([]*wisperpb.DatabaseEngineSpec{postgresEngine()}, nil)

	h.converge(t)
	h.converge(t)
	h.converge(t)

	if got := len(h.Docker.Created); got != 1 {
		t.Fatalf("three passes created %d containers; convergence is not idempotent", got)
	}
	if got := len(h.Docker.Removed); got != 0 {
		t.Fatalf("a pass with nothing to do removed %d containers", got)
	}
}

func TestConvergeReplacesAServerWhoseImageMoved(t *testing.T) {
	h := newHarness(t)
	h.publish([]*wisperpb.DatabaseEngineSpec{postgresEngine()}, nil)
	h.converge(t)

	upgraded := postgresEngine()
	upgraded.Image = "postgres:17.4-alpine"
	h.publish([]*wisperpb.DatabaseEngineSpec{upgraded}, nil)
	h.converge(t)

	if got := len(h.Docker.Created); got != 2 {
		t.Fatalf("expected the server to be recreated on the new image, %d containers were created", got)
	}
	if got := len(h.Docker.Removed); got != 1 {
		t.Fatalf("expected the old container to be removed, %d were", got)
	}
	made, _ := h.Docker.containerNamed("wisper-db-postgres-" + postgresInstance)
	if made.Config.Image != "postgres:17.4-alpine" {
		t.Fatalf("the replacement runs %s", made.Config.Image)
	}
}

func TestConvergeStartsAServerThatIsNotRunning(t *testing.T) {
	h := newHarness(t)
	h.publish([]*wisperpb.DatabaseEngineSpec{postgresEngine()}, nil)
	h.converge(t)

	made, _ := h.Docker.containerNamed("wisper-db-postgres-" + postgresInstance)
	if _, err := h.Docker.ContainerStop(context.Background(), made.ID, client.ContainerStopOptions{}); err != nil {
		t.Fatalf("stop the server: %v", err)
	}

	h.converge(t)

	if h.Docker.running() != 1 {
		t.Fatal("a database server that had stopped was not started again")
	}
	if len(h.Docker.Created) != 1 {
		t.Fatal("a stopped server was recreated rather than started, which would have taken its logs")
	}
}

func TestConvergeRemovesAServerThatLeftTheSpecAndKeepsItsData(t *testing.T) {
	h := newHarness(t)
	h.publish([]*wisperpb.DatabaseEngineSpec{postgresEngine()}, nil)
	h.converge(t)

	paths, err := pathsFor(h.StateDir, postgresInstance)
	if err != nil {
		t.Fatalf("resolve the data directory: %v", err)
	}
	marker := filepath.Join(paths.Data, "customer-data")
	if err := os.WriteFile(marker, []byte("rows"), 0o600); err != nil {
		t.Fatalf("seed the data directory: %v", err)
	}

	h.publish(nil, nil)
	h.converge(t)

	if got := len(h.Docker.Removed); got != 1 {
		t.Fatalf("expected the container to be removed, %d were", got)
	}
	if _, err := os.Stat(marker); err != nil {
		t.Fatalf("the data directory was removed with the container, which loses every customer "+
			"database on it: %v", err)
	}
}

func TestConvergeDeletesNothingWhenDockerCannotBeAsked(t *testing.T) {
	h := newHarness(t)
	h.publish([]*wisperpb.DatabaseEngineSpec{postgresEngine()}, nil)
	h.converge(t)

	h.Docker.FailList = errEngineUnreachable
	if err := h.Engines.Converge(context.Background()); err == nil {
		t.Fatal("a pass that could not list the containers reported success")
	}

	if got := len(h.Docker.Removed); got != 0 {
		t.Fatalf("a pass that could not see the machine removed %d containers; "+
			"'cannot see it' is not 'does not exist'", got)
	}
}

func TestConvergeDoesNothingBeforeTheFirstSpecArrives(t *testing.T) {
	h := newHarness(t)
	h.Store.Err = state.ErrNoSpec

	if err := h.Engines.Converge(context.Background()); err != nil {
		t.Fatalf("a node that has never been given a spec reported a failure: %v", err)
	}
	if len(h.Docker.Created) != 0 || len(h.Docker.Removed) != 0 {
		t.Fatal("a node that has never been given a spec touched the engine")
	}
}

func TestConvergeCarriesOnWhenOneServerCannotBeResolved(t *testing.T) {
	h := newHarness(t)
	broken := mysqlEngine()
	broken.Image = ""
	h.publish([]*wisperpb.DatabaseEngineSpec{postgresEngine(), broken}, nil)

	err := h.Engines.Converge(context.Background())
	if err == nil {
		t.Fatal("a spec naming a server with no image reported success")
	}
	if !strings.Contains(err.Error(), "no image") {
		t.Fatalf("the failure does not say what was wrong: %v", err)
	}
	if got := len(h.Docker.Created); got != 1 {
		t.Fatalf("expected the other server to be created anyway, %d were", got)
	}
}

func TestConvergeJoinsTheServerToEveryTenantNetwork(t *testing.T) {
	h := newHarness(t)
	h.Docker.Networks["wisper-tenant-7"] = true

	h.Store.Spec = &wisperpb.NodeSpec{
		Generation: 2,
		Engines:    []*wisperpb.DatabaseEngineSpec{postgresEngine()},
		Workloads: []*wisperpb.Workload{
			{Id: "41", TenantNetwork: "wisper-tenant-7"},
			// A tenant whose first workload has not been created yet, so its bridge does not
			// exist. That is not a failure and the next pass picks it up.
			{Id: "42", TenantNetwork: "wisper-tenant-9"},
		},
	}

	h.converge(t)

	made, _ := h.Docker.containerNamed("wisper-db-postgres-" + postgresInstance)
	if !made.Networks["wisper-tenant-7"] {
		t.Fatal("the server did not join the tenant network whose workloads have to reach it")
	}
	if made.Host.NetworkMode != "none" {
		t.Fatalf("the server was created on the %q network rather than on none, so it can reach "+
			"the internet", made.Host.NetworkMode)
	}

	// A second pass must not join it again.
	before := len(h.Docker.Joined)
	h.converge(t)
	if len(h.Docker.Joined) != before {
		t.Fatal("a pass with nothing to do re-joined the server to a network it was already on")
	}
}
