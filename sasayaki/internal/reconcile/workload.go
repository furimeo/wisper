package reconcile

import (
	"time"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Turning one workload's facts into the sentence the panel shows.
//
// The panel owns intent and the node owns fact, and this file is the whole of the node's
// half for a workload: nothing here is ever written back into a spec (AGENTS.md section
// 4.2). The phases are the ones a customer is waiting on rather than a mirror of Docker's
// own - PULLING is a phase here and is "created" over there - because the person reading
// it wants to know what is happening to their application, not what the engine calls it.

// observe describes a workload after this pass has done whatever it was going to do.
//
// previous is what the node last reported for it, and it is not decoration: it carries
// last_transition_at, which is the difference between "this container has been
// crash-looping for an hour" and "this container has just started". A daemon that dropped
// it would reset that history on every restart and every upgrade.
func observe(a applied, previous spec.WorkloadStatus, now time.Time) spec.WorkloadStatus {
	workload := a.Decision.Workload

	status := spec.WorkloadStatus{
		WorkloadID: workload.ID,
		Phase:      a.Phase,
		Message:    a.Message,
		Runtime:    workload.Runtime,
	}
	if status.Phase == "" {
		status.Phase = derivePhase(a)
	}

	if a.HasContainer {
		status.ContainerID = a.Container.ID
		status.ImageDigest = a.Container.ImageDigest
		status.RestartCount = a.Container.RestartCount
		status.ExitCode = a.Container.ExitCode
		status.StartedAt = a.Container.StartedAt
		if a.Container.Runtime != "" {
			// What is really in effect, which is how a node that fell back to runc says so
			// instead of implying an isolation it does not have.
			status.Runtime = a.Container.Runtime
		}
		if status.Message == "" {
			status.Message = a.Container.Status
		}
	}
	if workload.IsSite() {
		status.ReleaseID = a.Published
	}

	// Only moves when the phase does, so "since when" survives the fifteen-second passes
	// that find nothing to do.
	switch {
	case previous.LastTransitionAt.IsZero() || previous.Phase != status.Phase:
		status.LastTransitionAt = now
	default:
		status.LastTransitionAt = previous.LastTransitionAt
	}

	return status
}

// derivePhase reads the phase off the machine, for the passes where the action taken did
// not settle it by itself.
func derivePhase(a applied) spec.Phase {
	d := a.Decision

	if d.Workload.IsSite() {
		switch {
		case d.Workload.ReleaseID == "":
			return spec.PhasePending
		case d.Want == spec.DesiredStopped:
			return spec.PhaseStopped
		case a.Published == d.Workload.ReleaseID:
			// A site is "running" when its files are the ones the spec names. There is no
			// process to be running, which is the whole point of serving it from disk.
			return spec.PhaseRunning
		default:
			return spec.PhasePending
		}
	}

	if !a.HasContainer {
		if d.Want == spec.DesiredRunning {
			// Reachable when the panel sent no desired state, or on the pass in which a
			// container was removed for drift and not yet rebuilt.
			return spec.PhasePending
		}
		return spec.PhaseStopped
	}

	if a.Container.Running {
		if a.Container.Health == HealthFailing {
			// Reported, never acted on. The panel decides: a check failing during a slow
			// migration is not a reason to restart a database-backed app in a loop.
			return spec.PhaseUnhealthy
		}
		return spec.PhaseRunning
	}

	switch {
	case crashLooping(d.Workload, a.Container):
		return spec.PhaseCrashLooping
	case d.Want == spec.DesiredRunning && a.Container.ExitCode != 0:
		return spec.PhaseFailed
	default:
		return spec.PhaseStopped
	}
}

// crashLooping reports whether the engine has given up restarting this container.
//
// Exactly the rule the contract states and no cleverer: a workload with an on-failure
// policy that has exhausted its retries is crash-looping, and the customer is told rather
// than left watching a service that restarts forever and never works. A policy with no
// retry limit is not guessed at - the engine will keep restarting it, restart_count climbs
// in every batch, and inventing a threshold here would label a container that happens to
// have been restarted five times over a year as broken.
func crashLooping(workload spec.Workload, container Container) bool {
	if container.Running {
		return false
	}
	if workload.Restart.Mode != spec.RestartOnFailure || workload.Restart.MaxRetries <= 0 {
		return false
	}
	return container.RestartCount >= workload.Restart.MaxRetries
}

// transitionEvents is what happened to this workload that neither neighbouring status
// batch would show.
//
// A batch carries the steady state; a container the kernel killed and a service that has
// stopped being restarted are both invisible in the batch before and the batch after. They
// are emitted on the transition and not on every pass, because an event repeated every
// fifteen seconds is an event nobody reads.
func transitionEvents(previous, current spec.WorkloadStatus, container Container, at time.Time) []*wisperpb.NodeEvent {
	if previous.Phase == current.Phase {
		return nil
	}

	var events []*wisperpb.NodeEvent
	if current.Phase == spec.PhaseCrashLooping {
		events = append(events, &wisperpb.NodeEvent{
			Kind:      wisperpb.NodeEventKind_NODE_EVENT_KIND_WORKLOAD_CRASH_LOOPING,
			Severity:  wisperpb.EventSeverity_EVENT_SEVERITY_WARNING,
			SubjectId: current.WorkloadID,
			Detail:    current.Message,
			At:        timestamppb.New(at),
		})
	}
	if container.OOMKilled && !current.Phase.IsUp() {
		events = append(events, &wisperpb.NodeEvent{
			Kind:      wisperpb.NodeEventKind_NODE_EVENT_KIND_WORKLOAD_OOM_KILLED,
			Severity:  wisperpb.EventSeverity_EVENT_SEVERITY_WARNING,
			SubjectId: current.WorkloadID,
			Detail:    "the kernel killed this container for exceeding its memory limit",
			At:        timestamppb.New(at),
		})
	}
	return events
}

// unknownStatus is what a workload gets when Docker could not be asked and the node has
// never observed it.
//
// UNKNOWN is the per-workload form of StatusBatch.partial, and it exists so a node with no
// answer says "I cannot see it" instead of saying nothing - which the panel would read as
// the workload having gone away (docs/contracts/node-spec.md section 5).
func unknownStatus(workload spec.Workload, detail string, at time.Time) spec.WorkloadStatus {
	return spec.WorkloadStatus{
		WorkloadID:       workload.ID,
		Phase:            spec.PhaseUnknown,
		Runtime:          workload.Runtime,
		Message:          detail,
		LastTransitionAt: at,
	}
}
