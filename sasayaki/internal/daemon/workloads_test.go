package daemon

import (
	"context"
	"testing"

	cerrdefs "github.com/containerd/errdefs"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
)

// The adapter that turns "workload wl-api" into "container c1", and the two places where an
// error must not be one.
//
// Unpause is called from a deferred function on every path a backup can take, including the
// one where the daemon was killed during the pause and a new one is thawing what the old one
// left. Both ways of already being thawed have to be success, or a node that recovered
// correctly reports a failure every time it starts.

func TestFrozenWorkloadsRunning(t *testing.T) {
	cases := []struct {
		name      string
		workload  string
		container *reconcile.Container
		lookupErr error

		running bool
		fails   bool
	}{
		{
			name:      "a running container is running",
			workload:  "wl-api",
			container: &reconcile.Container{ID: "c1", Running: true},
			running:   true,
		},
		{
			name:      "a stopped container needs no pause and is not an error",
			workload:  "wl-api",
			container: &reconcile.Container{ID: "c1", Running: false},
			running:   false,
		},
		{
			name:     "a workload scaled to zero has no container, which is a fact",
			workload: "wl-api",
			running:  false,
		},
		{
			name:      "an engine that did not answer is a failure, not an empty machine",
			workload:  "wl-api",
			lookupErr: errEngineDown,
			fails:     true,
		},
	}

	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			workloads, containers, _ := freezerHarness(test.workload, test.container)
			containers.lookupErr = test.lookupErr

			running, err := workloads.Running(context.Background(), test.workload)
			if test.fails {
				if err == nil {
					t.Fatal("an engine that could not be asked was reported as an empty machine")
				}
				return
			}
			if err != nil {
				t.Fatalf("ask whether %s is running: %v", test.workload, err)
			}
			if running != test.running {
				t.Errorf("running = %t, want %t", running, test.running)
			}
		})
	}
}

func TestFrozenWorkloadsUnpause(t *testing.T) {
	cases := []struct {
		name       string
		container  *reconcile.Container
		unpauseErr error
		lookupErr  error

		attempted bool
		fails     bool
	}{
		{
			name:      "a paused container is thawed",
			container: &reconcile.Container{ID: "c1", Running: true},
			attempted: true,
		},
		{
			name:       "a container the engine says is not paused is already in the state asked for",
			container:  &reconcile.Container{ID: "c1", Running: true},
			unpauseErr: cerrdefs.ErrConflict,
			attempted:  true,
		},
		{
			name:       "a container that has gone cannot be frozen, so there is nothing to report",
			container:  &reconcile.Container{ID: "c1", Running: true},
			unpauseErr: cerrdefs.ErrNotFound,
			attempted:  true,
		},
		{
			name:      "a workload with no container at all is not a failure either",
			attempted: false,
		},
		{
			name:       "anything else is reported, because a workload left frozen needs an operator",
			container:  &reconcile.Container{ID: "c1", Running: true},
			unpauseErr: errEngineDown,
			attempted:  true,
			fails:      true,
		},
		{
			name:      "an engine that did not answer the lookup is reported",
			lookupErr: errEngineDown,
			fails:     true,
		},
	}

	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			workloads, containers, engine := freezerHarness("wl-api", test.container)
			containers.lookupErr = test.lookupErr
			engine.unpauseErr = test.unpauseErr

			err := workloads.Unpause(context.Background(), "wl-api")
			if test.fails != (err != nil) {
				t.Fatalf("Unpause returned %v, wanted a failure = %t", err, test.fails)
			}
			if got := len(engine.unpaused) == 1; got != test.attempted {
				t.Errorf("the engine was asked to unpause = %t, want %t", got, test.attempted)
			}
		})
	}
}

func TestFrozenWorkloadsStopAndStart(t *testing.T) {
	t.Run("a restore stops and starts the container behind the workload", func(t *testing.T) {
		workloads, containers, _ := freezerHarness("wl-api", &reconcile.Container{ID: "c1", Running: true})

		if err := workloads.Stop(context.Background(), "wl-api"); err != nil {
			t.Fatalf("stop the workload: %v", err)
		}
		if err := workloads.Start(context.Background(), "wl-api"); err != nil {
			t.Fatalf("start the workload: %v", err)
		}
		if len(containers.stopped) != 1 || containers.stopped[0] != "c1" {
			t.Errorf("stopped %v, want [c1]", containers.stopped)
		}
		if len(containers.started) != 1 || containers.started[0] != "c1" {
			t.Errorf("started %v, want [c1]", containers.started)
		}
	})

	t.Run("stopping a workload that has no container is the state asked for", func(t *testing.T) {
		workloads, containers, _ := freezerHarness("wl-api", nil)

		if err := workloads.Stop(context.Background(), "wl-api"); err != nil {
			t.Fatalf("stop a workload with no container: %v", err)
		}
		if len(containers.stopped) != 0 {
			t.Errorf("the engine was asked to stop %v, want nothing", containers.stopped)
		}
	})

	t.Run("a container that vanished before the restart is reported, not shrugged off", func(t *testing.T) {
		workloads, _, _ := freezerHarness("wl-api", nil)

		err := workloads.Start(context.Background(), "wl-api")
		if err == nil {
			t.Fatal("a workload that could not be started again was reported as started")
		}
	})
}

func TestFrozenWorkloadsPauseNeedsAContainer(t *testing.T) {
	workloads, _, engine := freezerHarness("wl-api", nil)

	if err := workloads.Pause(context.Background(), "wl-api"); err == nil {
		t.Fatal("a workload with no container was reported as paused")
	}
	if len(engine.paused) != 0 {
		t.Errorf("the engine was asked to pause %v, want nothing", engine.paused)
	}
}

func freezerHarness(workloadID string, container *reconcile.Container) (frozenWorkloads, *fakeContainers, *fakeFreezer) {
	containers := newFakeContainers()
	if container != nil {
		containers.byWorkload[workloadID] = *container
	}
	engine := &fakeFreezer{}
	return frozenWorkloads{containers: containers, engine: engine}, containers, engine
}
