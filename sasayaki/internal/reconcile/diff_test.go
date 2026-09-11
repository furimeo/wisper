package reconcile

import (
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// computePlan is a pure function, so the decisions can be checked without a container
// engine anywhere near them. These are the cases where doing nothing is the right answer
// and the temptation is to guess.

func app(id string) spec.Workload {
	return spec.Workload{
		ID:      id,
		Kind:    spec.KindApp,
		Name:    id,
		Image:   "docker.io/library/busybox:1",
		Runtime: spec.RuntimeRunsc,
		Desired: spec.DesiredRunning,
	}
}

func planFor(workloads []spec.Workload, containers []Container) plan {
	return computePlan(
		spec.Spec{Generation: 1, Workloads: workloads},
		containers,
		map[string]string{},
		map[string]spec.WorkloadStatus{},
		false,
	)
}

func only(t *testing.T, work plan) decision {
	t.Helper()
	if len(work.Decisions) != 1 {
		t.Fatalf("the plan holds %d decisions, want 1", len(work.Decisions))
	}
	return work.Decisions[0]
}

func TestAWorkloadOfAnUnknownKindIsRefusedRatherThanGuessedAt(t *testing.T) {
	// A kind this binary does not implement could be a container or a directory of files.
	// Starting the wrong one is worse than saying so.
	workload := app("wl-new")
	workload.Kind = spec.KindUnknown

	d := only(t, planFor([]spec.Workload{workload}, nil))
	if !d.Blocked || d.Action != actionNone {
		t.Fatalf("decided %q, blocked=%v, want no action and a refusal", d.Action, d.Blocked)
	}
}

func TestAWorkloadWithNoDesiredStateIsNeitherStartedNorStopped(t *testing.T) {
	// Guessing "running" starts something a customer paid to have stopped; guessing
	// "stopped" takes down something that is serving.
	workload := app("wl-api")
	workload.Desired = spec.DesiredUnspecified

	d := only(t, planFor([]spec.Workload{workload}, nil))
	if d.Action != actionNone || d.Blocked {
		t.Fatalf("decided %q, blocked=%v, want no action and no refusal", d.Action, d.Blocked)
	}
	if d.Note == "" {
		t.Fatal("nothing was said about a workload the panel gave no desired state for")
	}
}

func TestAMountOfAnUnknownKindStopsTheWorkloadStarting(t *testing.T) {
	// An application that comes up with an empty data directory writes into it, which looks
	// like success and is data loss.
	workload := app("wl-api")
	workload.Mounts = []spec.Mount{{VolumeID: "vol-1", Kind: spec.MountKindUnknown, Target: "/srv/data"}}

	if d := only(t, planFor([]spec.Workload{workload}, nil)); !d.Blocked {
		t.Fatalf("decided %q on a workload whose mount cannot be resolved", d.Action)
	}
}

func TestAnAppWithNoImageIsRefused(t *testing.T) {
	workload := app("wl-api")
	workload.Image = ""

	if d := only(t, planFor([]spec.Workload{workload}, nil)); !d.Blocked {
		t.Fatalf("decided %q on an app with no image", d.Action)
	}
}

func TestAStoppedWorkloadWithNoContainerIsLeftAlone(t *testing.T) {
	// Creating a container so it can sit there stopped would pull an image nobody is
	// waiting for and reserve a quota nobody is using.
	workload := app("wl-api")
	workload.Desired = spec.DesiredStopped

	if d := only(t, planFor([]spec.Workload{workload}, nil)); d.Action != actionNone {
		t.Fatalf("decided %q for a stopped workload that has never been created", d.Action)
	}
}

func TestAContainerWithNoFingerprintIsRebuilt(t *testing.T) {
	// Something other than this reconciler created it, and the spec is the only authority
	// for what a managed container should be.
	workload := app("wl-api")
	existing := Container{ID: "ctr-1", WorkloadID: "wl-api", Running: true}

	if d := only(t, planFor([]spec.Workload{workload}, []Container{existing})); d.Action != actionRecreate {
		t.Fatalf("decided %q for a container carrying no fingerprint", d.Action)
	}
}

func TestAMatchingRunningContainerIsLeftAlone(t *testing.T) {
	workload := app("wl-api")
	existing := Container{
		ID:          "ctr-1",
		WorkloadID:  "wl-api",
		Fingerprint: Fingerprint(workload),
		Running:     true,
	}

	if d := only(t, planFor([]spec.Workload{workload}, []Container{existing})); d.Action != actionNone {
		t.Fatalf("decided %q for a container that already matches the spec", d.Action)
	}
}

func TestDrainingStopsWhatCanMoveAndLeavesWhatHoldsData(t *testing.T) {
	stateless := app("wl-api")
	stateful := app("wl-db")
	stateful.Mounts = []spec.Mount{{VolumeID: "vol-1", Kind: spec.MountKindVolume, Target: "/var/lib/data"}}

	containers := []Container{
		{ID: "ctr-api", WorkloadID: "wl-api", Fingerprint: Fingerprint(stateless), Running: true},
		{ID: "ctr-db", WorkloadID: "wl-db", Fingerprint: Fingerprint(stateful), Running: true},
	}
	work := computePlan(
		spec.Spec{Workloads: []spec.Workload{stateless, stateful}},
		containers,
		map[string]string{},
		map[string]spec.WorkloadStatus{},
		true,
	)

	byWorkload := make(map[string]decision, len(work.Decisions))
	for _, d := range work.Decisions {
		byWorkload[d.Workload.ID] = d
	}
	if got := byWorkload["wl-api"].Action; got != actionStop {
		t.Fatalf("a drained node decided %q for a workload holding no volume, want stop", got)
	}
	if got := byWorkload["wl-db"].Action; got != actionNone {
		t.Fatalf("a drained node decided %q for a workload holding a volume; "+
			"moving it means moving data, and that is never automatic", got)
	}
}

func TestASiteThatLeftTheSpecIsDiscardedAndAnAppThatNeverPublishedIsNot(t *testing.T) {
	// A site has no container, so the only record that a tree was published here is the
	// status the node last reported. Nothing but a site ever carries a release id.
	previous := map[string]spec.WorkloadStatus{
		"wl-site": {WorkloadID: "wl-site", ReleaseID: "dep-1"},
		"wl-api":  {WorkloadID: "wl-api"},
	}
	work := computePlan(spec.Spec{}, nil, map[string]string{}, previous, false)

	if len(work.OrphanSites) != 1 || work.OrphanSites[0] != "wl-site" {
		t.Fatalf("orphaned sites %v, want only the one that had a release published", work.OrphanSites)
	}
}

func TestASiteWithNoReleaseIsPendingRatherThanBroken(t *testing.T) {
	site := spec.Workload{ID: "wl-site", Kind: spec.KindSite, Name: "marketing", Desired: spec.DesiredRunning}

	d := only(t, planFor([]spec.Workload{site}, nil))
	if d.Action != actionNone || d.Blocked {
		t.Fatalf("decided %q, blocked=%v for a site between being created and its first build",
			d.Action, d.Blocked)
	}
}
