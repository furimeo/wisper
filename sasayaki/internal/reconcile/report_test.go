package reconcile

import (
	"context"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// A status batch is one snapshot of the whole node at one moment, so the pieces other
// packages own travel with the containers rather than being reported separately.

func TestTheBatchCarriesTheEngineSizesAndTheCronHistory(t *testing.T) {
	h := newHarness(t)
	h.engines.statuses = []spec.DatabaseStatus{{
		GrantID:    "grant-9",
		Engine:     spec.EnginePostgres,
		Exists:     true,
		SizeBytes:  128 << 20,
		QuotaBytes: 1 << 30,
	}}
	ctx := context.Background()
	if err := h.store.StartCronRun(ctx, "cron-nightly", "wl-api", noon.Add(-time.Hour)); err != nil {
		t.Fatalf("record a cron run: %v", err)
	}
	if err := h.store.FinishCronRun(ctx, "cron-nightly", 0, noon.Add(-time.Hour+time.Minute), ""); err != nil {
		t.Fatalf("finish the cron run: %v", err)
	}

	h.publish(t, appSpec(7))
	h.pass(t)

	batch := h.panel.latest(t)
	if len(batch.GetDatabases()) != 1 || batch.GetDatabases()[0].GetId() != "grant-9" {
		t.Fatalf("the batch carried %d database statuses; the panel would never learn a customer's size",
			len(batch.GetDatabases()))
	}
	if len(batch.GetCron()) != 1 || batch.GetCron()[0].GetCronId() != "cron-nightly" {
		t.Fatalf("the batch carried %d cron statuses; last night's job would show as never run",
			len(batch.GetCron()))
	}
}

func TestAnEngineThatCannotBeMeasuredLeavesThePreviousFiguresAlone(t *testing.T) {
	// The panel writes only the rows it receives. Sending zeroes would show every customer
	// as using nothing.
	h := newHarness(t)
	h.engines.err = errEngineDown
	h.publish(t, appSpec(7))

	h.pass(t)

	batch := h.panel.latest(t)
	if len(batch.GetDatabases()) != 0 {
		t.Fatalf("a failed measurement produced %d database statuses", len(batch.GetDatabases()))
	}
	if batch.GetHealth() != wisperpb.NodeHealth_NODE_HEALTH_HEALTHY {
		t.Fatal("a database size that could not be read made the whole node degraded")
	}
}

func TestAPanelThatCannotBeReachedDoesNotFailThePass(t *testing.T) {
	// The tunnel drops routinely. A pass that converged the machine and could not say so
	// still converged the machine.
	h := newHarness(t)
	h.panel.err = errEngineDown
	h.publish(t, appSpec(7))

	h.pass(t)

	if len(h.runtime.created) != 1 {
		t.Fatalf("created %v while the panel was away; convergence does not need it",
			h.runtime.created)
	}
	converged, err := h.store.Convergence(context.Background())
	if err != nil {
		t.Fatalf("read convergence: %v", err)
	}
	if converged.AppliedGeneration != 7 {
		t.Fatalf("applied generation = %d with the panel unreachable, want 7", converged.AppliedGeneration)
	}
}

func TestAPanelAskingForAFreshStatusQueuesAnEarlyPass(t *testing.T) {
	h := newHarness(t)
	h.panel.ack = &wisperpb.Ack{RequestStatus: true}
	h.publish(t, appSpec(7))

	h.pass(t)

	if len(h.loop.nudge) != 1 {
		t.Fatal("the panel asked for a fresh status and no early pass was queued")
	}
}

func TestTheObservedMomentIsTheOneThePassBeganAt(t *testing.T) {
	// The panel compares observed_at against what it published. A timestamp taken at the
	// end of a pass would measure how long the pass took, not when the machine was seen.
	h := newHarness(t)
	h.publish(t, appSpec(7))
	h.pass(t)

	if observed := h.panel.latest(t).GetObservedAt().AsTime(); !observed.Equal(noon) {
		t.Fatalf("the batch was stamped %s, want the moment the pass began", observed)
	}
}

func TestAFailedActionKeepsTheAppliedGenerationWhereItWas(t *testing.T) {
	// applied_generation means "this node is where the panel asked it to be". A node that
	// could not start a container is not, and claiming otherwise stops the panel noticing.
	h := newHarness(t)
	h.publish(t, appSpec(7))
	h.runtime.createErr = errEngineDown

	h.failingPass(t)

	converged, err := h.store.Convergence(context.Background())
	if err != nil {
		t.Fatalf("read convergence: %v", err)
	}
	if converged.AppliedGeneration != 0 {
		t.Fatalf("applied generation = %d after a pass that could not create a container",
			converged.AppliedGeneration)
	}
	if converged.LastError == "" {
		t.Fatal("the failure was recorded with no explanation, so health_detail would be empty")
	}
	if h.panel.latest(t).GetHealth() != wisperpb.NodeHealth_NODE_HEALTH_DEGRADED {
		t.Fatal("a pass that failed to converge reported the node as healthy")
	}
}

func TestOneWorkloadFailingDoesNotStopTheOthersConverging(t *testing.T) {
	// A node where one image will not pull is still a node whose other workloads have to be
	// running.
	h := newHarness(t)
	h.runtime.createErr = errEngineDown
	h.publish(t, appSpec(7))

	h.failingPass(t)

	if h.sites.published["wl-site"] != "dep-1042" {
		t.Fatalf("the site was left unpublished because an unrelated container would not start")
	}
}
