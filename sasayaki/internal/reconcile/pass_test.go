package reconcile

import (
	"context"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

func TestAFreshSpecIsCreatedStartedPublishedAndReported(t *testing.T) {
	h := newHarness(t)
	h.publish(t, appSpec(7))

	h.pass(t)

	if got := h.runtime.created; len(got) != 1 || got[0] != "wl-api" {
		t.Fatalf("created %v, want exactly the app workload", got)
	}
	if len(h.runtime.started) != 1 {
		t.Fatalf("started %v, want the one container that was created", h.runtime.started)
	}
	if h.sites.published["wl-site"] != "dep-1042" {
		t.Fatalf("the site is serving %q, want dep-1042", h.sites.published["wl-site"])
	}
	if h.edge.syncCount() != 1 {
		t.Fatalf("the edge was loaded %d times, want once", h.edge.syncCount())
	}

	batch := h.panel.latest(t)
	if batch.GetAppliedGeneration() != 7 {
		t.Fatalf("reported applied generation %d, want 7", batch.GetAppliedGeneration())
	}
	if batch.GetPartial() {
		t.Fatal("a pass that saw the whole machine reported itself as partial")
	}
	if batch.GetHealth() != wisperpb.NodeHealth_NODE_HEALTH_HEALTHY {
		t.Fatalf("health = %s on a pass that converged everything", batch.GetHealth())
	}

	// STARTING and not RUNNING: this pass started the container and has not looked at it
	// since. Claiming it is up before anything observed it up is how a panel shows a
	// service as healthy while it is crashing on boot.
	if phase := h.statusOf(t, "wl-api").GetPhase(); phase != wisperpb.WorkloadPhase_WORKLOAD_PHASE_STARTING {
		t.Fatalf("the app is %s after being started, want STARTING", phase)
	}
	site := h.statusOf(t, "wl-site")
	if site.GetPhase() != wisperpb.WorkloadPhase_WORKLOAD_PHASE_RUNNING {
		t.Fatalf("the site is %s after publishing, want RUNNING", site.GetPhase())
	}
	if site.GetReleaseId() != "dep-1042" {
		t.Fatalf("the site reported release %q", site.GetReleaseId())
	}
}

func TestASecondPassOverAnUnchangedSpecChangesNothing(t *testing.T) {
	// The fifteen-second tick runs whether or not anything happened, so the expensive
	// no-op is the common case: it must not rebuild the edge, recreate a container or
	// republish a release.
	h := newHarness(t)
	h.publish(t, appSpec(7))
	h.pass(t)
	h.pass(t)

	if len(h.runtime.created) != 1 {
		t.Fatalf("created %v across two passes over one spec", h.runtime.created)
	}
	if len(h.runtime.removals()) != 0 {
		t.Fatalf("removed %v on a pass with nothing to do", h.runtime.removals())
	}
	if h.edge.syncCount() != 1 {
		t.Fatalf("the edge was rebuilt %d times for one unchanged route table", h.edge.syncCount())
	}
	if phase := h.statusOf(t, "wl-api").GetPhase(); phase != wisperpb.WorkloadPhase_WORKLOAD_PHASE_RUNNING {
		t.Fatalf("the app is %s on the pass after it started, want RUNNING", phase)
	}
}

func TestDriftInTheEnvironmentRebuildsTheContainer(t *testing.T) {
	h := newHarness(t)
	h.publish(t, appSpec(7))
	h.pass(t)
	first := h.runtime.containers["ctr-wl-api-1"]

	changed := appSpec(8)
	workloadOf(changed, "wl-api").Env = []*wisperpb.EnvVar{
		{Name: "NODE_ENV", Value: "production"},
		{Name: "FEATURE_FLAG", Value: "on"},
	}
	h.publish(t, changed)
	h.pass(t)

	if removed := h.runtime.removals(); len(removed) != 1 || removed[0] != first.ID {
		t.Fatalf("removed %v, want the container built from the old spec", removed)
	}
	if len(h.runtime.created) != 2 {
		t.Fatalf("created %v, want the app rebuilt from the new spec", h.runtime.created)
	}
	if h.runtime.count() != 1 {
		t.Fatalf("%d containers exist, want one", h.runtime.count())
	}
}

func TestDriftInTheImageRebuildsTheContainer(t *testing.T) {
	h := newHarness(t)
	h.publish(t, appSpec(7))
	h.pass(t)

	changed := appSpec(8)
	workloadOf(changed, "wl-api").Image = "docker.io/library/node:24"
	h.publish(t, changed)
	h.pass(t)

	if len(h.runtime.removals()) != 1 || len(h.runtime.created) != 2 {
		t.Fatalf("a new image left %d removals and %d creations",
			len(h.runtime.removals()), len(h.runtime.created))
	}
}

func TestDriftInTheLimitsRebuildsTheContainer(t *testing.T) {
	// The one the predecessor got wrong in the other direction: a limit that changed in the
	// panel and never reached the container, while the panel showed it as applied.
	h := newHarness(t)
	h.publish(t, appSpec(7))
	h.pass(t)

	changed := appSpec(8)
	workloadOf(changed, "wl-api").Limits.MemoryBytes = 1 << 30
	h.publish(t, changed)
	h.pass(t)

	if len(h.runtime.removals()) != 1 || len(h.runtime.created) != 2 {
		t.Fatalf("a new memory ceiling left %d removals and %d creations",
			len(h.runtime.removals()), len(h.runtime.created))
	}
}

func TestPausingAWorkloadStopsItAndKeepsTheContainer(t *testing.T) {
	// Stopping is not removing. A customer who pressed pause expects their logs, their
	// volumes and their container to still be there.
	h := newHarness(t)
	h.publish(t, appSpec(7))
	h.pass(t)

	paused := appSpec(8)
	workloadOf(paused, "wl-api").DesiredState = wisperpb.DesiredState_DESIRED_STATE_STOPPED
	h.publish(t, paused)
	h.pass(t)

	if len(h.runtime.removals()) != 0 {
		t.Fatalf("pausing removed %v", h.runtime.removals())
	}
	if len(h.runtime.stopped) != 1 {
		t.Fatalf("stopped %v, want the one container", h.runtime.stopped)
	}
	if phase := h.statusOf(t, "wl-api").GetPhase(); phase != wisperpb.WorkloadPhase_WORKLOAD_PHASE_STOPPED {
		t.Fatalf("a paused workload is %s, want STOPPED", phase)
	}
}

func TestAWorkloadLeftOutOfTheSpecIsRemoved(t *testing.T) {
	// Omission is deletion: the garbage-collection half of reconciliation.
	h := newHarness(t)
	h.publish(t, appSpec(7))
	h.pass(t)

	shrunk := appSpec(8)
	shrunk.Workloads = []*wisperpb.Workload{workloadOf(shrunk, "wl-api")}
	shrunk.Routes = nil
	h.publish(t, shrunk)
	h.pass(t)

	if len(h.sites.discarded) != 1 || h.sites.discarded[0] != "wl-site" {
		t.Fatalf("discarded %v, want the release tree of the site that left the spec", h.sites.discarded)
	}

	statuses, err := h.store.WorkloadStatuses(context.Background())
	if err != nil {
		t.Fatalf("read the stored statuses: %v", err)
	}
	if len(statuses) != 1 || statuses[0].Status.GetWorkloadId() != "wl-api" {
		t.Fatalf("%d status rows survived the removal, want only the app's", len(statuses))
	}
}

func TestAnOrphanedContainerIsRemovedAndAnUnlabelledOneIsNot(t *testing.T) {
	h := newHarness(t)
	h.runtime.put(Container{
		ID:         "ctr-orphan",
		WorkloadID: "wl-deleted",
		Name:       "old-api",
		Running:    true,
	})
	// Managed by the daemon but not by this loop: a shared database engine. Removing it
	// would take out every customer's database on the node.
	h.runtime.put(Container{ID: "ctr-engine", Name: "wisper-postgres", Running: true})

	h.publish(t, appSpec(7))
	h.pass(t)

	removed := h.runtime.removals()
	if len(removed) != 1 || removed[0] != "ctr-orphan" {
		t.Fatalf("removed %v, want only the container whose workload left the spec", removed)
	}
	if _, alive := h.runtime.containers["ctr-engine"]; !alive {
		t.Fatal("a managed container with no workload label was removed; it belongs to another package")
	}
}

func TestAnImageStillPullingIsReportedRatherThanWaitedFor(t *testing.T) {
	// A pass that blocked on a cold pull would miss the watchdog's window and get the
	// daemon restarted in the middle of a deployment.
	h := newHarness(t)
	h.runtime.pulling["docker.io/library/node:24-alpine"] = ImageState{Progress: "pulling 42%"}
	h.publish(t, appSpec(7))

	h.pass(t)

	if len(h.runtime.created) != 0 {
		t.Fatalf("created %v before the image was on the node", h.runtime.created)
	}
	status := h.statusOf(t, "wl-api")
	if status.GetPhase() != wisperpb.WorkloadPhase_WORKLOAD_PHASE_PULLING {
		t.Fatalf("a workload waiting for its image is %s, want PULLING", status.GetPhase())
	}
	if status.GetMessage() != "pulling 42%" {
		t.Fatalf("the customer is told %q while the image comes down", status.GetMessage())
	}
	// Still a converged pass: nothing failed, and the generation has been processed.
	if h.panel.latest(t).GetHealth() != wisperpb.NodeHealth_NODE_HEALTH_HEALTHY {
		t.Fatal("a pull in progress was reported as a degraded node")
	}
}

func TestAGenerationBelowTheAppliedOneIsIgnored(t *testing.T) {
	// Reachable only when the state database was replaced or restored underneath a running
	// daemon, because SaveSpec refuses to store a spec older than the one on disk.
	// Converging backwards would undo work the panel believes is done.
	h := newHarness(t)
	h.publish(t, appSpec(9))
	if err := h.store.MarkApplied(context.Background(), 12, noon); err != nil {
		t.Fatalf("record a later generation as applied: %v", err)
	}

	h.pass(t)

	if len(h.runtime.created) != 0 || len(h.runtime.removals()) != 0 {
		t.Fatalf("an older spec was acted on: created %v, removed %v",
			h.runtime.created, h.runtime.removals())
	}
	if len(h.panel.batches) != 0 {
		t.Fatalf("an ignored spec produced %d status batches", len(h.panel.batches))
	}
}

func TestANodeWithNoSpecRemovesNothing(t *testing.T) {
	// A freshly enrolled node. "Nobody has told me yet" is not "run nothing".
	h := newHarness(t)
	h.runtime.put(Container{ID: "ctr-existing", WorkloadID: "wl-api", Running: true})

	h.pass(t)

	if len(h.runtime.removals()) != 0 {
		t.Fatalf("a node that has never been given a spec removed %v", h.runtime.removals())
	}
}
