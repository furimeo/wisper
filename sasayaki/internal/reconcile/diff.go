package reconcile

import (
	"sort"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// Desired against actual, in one pure function.
//
// Nothing in this file talks to Docker, the edge or the disk. That is what makes the three
// branches the quality gate insists on - create, drift, garbage collection - testable
// without a container runtime, and it is what keeps the interesting decision in one place
// instead of scattered through the code that performs it.

// action is what a pass will do about one workload.
type action string

const (
	// actionNone covers two different situations that need the same treatment: the
	// workload is already where it should be, and the panel has not said where that is.
	actionNone   action = "none"
	actionCreate action = "create"
	actionStart  action = "start"
	actionStop   action = "stop"
	// actionRecreate is the drift path: stop, remove, create from the current spec, and
	// start again if it should be running.
	actionRecreate action = "recreate"
	// actionPublish points a site's `current` symlink at the release the spec names.
	// Deploying and rolling back are the same action.
	actionPublish action = "publish"
)

// decision is one workload's whole story for one pass: what is there, what will be done
// about it, and what to say if the answer is "nothing".
type decision struct {
	Workload  spec.Workload
	Container Container
	// False when no container exists for this workload yet, which is different from a
	// container that exists and is stopped.
	HasContainer bool
	// Where this pass wants the workload, after a drain has had its say. Not the same as
	// Workload.Desired, which is what the panel published.
	Want   spec.Desired
	Action action
	// Why. It becomes WorkloadStatus.Message when the phase alone does not explain
	// what an operator is looking at.
	Note string
	// The node cannot run this workload at all: a kind this binary does not implement, a
	// mount it cannot resolve, an app with no image. Reported FAILED and left alone, never
	// guessed at.
	Blocked bool
	// What the site is serving right now, for a site workload.
	Published string
}

// plan is one pass's whole intent.
type plan struct {
	// One per workload in the spec, in spec order.
	Decisions []decision
	// Managed containers whose workload is not in the spec. Omission is deletion: this is
	// the garbage-collection half of reconciliation, and the only place this package
	// removes anything.
	Orphans []Container
	// Sites that were published here and are not in the spec any more, by workload id.
	OrphanSites []string
}

// computePlan works out what this pass will do.
//
// containers must be the complete list the runtime returned. Calling this with a partial
// list would produce orphan removals for containers that merely were not mentioned, which
// is exactly the mistake the whole daemon is built to avoid - so the caller only reaches
// here after Containers succeeded.
func computePlan(
	desired spec.Spec,
	containers []Container,
	published map[string]string,
	previous map[string]spec.WorkloadStatus,
	drained bool,
) plan {
	byWorkload := make(map[string]Container, len(containers))
	for _, container := range containers {
		if container.WorkloadID == "" {
			// Managed by the daemon, not by this loop: a database engine, a build. Another
			// package owns it and removing it here would take out a shared service.
			continue
		}
		byWorkload[container.WorkloadID] = container
	}

	result := plan{Decisions: make([]decision, 0, len(desired.Workloads))}
	wanted := make(map[string]struct{}, len(desired.Workloads))

	for _, workload := range desired.Workloads {
		wanted[workload.ID] = struct{}{}
		container, hasContainer := byWorkload[workload.ID]
		result.Decisions = append(result.Decisions,
			decide(workload, container, hasContainer, published[workload.ID], drained))
	}

	for _, container := range containers {
		if container.WorkloadID == "" {
			continue
		}
		if _, keep := wanted[container.WorkloadID]; !keep {
			result.Orphans = append(result.Orphans, container)
		}
	}

	// A site has no container, so the only record that one was ever published here is the
	// status the node last reported for it. ReleaseID is set on nothing else, which makes
	// it an unambiguous marker of "there is a tree on disk for this workload".
	for workloadID, status := range previous {
		if status.ReleaseID == "" {
			continue
		}
		if _, keep := wanted[workloadID]; !keep {
			result.OrphanSites = append(result.OrphanSites, workloadID)
		}
	}
	sort.Strings(result.OrphanSites)

	return result
}

// decide is the whole of the per-workload rule, split out because the loop above is about
// bookkeeping and this is about policy.
func decide(workload spec.Workload, container Container, hasContainer bool, published string, drained bool) decision {
	d := decision{
		Workload:     workload,
		Container:    container,
		HasContainer: hasContainer,
		Want:         workload.Desired,
		Action:       actionNone,
		Published:    published,
	}

	if reason, blocked := unrunnable(workload); blocked {
		d.Blocked = true
		d.Note = reason
		return d
	}

	// The panel did not say what it wants. Guessing "running" starts something a customer
	// may have paid to have stopped, and guessing "stopped" takes down something that is
	// serving, so the node does neither and says so (design section 6, spec package).
	if workload.Desired == spec.DesiredUnspecified {
		d.Note = "the panel sent no desired state for this workload, so the node has neither started nor stopped it"
		return d
	}

	want := workload.Desired
	if drained && want == spec.DesiredRunning && !holdsVolume(workload) {
		// Draining evacuates what can be moved and never what holds data. The panel will
		// publish the same decision in its next spec; this keeps the node from restarting
		// the workload in the fifteen seconds before that arrives (design section 7.7).
		want = spec.DesiredStopped
		d.Note = "stopped for a drain: this workload holds no volume, so the panel can place it elsewhere"
	}
	d.Want = want

	if workload.IsSite() {
		return decideSite(d, want)
	}
	return decideApp(d, want)
}

// decideSite converges a directory of files. There is no process, which is what makes an
// idle static site cost nothing (design section 5.5).
func decideSite(d decision, want spec.Desired) decision {
	switch {
	case d.Workload.ReleaseID == "":
		// Placed but never deployed. Normal for the minutes between creating a site and
		// the first build finishing, so it is a phase and not a failure.
		d.Note = "no release has been published for this site yet"
	case want == spec.DesiredStopped:
		// Nothing to stop. The release tree stays where it is - a paused site that lost
		// its files would have nothing to come back to - and the panel withdraws its
		// routes by leaving them out of the spec.
		d.Note = "this site is stopped; its release is on disk and is not being served"
	case d.Published != d.Workload.ReleaseID:
		d.Action = actionPublish
		d.Note = "publishing release " + d.Workload.ReleaseID
	}
	return d
}

// decideApp converges a container.
func decideApp(d decision, want spec.Desired) decision {
	if !d.HasContainer {
		if want == spec.DesiredStopped {
			// Nothing exists and nothing should be running. Creating a container so it can
			// sit there stopped would pull an image nobody is waiting for and reserve a
			// quota nobody is using.
			d.Note = "stopped, and no container has been created for it"
			return d
		}
		d.Action = actionCreate
		return d
	}

	if d.Container.Fingerprint != Fingerprint(d.Workload) {
		d.Action = actionRecreate
		d.Note = driftNote(d.Container.Fingerprint, Fingerprint(d.Workload))
		return d
	}

	switch {
	case want == spec.DesiredRunning && !d.Container.Running:
		d.Action = actionStart
	case want == spec.DesiredStopped && d.Container.Running:
		d.Action = actionStop
	}
	return d
}

// driftNote explains a recreation in the one line an operator will read.
func driftNote(found, wanted string) string {
	if found == "" {
		return "recreating: this container carries no configuration fingerprint, so the spec is the only authority for what it should be"
	}
	return "recreating: the spec changed (" + short(found) + " -> " + short(wanted) + ")"
}

// unrunnable reports the reasons the node refuses to act on a workload at all.
//
// Each one is a case where doing something would be worse than doing nothing. A kind this
// binary does not know could be a container or a directory of files and starting the wrong
// one is worse than reporting the problem. A mount that cannot be resolved would let an
// application come up with an empty data directory and write into it, which looks like
// success and is data loss.
func unrunnable(workload spec.Workload) (string, bool) {
	if workload.Kind == spec.KindUnknown {
		return "this node does not know how to run a workload of that kind; it is probably newer than this daemon", true
	}
	for _, mount := range workload.Mounts {
		if mount.Kind == spec.MountKindUnknown {
			return "the mount at " + mount.Target + " is of a kind this daemon cannot resolve, " +
				"and starting the workload without it would let it write into an empty directory", true
		}
	}
	if workload.IsApp() && workload.Image == "" {
		return "this workload is an app with no image", true
	}
	return "", false
}

// holdsVolume reports whether moving this workload would mean moving data.
//
// Only a persistent volume counts. A tmpfs is scratch and a site release is rebuilt by the
// next deployment, so neither pins a workload to this machine.
func holdsVolume(workload spec.Workload) bool {
	for _, mount := range workload.Mounts {
		if mount.Kind == spec.MountKindVolume {
			return true
		}
	}
	return false
}
