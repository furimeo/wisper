package runtime

import (
	"context"
	"errors"
	"testing"
	"time"

	cerrdefs "github.com/containerd/errdefs"
)

// The reconcile loop is not a state machine that remembers what it did last pass: it
// looks at the machine and closes the gap. So it asks for things that have already
// happened, and answering those as failures would put a healthy workload into FAILED.
func TestStartingSomethingAlreadyRunningIsSuccess(t *testing.T) {
	api := newFake()
	api.onStart = func(string) error { return cerrdefs.ErrNotModified.WithMessage("already started") }
	docker := newDocker(t, api, newHost())

	if err := docker.Start(context.Background(), "c1"); err != nil {
		t.Fatalf("Start: %v", err)
	}
}

func TestStartReportsARealFailure(t *testing.T) {
	api := newFake()
	api.onStart = func(string) error { return errors.New("no such image") }
	docker := newDocker(t, api, newHost())

	if err := docker.Start(context.Background(), "c1"); err == nil {
		t.Fatal("a container that would not start was reported as started")
	}
}

func TestStopPassesTheGraceTheSpecAskedFor(t *testing.T) {
	api := newFake()
	docker := newDocker(t, api, newHost())

	if err := docker.Stop(context.Background(), "c1", 45*time.Second); err != nil {
		t.Fatalf("Stop: %v", err)
	}
	if len(api.stopped) != 1 || api.stopped[0].timeout != 45 {
		t.Errorf("stop = %+v, want 45 seconds before SIGKILL", api.stopped)
	}
}

func TestStopWithNoGraceUsesSomethingAnApplicationCanSurvive(t *testing.T) {
	api := newFake()
	docker := newDocker(t, api, newHost())

	if err := docker.Stop(context.Background(), "c1", 0); err != nil {
		t.Fatalf("Stop: %v", err)
	}
	if api.stopped[0].timeout != int(defaultStopGrace.Seconds()) {
		t.Errorf("timeout = %d, want %v: the engine's own ten seconds loses whatever a "+
			"database-backed application had in flight", api.stopped[0].timeout, defaultStopGrace)
	}
}

func TestStoppingSomethingThatIsNotThereIsSuccess(t *testing.T) {
	api := newFake()
	api.onStop = func(string) error { return cerrdefs.ErrNotFound.WithMessage("no such container") }
	docker := newDocker(t, api, newHost())

	if err := docker.Stop(context.Background(), "c1", time.Second); err != nil {
		t.Fatalf("Stop: %v", err)
	}
}

// A workload leaving the spec means the panel no longer wants it running here. It does
// not mean anybody agreed to lose a volume.
func TestRemoveNeverTakesTheDataWithIt(t *testing.T) {
	api := newFake()
	docker := newDocker(t, api, newHost())

	if err := docker.Remove(context.Background(), "c1"); err != nil {
		t.Fatalf("Remove: %v", err)
	}
	if len(api.removed) != 1 {
		t.Fatalf("removed %d containers, want 1", len(api.removed))
	}
	if api.removed[0].RemoveVolumes {
		t.Error("volumes were removed with the container: an orphaned directory can be " +
			"collected later, one that has been deleted cannot be brought back")
	}
	if !api.removed[0].Force {
		t.Error("a container the engine's own restart policy brought back between two passes " +
			"would be left half-removed forever")
	}
}

func TestRemovingSomethingAlreadyGoneIsSuccess(t *testing.T) {
	api := newFake()
	api.onRemove = func(string) error { return cerrdefs.ErrNotFound.WithMessage("no such container") }
	docker := newDocker(t, api, newHost())

	if err := docker.Remove(context.Background(), "c1"); err != nil {
		t.Fatalf("Remove: %v", err)
	}
}

func TestRemoveReportsAnEngineThatCouldNotBeAsked(t *testing.T) {
	api := newFake()
	api.onRemove = func(string) error { return errors.New("connection refused") }
	docker := newDocker(t, api, newHost())

	if err := docker.Remove(context.Background(), "c1"); err == nil {
		t.Fatal("a container that could not be removed was reported as removed, which would " +
			"have the panel release a placement that is still occupied")
	}
}

func TestReachableSaysWhetherTheEngineIsAnswering(t *testing.T) {
	docker := newDocker(t, newFake(), newHost())
	if err := docker.Reachable(context.Background()); err != nil {
		t.Fatalf("Reachable: %v", err)
	}
}
