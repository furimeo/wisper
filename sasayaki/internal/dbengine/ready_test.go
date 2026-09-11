package dbengine

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Readiness: that a server still initialising is waited for rather than slept through, and
// that a server that is never coming up is given up on with a reason.

func TestProvisionWaitsForAServerThatIsStillInitialising(t *testing.T) {
	h := newHarness(t)
	h.publish([]*wisperpb.DatabaseEngineSpec{postgresEngine()}, nil)
	h.converge(t)

	// initdb is still running: the container is up and the server refuses connections.
	server := h.Servers.only()
	server.setReady(false)

	// It finishes shortly. Nothing in the package knows how long that will be, which is the
	// whole point - a fixed sleep is wrong in both directions.
	go func() {
		time.Sleep(300 * time.Millisecond)
		server.setReady(true)
	}()

	started := time.Now()
	if _, err := h.Engines.ProvisionDatabase(context.Background(), &wisperpb.ProvisionDatabase{
		Grant:    grant("db-1", "proj_api", "proj_api_user", wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES),
		Password: "password",
	}); err != nil {
		t.Fatalf("provision against a server that was still starting: %v", err)
	}
	took := time.Since(started)

	if took < 250*time.Millisecond {
		t.Fatalf("the provision finished in %s, before the server was accepting connections", took)
	}
	if took > 30*time.Second {
		t.Fatalf("the provision took %s, which is a sleep rather than a poll", took)
	}
	if _, present := h.Servers.only().Databases["proj_api"]; !present {
		t.Fatal("the database was not created once the server came up")
	}
}

func TestWaitingGivesUpWithTheReasonTheClientGave(t *testing.T) {
	h := newHarness(t)
	h.publish([]*wisperpb.DatabaseEngineSpec{postgresEngine()}, nil)
	h.converge(t)
	h.Servers.only().setReady(false)

	built, err := instanceFor(h.StateDir, postgresSpec())
	if err != nil {
		t.Fatalf("resolve the instance: %v", err)
	}
	made, present := h.Docker.containerNamed(built.Name)
	if !present {
		t.Fatal("the server container was not created")
	}

	failure := h.Engines.waitReady(context.Background(), built, made.ID, 300*time.Millisecond)
	if failure == nil {
		t.Fatal("waiting for a server that never came up reported success")
	}
	if !strings.Contains(failure.Error(), "Connection refused") {
		t.Fatalf("the failure does not carry what the client said, so a wrong password looks the "+
			"same as a slow start: %v", failure)
	}
}

func TestWaitingStopsWhenTheContextEnds(t *testing.T) {
	h := newHarness(t)
	h.publish([]*wisperpb.DatabaseEngineSpec{postgresEngine()}, nil)
	h.converge(t)
	h.Servers.only().setReady(false)

	built, err := instanceFor(h.StateDir, postgresSpec())
	if err != nil {
		t.Fatalf("resolve the instance: %v", err)
	}
	made, _ := h.Docker.containerNamed(built.Name)

	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()

	started := time.Now()
	if err := h.Engines.waitReady(ctx, built, made.ID, time.Minute); err == nil {
		t.Fatal("a cancelled wait reported success")
	}
	if took := time.Since(started); took > 5*time.Second {
		t.Fatalf("a cancelled wait took %s to notice", took)
	}
}

func TestABackoffNeverGivesUpAndNeverRunsAway(t *testing.T) {
	// The loop backs off after a failure and stops at the ceiling. It never stops retrying:
	// the panel may be about to publish a spec that fixes whatever is wrong.
	interval := 15 * time.Second
	if got := backoff(interval, 0); got != interval {
		t.Fatalf("a successful pass changed the interval to %s", got)
	}
	if got := backoff(interval, 1); got != 30*time.Second {
		t.Fatalf("one failure backed off to %s", got)
	}
	if got := backoff(interval, 40); got != backoffCeiling {
		t.Fatalf("forty failures backed off to %s rather than to the ceiling", got)
	}
	if got := backoff(interval, 40); got <= 0 {
		t.Fatal("the backoff reached zero, which is a busy loop")
	}
}
