package runtime

import (
	"context"
	"errors"
	"log/slog"
	"testing"
	"time"

	"github.com/moby/moby/api/types/system"
)

// gVisor can be installed on a running node: an operator edits daemon.json, restarts
// Docker, and reasonably expects the next container to be isolated without also having to
// restart sasayaki.
func TestTheEnginesCapabilitiesAreAskedForAgainAfterTheirTtl(t *testing.T) {
	api := newFake().withoutRunsc()
	now := time.Date(2026, 4, 1, 12, 0, 0, 0, time.UTC)

	docker, err := New(context.Background(), t.TempDir(), withEngine(api),
		WithCommandRunner(newHost().run), WithLogger(slog.New(slog.DiscardHandler)),
		WithClock(func() time.Time { return now }))
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer docker.Close()

	if got := docker.runtimeFor(context.Background(), "RUNSC"); got != "RUNC" {
		t.Fatalf("runtime = %q, want runc on a node with no gVisor", got)
	}

	api.info.Runtimes["runsc"] = system.RuntimeWithStatus{}

	// Still inside the cache window, so the engine is not asked again.
	if got := docker.runtimeFor(context.Background(), "RUNSC"); got != "RUNC" {
		t.Errorf("runtime = %q, want the cached answer inside the ttl", got)
	}

	now = now.Add(capabilityTTL + time.Second)
	if got := docker.runtimeFor(context.Background(), "RUNSC"); got != "RUNSC" {
		t.Errorf("runtime = %q, want gVisor once the engine has been asked again", got)
	}
}

// A Docker outage must not turn every container on the node into one the daemon believes
// should be running under runc: that is drift invented by an outage, and the next pass
// would rebuild every container on the machine.
func TestARefreshThatFailsKeepsTheLastAnswer(t *testing.T) {
	api := newFake()
	now := time.Date(2026, 4, 1, 12, 0, 0, 0, time.UTC)

	docker, err := New(context.Background(), t.TempDir(), withEngine(api),
		WithCommandRunner(newHost().run), WithLogger(slog.New(slog.DiscardHandler)),
		WithClock(func() time.Time { return now }))
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer docker.Close()

	api.infoErr = errors.New("connection refused")
	now = now.Add(capabilityTTL + time.Second)

	capability, err := docker.capabilities(context.Background())
	if err != nil {
		t.Fatalf("capabilities: %v", err)
	}
	if !capability.hasRunsc {
		t.Error("an unreachable engine was read as a node that lost gVisor")
	}
}

// The first answer is different: a daemon that cannot describe its engine at startup does
// not know what isolation it can provide, and starting anyway is how a node quietly ends
// up running customers with less containment than anybody was told.
func TestNewRefusesAnEngineItCannotAskAnything(t *testing.T) {
	api := newFake()
	api.infoErr = errors.New("connection refused")

	if _, err := New(context.Background(), t.TempDir(), withEngine(api),
		WithLogger(slog.New(slog.DiscardHandler))); err == nil {
		t.Fatal("started against an engine that would not describe itself")
	}
	if !api.closed {
		t.Error("the connection was leaked when construction failed")
	}
}

func TestNewRefusesAnEmptyStateDirectory(t *testing.T) {
	if _, err := New(context.Background(), "  ", withEngine(newFake())); err == nil {
		t.Fatal("started with nowhere to resolve a mount safely")
	}
}

func TestIsolationReportsWhatTheNodeReallyProvides(t *testing.T) {
	docker := newDocker(t, newFake(), newHost())

	isolation, err := docker.Isolation(context.Background())
	if err != nil {
		t.Fatalf("Isolation: %v", err)
	}
	if !isolation.Runsc || !isolation.Seccomp || !isolation.UserNamespaces {
		t.Errorf("isolation = %+v, want everything the fake engine advertises", isolation)
	}
	if isolation.CgroupVersion != "2" {
		t.Errorf("cgroups = %q, want 2: only v2 enforces the memory and pids ceilings the "+
			"way the spec means them", isolation.CgroupVersion)
	}
}

func TestASecurityOptionIsMatchedByNameAndNotBySubstring(t *testing.T) {
	options := []string{"name=seccomp,profile=builtin", "name=userns"}
	if !hasSecurityOption(options, "seccomp") || !hasSecurityOption(options, "userns") {
		t.Error("an option the engine advertises was not found")
	}
	if hasSecurityOption(options, "rootless") {
		t.Error("an option the engine does not advertise was found")
	}
	if hasSecurityOption([]string{"name=seccomp-something"}, "seccomp") {
		t.Error("a substring match reported a filter that is not there")
	}
}
