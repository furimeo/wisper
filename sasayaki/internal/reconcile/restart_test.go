package reconcile

import (
	"context"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The failure this whole daemon was rewritten to avoid.
//
// Its predecessor kept what it was running in memory. A restart - an upgrade, a crash, a
// machine rebooting - lost all of it, and nothing ever put it back: the panel believed the
// node was fine and the node had no idea what it was supposed to be doing. Here the truth
// is in SQLite and on the containers' own labels, so a second daemon over the same file
// picks up exactly where the first one stopped, with the panel unreachable throughout.
//
// "The daemon restarted" is modelled as a new Loop over the same store with a fresh view of
// the same engine, because that is what it is: customers' containers are children of
// Docker, not of this process, and they do not go anywhere when it is replaced.

// restart is a second daemon over the same disk and the same engine.
func restart(t *testing.T, first *harness) *harness {
	t.Helper()
	second := buildHarness(t, first.store)
	second.runtime.adopt(first.runtime)
	second.sites.adopt(first.sites)
	second.clock.now = first.clock.Now()
	return second
}

func TestARestartedDaemonRebuildsNothingItAlreadyMatches(t *testing.T) {
	first := newHarness(t)
	first.publish(t, appSpec(7))
	first.pass(t)

	second := restart(t, first)
	second.pass(t)

	if len(second.runtime.created) != 0 {
		t.Fatalf("a restarted daemon created %v over containers that already matched the spec",
			second.runtime.created)
	}
	if removed := second.runtime.removals(); len(removed) != 0 {
		t.Fatalf("a restarted daemon removed %v; it did not recognise its own containers", removed)
	}
	if second.runtime.count() != 1 {
		t.Fatalf("%d containers survived the restart, want 1", second.runtime.count())
	}
	if batch := second.panel.latest(t); batch.GetAppliedGeneration() != 7 {
		t.Fatalf("the restarted daemon reported generation %d, want the 7 it read off its own disk",
			batch.GetAppliedGeneration())
	}
}

func TestARestartedDaemonConvergesASpecThatArrivedBeforeItDied(t *testing.T) {
	// The gap that matters: ApplySpec is acknowledged as soon as it is stored, and
	// convergence happens later on the loop's own time. A daemon killed in between must
	// pick the spec up off the disk, with no panel involved at all.
	first := newHarness(t)
	first.publish(t, appSpec(7))
	first.pass(t)

	changed := appSpec(8)
	workloadOf(changed, "wl-api").Image = "docker.io/library/node:24"
	workloadOf(changed, "wl-site").ReleaseId = "dep-1099"
	first.publish(t, changed)
	// ... and the daemon dies here, before a pass ever sees generation 8.

	second := restart(t, first)
	second.pass(t)

	if len(second.runtime.created) != 1 {
		t.Fatalf("created %v, want the app rebuilt from the spec that was waiting on disk",
			second.runtime.created)
	}
	if len(second.runtime.removals()) != 1 {
		t.Fatalf("removed %v, want the container built from the older spec", second.runtime.removals())
	}
	if second.sites.published["wl-site"] != "dep-1099" {
		t.Fatalf("the site is serving %q, want the release the waiting spec names",
			second.sites.published["wl-site"])
	}

	converged, err := second.store.Convergence(context.Background())
	if err != nil {
		t.Fatalf("read convergence: %v", err)
	}
	if converged.AppliedGeneration != 8 {
		t.Fatalf("applied generation = %d after the restart, want 8", converged.AppliedGeneration)
	}
}

func TestARestartedDaemonKeepsHowLongAWorkloadHasBeenInItsPhase(t *testing.T) {
	// "This container has been crash-looping for an hour" and "this container has just
	// started" are different sentences, and a daemon that forgot the difference on every
	// upgrade would only ever be able to say the second one.
	first := newHarness(t)
	first.publish(t, appSpec(7))
	first.pass(t)
	first.clock.advance(time.Hour)
	first.pass(t)

	settled := first.statusOf(t, "wl-api").GetLastTransitionAt().AsTime()
	if !settled.Equal(noon.Add(time.Hour)) {
		t.Fatalf("the app entered RUNNING at %s, want an hour after noon", settled)
	}

	second := restart(t, first)
	second.clock.advance(time.Hour)
	second.pass(t)

	status := second.statusOf(t, "wl-api")
	if status.GetPhase() != wisperpb.WorkloadPhase_WORKLOAD_PHASE_RUNNING {
		t.Fatalf("the app is %s after the restart, want RUNNING", status.GetPhase())
	}
	if moved := status.GetLastTransitionAt().AsTime(); !moved.Equal(settled) {
		t.Fatalf("the restart reset the phase's age to %s, want it left at %s", moved, settled)
	}
}

func TestARestartedDaemonStillRemovesWhatLeftTheSpecWhileItWasDown(t *testing.T) {
	first := newHarness(t)
	first.publish(t, appSpec(7))
	first.pass(t)

	shrunk := appSpec(8)
	shrunk.Workloads = []*wisperpb.Workload{workloadOf(shrunk, "wl-site")}
	shrunk.Routes = nil
	first.publish(t, shrunk)

	second := restart(t, first)
	second.pass(t)

	if removed := second.runtime.removals(); len(removed) != 1 {
		t.Fatalf("removed %v, want the container of the workload that left the spec", removed)
	}
	if second.runtime.count() != 0 {
		t.Fatalf("%d containers are left, want none", second.runtime.count())
	}
}
