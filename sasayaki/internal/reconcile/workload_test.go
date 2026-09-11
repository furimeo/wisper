package reconcile

import (
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The phases a customer is waiting on, derived from what the engine reports. Pure
// functions, so every branch is one call.

// settled is a workload the last pass did nothing to, which is the case where the phase has
// to be read off the container rather than off the action.
func settled(workload spec.Workload, container Container) applied {
	return applied{
		Decision: decision{
			Workload:     workload,
			Container:    container,
			HasContainer: container.ID != "",
			Want:         workload.Desired,
			Action:       actionNone,
		},
		Container:    container,
		HasContainer: container.ID != "",
	}
}

func TestARunningContainerWithAFailingCheckIsUnhealthyAndNotRestarted(t *testing.T) {
	// Reported, never acted on. A check failing during a slow migration is not a reason to
	// restart a database-backed app in a loop; the panel decides.
	workload := app("wl-api")
	container := Container{ID: "ctr-1", WorkloadID: "wl-api", Running: true, Health: HealthFailing}

	status := observe(settled(workload, container), spec.WorkloadStatus{}, noon)
	if status.Phase != spec.PhaseUnhealthy {
		t.Fatalf("phase = %s, want UNHEALTHY", status.Phase)
	}
}

func TestAContainerStartingUpIsNotReportedUnhealthy(t *testing.T) {
	// Anything that takes ten seconds to boot would otherwise be permanently broken.
	workload := app("wl-api")
	container := Container{ID: "ctr-1", WorkloadID: "wl-api", Running: true, Health: HealthStarting}

	if status := observe(settled(workload, container), spec.WorkloadStatus{}, noon); status.Phase != spec.PhaseRunning {
		t.Fatalf("phase = %s during the health check's grace period, want RUNNING", status.Phase)
	}
}

func TestAnExhaustedOnFailurePolicyIsReportedAsCrashLooping(t *testing.T) {
	// The customer is told, rather than left with a service that restarts forever and never
	// works.
	workload := app("wl-api")
	workload.Restart = spec.Restart{Mode: spec.RestartOnFailure, MaxRetries: 5}
	container := Container{ID: "ctr-1", WorkloadID: "wl-api", RestartCount: 5, ExitCode: 1}

	if status := observe(settled(workload, container), spec.WorkloadStatus{}, noon); status.Phase != spec.PhaseCrashLooping {
		t.Fatalf("phase = %s after the retries ran out, want CRASH_LOOPING", status.Phase)
	}
}

func TestAPolicyWithNoRetryLimitIsNotGuessedIntoCrashLooping(t *testing.T) {
	// The engine will keep restarting it and restart_count climbs in every batch. Inventing
	// a threshold here would label a container restarted five times over a year as broken.
	workload := app("wl-api")
	workload.Restart = spec.Restart{Mode: spec.RestartAlways}
	container := Container{ID: "ctr-1", WorkloadID: "wl-api", RestartCount: 40, ExitCode: 1}

	if status := observe(settled(workload, container), spec.WorkloadStatus{}, noon); status.Phase != spec.PhaseFailed {
		t.Fatalf("phase = %s, want FAILED for a workload that is down and wanted up", status.Phase)
	}
}

func TestAStoppedWorkloadThatExitedCleanlyIsStoppedAndNotFailed(t *testing.T) {
	workload := app("wl-api")
	workload.Desired = spec.DesiredStopped
	container := Container{ID: "ctr-1", WorkloadID: "wl-api", ExitCode: 0}

	if status := observe(settled(workload, container), spec.WorkloadStatus{}, noon); status.Phase != spec.PhaseStopped {
		t.Fatalf("phase = %s for a workload the customer paused, want STOPPED", status.Phase)
	}
}

func TestTheRuntimeInEffectIsReportedRatherThanTheOneAskedFor(t *testing.T) {
	// A node without runsc falls back to runc, and the panel has to say so out loud rather
	// than imply an isolation the workload does not have.
	workload := app("wl-api")
	workload.Runtime = spec.RuntimeRunsc
	container := Container{ID: "ctr-1", WorkloadID: "wl-api", Running: true, Runtime: spec.RuntimeRunc}

	if status := observe(settled(workload, container), spec.WorkloadStatus{}, noon); status.Runtime != spec.RuntimeRunc {
		t.Fatalf("reported runtime %s, want the one the container is really using", status.Runtime)
	}
}

func TestAWorkloadWithNoContainerReportsTheRuntimeTheSpecAsked(t *testing.T) {
	workload := app("wl-api")
	workload.Runtime = spec.RuntimeRunc
	workload.Desired = spec.DesiredStopped

	if status := observe(settled(workload, Container{}), spec.WorkloadStatus{}, noon); status.Runtime != spec.RuntimeRunc {
		t.Fatalf("reported runtime %s for a workload with no container", status.Runtime)
	}
}

func TestAPhaseThatDidNotChangeKeepsItsAge(t *testing.T) {
	// "This has been crash-looping for an hour" is a different sentence from "this has just
	// started", and the fifteen-second passes in between must not keep resetting it.
	workload := app("wl-api")
	container := Container{ID: "ctr-1", WorkloadID: "wl-api", Running: true}
	previous := spec.WorkloadStatus{
		WorkloadID:       "wl-api",
		Phase:            spec.PhaseRunning,
		LastTransitionAt: noon.Add(-time.Hour),
	}

	status := observe(settled(workload, container), previous, noon)
	if !status.LastTransitionAt.Equal(noon.Add(-time.Hour)) {
		t.Fatalf("the transition moved to %s on a pass that changed nothing", status.LastTransitionAt)
	}
}

func TestAPhaseThatChangedIsTimestampedNow(t *testing.T) {
	workload := app("wl-api")
	container := Container{ID: "ctr-1", WorkloadID: "wl-api", ExitCode: 1}
	previous := spec.WorkloadStatus{
		WorkloadID:       "wl-api",
		Phase:            spec.PhaseRunning,
		LastTransitionAt: noon.Add(-time.Hour),
	}

	status := observe(settled(workload, container), previous, noon)
	if !status.LastTransitionAt.Equal(noon) {
		t.Fatalf("a workload that fell over kept its old transition time %s", status.LastTransitionAt)
	}
}

func TestAContainerTheKernelKilledIsReportedAsAnEvent(t *testing.T) {
	// Invisible in the batch before and the batch after: the container was running, and now
	// it is running again because the engine restarted it.
	container := Container{ID: "ctr-1", WorkloadID: "wl-api", OOMKilled: true, ExitCode: 137}
	previous := spec.WorkloadStatus{WorkloadID: "wl-api", Phase: spec.PhaseRunning}
	current := spec.WorkloadStatus{WorkloadID: "wl-api", Phase: spec.PhaseFailed}

	events := transitionEvents(previous, current, container, noon)
	if len(events) != 1 {
		t.Fatalf("%d events for a container the kernel killed, want one", len(events))
	}
	if events[0].GetKind() != wisperpb.NodeEventKind_NODE_EVENT_KIND_WORKLOAD_OOM_KILLED {
		t.Fatalf("reported %s, want an out-of-memory event", events[0].GetKind())
	}
}

func TestNothingIsReportedForAWorkloadThatDidNotMove(t *testing.T) {
	container := Container{ID: "ctr-1", WorkloadID: "wl-api", OOMKilled: true}
	same := spec.WorkloadStatus{WorkloadID: "wl-api", Phase: spec.PhaseFailed}

	if events := transitionEvents(same, same, container, noon); len(events) != 0 {
		t.Fatalf("%d events on a pass where nothing changed; the panel would drown", len(events))
	}
}

func TestASiteThatIsServingTheReleaseTheSpecNamesIsRunning(t *testing.T) {
	site := spec.Workload{ID: "wl-site", Kind: spec.KindSite, Desired: spec.DesiredRunning, ReleaseID: "dep-9"}
	a := settled(site, Container{})
	a.Published = "dep-9"

	status := observe(a, spec.WorkloadStatus{}, noon)
	if status.Phase != spec.PhaseRunning {
		t.Fatalf("a site serving the right release is %s, want RUNNING", status.Phase)
	}
	if status.ReleaseID != "dep-9" {
		t.Fatalf("the site reported release %q", status.ReleaseID)
	}
}

func TestABlockedWorkloadIsReportedFailedWithTheReason(t *testing.T) {
	workload := app("wl-api")
	a := applied{
		Decision: decision{Workload: workload, Blocked: true, Note: "this node cannot resolve the mount"},
		Phase:    spec.PhaseFailed,
		Message:  "this node cannot resolve the mount",
	}

	status := observe(a, spec.WorkloadStatus{}, noon)
	if status.Phase != spec.PhaseFailed {
		t.Fatalf("phase = %s for a workload the node refused, want FAILED", status.Phase)
	}
	if status.Message == "" {
		t.Fatal("a refused workload was reported with no reason, which is a blank screen in the panel")
	}
}
