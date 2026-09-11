package reconcile

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The rule this whole file exists for:
//
//	"Cannot see it" is not "does not exist".
//
// A Docker daemon that is restarting, out of file descriptors or being upgraded answers
// nothing for a few seconds. Every container on the machine is still running. A reconciler
// that reads that silence as an empty machine removes every one of them, and a customer's
// data goes with them (AGENTS.md section 4.5).

func TestAnUnreachableEngineDeletesNothing(t *testing.T) {
	h := newHarness(t)
	h.publish(t, appSpec(7))
	h.pass(t)

	before := h.runtime.count()
	h.runtime.listErr = errEngineDown

	err := h.failingPass(t)
	if !strings.Contains(err.Error(), "list the containers") {
		t.Fatalf("the pass failed with %q, which does not say the engine could not be asked", err)
	}
	if removed := h.runtime.removals(); len(removed) != 0 {
		t.Fatalf("an unreachable engine caused %v to be removed", removed)
	}
	if h.runtime.count() != before {
		t.Fatalf("%d containers survived an engine outage, want %d", h.runtime.count(), before)
	}
	if len(h.sites.discarded) != 0 {
		t.Fatalf("an unreachable engine discarded the release trees %v", h.sites.discarded)
	}
}

func TestAnUnreachableEngineReportsLastKnownStatusesAsPartial(t *testing.T) {
	h := newHarness(t)
	h.publish(t, appSpec(7))
	h.pass(t)
	h.pass(t) // so the app is observed RUNNING rather than STARTING

	h.runtime.listErr = errEngineDown
	h.failingPass(t)

	batch := h.panel.latest(t)
	if !batch.GetPartial() {
		t.Fatal("a batch built without asking the engine was not marked partial; " +
			"the panel would conclude that anything missing had gone away")
	}
	if batch.GetHealth() != wisperpb.NodeHealth_NODE_HEALTH_DEGRADED {
		t.Fatalf("health = %s during an engine outage, want DEGRADED", batch.GetHealth())
	}
	if !strings.Contains(batch.GetHealthDetail(), "docker unreachable since") {
		t.Fatalf("health detail %q does not tell an operator since when", batch.GetHealthDetail())
	}
	if len(batch.GetWorkloads()) != 2 {
		t.Fatalf("%d workloads were reported during the outage, want the two the node knows about",
			len(batch.GetWorkloads()))
	}
	if phase := h.statusOf(t, "wl-api").GetPhase(); phase != wisperpb.WorkloadPhase_WORKLOAD_PHASE_RUNNING {
		t.Fatalf("the app was reported %s during the outage; last known is more truthful than a guess", phase)
	}
}

func TestAnUnreachableEngineLeavesTheStoredStatusesAlone(t *testing.T) {
	// Overwriting real history with "unknown" would lose exactly the fact - how long this
	// has been failing - that an outage makes valuable.
	h := newHarness(t)
	h.publish(t, appSpec(7))
	h.pass(t)
	h.pass(t)

	h.runtime.listErr = errEngineDown
	h.failingPass(t)

	stored, err := h.store.WorkloadStatuses(context.Background())
	if err != nil {
		t.Fatalf("read the stored statuses: %v", err)
	}
	if len(stored) != 2 {
		t.Fatalf("%d status rows survived the outage, want 2", len(stored))
	}
	for _, observation := range stored {
		if observation.Status.GetPhase() == wisperpb.WorkloadPhase_WORKLOAD_PHASE_UNKNOWN {
			t.Fatalf("the stored status of %s was overwritten with UNKNOWN",
				observation.Status.GetWorkloadId())
		}
	}
}

func TestTheEngineGoingAwayAndComingBackIsReportedOnce(t *testing.T) {
	// An event repeated every fifteen seconds is an event nobody reads.
	h := newHarness(t)
	h.publish(t, appSpec(7))
	h.pass(t)

	h.runtime.listErr = errEngineDown
	h.failingPass(t)
	h.failingPass(t)

	if got := count(h.panel.kinds(), wisperpb.NodeEventKind_NODE_EVENT_KIND_DOCKER_UNREACHABLE); got != 1 {
		t.Fatalf("the outage was reported %d times across two failing passes", got)
	}

	h.runtime.listErr = nil
	h.pass(t)

	if got := count(h.panel.kinds(), wisperpb.NodeEventKind_NODE_EVENT_KIND_DOCKER_RECOVERED); got != 1 {
		t.Fatalf("the engine coming back was reported %d times", got)
	}
}

func TestAnOutageBeforeAnythingWasObservedReportsUnknown(t *testing.T) {
	// UNKNOWN is the per-workload form of partial: the node says "I cannot see it" rather
	// than saying nothing, which the panel would read as the workload having gone.
	h := newHarness(t)
	h.publish(t, appSpec(7))
	h.runtime.listErr = errEngineDown

	h.failingPass(t)

	for _, id := range []string{"wl-api", "wl-site"} {
		if phase := h.statusOf(t, id).GetPhase(); phase != wisperpb.WorkloadPhase_WORKLOAD_PHASE_UNKNOWN {
			t.Fatalf("%s was reported %s by a node that has never seen it, want UNKNOWN", id, phase)
		}
	}
	if len(h.runtime.removals()) != 0 {
		t.Fatalf("removed %v", h.runtime.removals())
	}
}

func TestADamagedSpecStopsThePassWithoutTouchingAnything(t *testing.T) {
	// LoadSpec verifies the stored checksum, so a flipped bit is refused rather than
	// reconciled. With no desired state there is nothing that can honestly be called an
	// orphan.
	h := newHarness(t)
	h.publish(t, appSpec(7))
	h.pass(t)

	h.loadSpecErr = errors.New(
		"state: the stored spec at generation 7 does not match its checksum: it is damaged and must be resent")

	err := h.failingPass(t)
	if !strings.Contains(err.Error(), "could not be read") {
		t.Fatalf("the pass failed with %q", err)
	}
	if len(h.runtime.removals()) != 0 {
		t.Fatalf("a damaged spec caused %v to be removed", h.runtime.removals())
	}
	batch := h.panel.latest(t)
	if !batch.GetPartial() {
		t.Fatal("a pass with no desired state reported a complete view of the node")
	}
	if len(batch.GetWorkloads()) != 2 {
		t.Fatalf("%d workloads were reported, want everything the node last saw", len(batch.GetWorkloads()))
	}
}

func count[T comparable](values []T, wanted T) int {
	found := 0
	for _, value := range values {
		if value == wanted {
			found++
		}
	}
	return found
}
