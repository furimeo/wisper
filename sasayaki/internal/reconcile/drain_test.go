package reconcile

import (
	"context"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// drainableSpec has one workload holding a volume and one that does not, which is the whole
// distinction a drain turns on.
func drainableSpec(generation uint64) *wisperpb.NodeSpec {
	message := appSpec(generation)
	message.Workloads = append(message.Workloads, &wisperpb.Workload{
		Id:           "wl-worker",
		Kind:         wisperpb.WorkloadKind_WORKLOAD_KIND_APP,
		Name:         "worker",
		Image:        "docker.io/library/busybox:1",
		DesiredState: wisperpb.DesiredState_DESIRED_STATE_RUNNING,
	})
	return message
}

func TestASurveyChangesNothingAndNamesWhatHoldsData(t *testing.T) {
	// An administrator has to be able to find out what a drain would cost before committing
	// to it.
	h := newHarness(t)
	h.publish(t, drainableSpec(7))
	h.pass(t)

	report, err := h.loop.Drain(context.Background(), &wisperpb.DrainNode{Reason: "planned maintenance"})
	if err != nil {
		t.Fatalf("survey the drain: %v", err)
	}

	if len(h.runtime.stopped) != 0 {
		t.Fatalf("a survey stopped %v", h.runtime.stopped)
	}
	if got := report.GetPinnedWorkloads(); len(got) != 1 || got[0] != "wl-api" {
		t.Fatalf("pinned %v, want the workload holding a volume", got)
	}
	if got := report.GetEvacuatedWorkloads(); len(got) != 2 {
		t.Fatalf("evacuable %v, want the site and the worker", got)
	}
	if report.GetComplete() {
		t.Fatal("a survey with evacuable workloads reported the drain as complete")
	}
}

func TestDrainingStopsTheStatelessAndLeavesTheStateful(t *testing.T) {
	h := newHarness(t)
	h.publish(t, drainableSpec(7))
	h.pass(t)

	report, err := h.loop.Drain(context.Background(),
		&wisperpb.DrainNode{EvacuateStateless: true, Reason: "decommissioning"})
	if err != nil {
		t.Fatalf("drain: %v", err)
	}
	if !report.GetComplete() {
		t.Fatal("the drain did not report itself complete")
	}

	if len(h.runtime.stopped) != 1 {
		t.Fatalf("stopped %v, want only the worker", h.runtime.stopped)
	}
	for _, container := range h.runtime.containers {
		if container.WorkloadID == "wl-api" && !container.Running {
			t.Fatal("the workload holding a volume was stopped; moving it means moving data")
		}
	}
	if len(h.runtime.removals()) != 0 {
		t.Fatalf("a drain removed %v; it evacuates, it does not delete", h.runtime.removals())
	}
}

func TestAPassAfterADrainDoesNotRestartWhatWasEvacuated(t *testing.T) {
	// The panel will publish the same decision in its next spec. This is about the fifteen
	// seconds before that arrives.
	h := newHarness(t)
	h.publish(t, drainableSpec(7))
	h.pass(t)

	if _, err := h.loop.Drain(context.Background(), &wisperpb.DrainNode{EvacuateStateless: true}); err != nil {
		t.Fatalf("drain: %v", err)
	}
	started := len(h.runtime.started)

	h.pass(t)

	if len(h.runtime.started) != started {
		t.Fatalf("a pass after the drain started %d more containers",
			len(h.runtime.started)-started)
	}
	batch := h.panel.latest(t)
	if batch.GetHealth() != wisperpb.NodeHealth_NODE_HEALTH_DRAINING {
		t.Fatalf("a drained node reported health %s, want DRAINING", batch.GetHealth())
	}
}

func TestADrainThatCannotSeeTheMachineRefuses(t *testing.T) {
	// A drain that reported an empty node would have an administrator remove it believing
	// nothing was left on it.
	h := newHarness(t)
	h.publish(t, drainableSpec(7))
	h.pass(t)
	h.runtime.listErr = errEngineDown

	if _, err := h.loop.Drain(context.Background(), &wisperpb.DrainNode{EvacuateStateless: true}); err == nil {
		t.Fatal("a drain succeeded without being able to list the containers")
	}
}

func TestDrainingANodeThatHasNeverBeenGivenASpecIsComplete(t *testing.T) {
	h := newHarness(t)

	report, err := h.loop.Drain(context.Background(), &wisperpb.DrainNode{EvacuateStateless: true})
	if err != nil {
		t.Fatalf("drain an empty node: %v", err)
	}
	if !report.GetComplete() {
		t.Fatal("a node holding nothing was not reported as drained")
	}
}
