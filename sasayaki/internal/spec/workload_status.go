package spec

import (
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Phase is where a workload actually is.
//
// Phases the node can observe, not a mirror of Docker's own states: "pulling" is what a
// customer is waiting on and Docker calls that "created". The panel owns intent and the
// node owns fact, so nothing here is ever written back into a spec.
type Phase string

const (
	// PhaseUnspecified is the zero value and means nothing has looked yet. It is not the
	// same as PhaseUnknown, which means somebody looked and Docker did not answer.
	PhaseUnspecified Phase = "UNSPECIFIED"
	PhasePending     Phase = "PENDING"
	PhasePulling     Phase = "PULLING"
	PhaseStarting    Phase = "STARTING"
	PhaseRunning     Phase = "RUNNING"
	// PhaseUnhealthy is reported, not acted on. The panel decides: a failing health check
	// during a slow migration is not a reason to restart a database-backed app in a loop.
	PhaseUnhealthy Phase = "UNHEALTHY"
	PhaseStopped   Phase = "STOPPED"
	PhaseFailed    Phase = "FAILED"
	// PhaseCrashLooping is reported once Restart.MaxRetries is exhausted, so the customer
	// is told instead of being left with a service that restarts forever and never works.
	PhaseCrashLooping Phase = "CRASH_LOOPING"
	// PhaseUnknown means Docker did not answer. The container is probably fine and must not
	// be touched: mistaking "cannot see it" for "does not exist" is the fastest way to
	// delete a customer's data (AGENTS.md section 4.5).
	PhaseUnknown Phase = "UNKNOWN"
)

// IsUp reports whether the workload is serving, health notwithstanding.
func (p Phase) IsUp() bool { return p == PhaseRunning || p == PhaseUnhealthy }

// IsSettled reports whether the phase is one that will not change without something
// happening. The reconciler uses it to decide whether a workload is worth looking at again
// on the next pass or is simply where it should be.
func (p Phase) IsSettled() bool {
	switch p {
	case PhaseRunning, PhaseStopped, PhaseFailed, PhaseCrashLooping:
		return true
	default:
		return false
	}
}

// WorkloadStatus is the observed half of a Workload.
type WorkloadStatus struct {
	WorkloadID  string
	Phase       Phase
	ContainerID string
	// What is really running, which is how a moved tag becomes visible instead of being a
	// mystery restart.
	ImageDigest  string
	RestartCount int32
	// Of the last exit. Meaningless while running, and Phase says which it is.
	ExitCode int32
	// Human-readable reason for the current phase: the pull error, the OOM kill, the health
	// check's output. Written for a person, because the panel shows it to one.
	Message          string
	StartedAt        time.Time
	LastTransitionAt time.Time
	// The runtime in effect, not the one requested. A node without runsc falls back to runc
	// and the panel has to say so out loud rather than imply isolation it does not have.
	Runtime Runtime
	// Site only: which release the `current` symlink resolves to. The panel compares it
	// with the spec to show a deployment as live.
	ReleaseID string
}

// ToProto renders the status for the wire.
func (w WorkloadStatus) ToProto() *wisperpb.WorkloadStatus {
	return &wisperpb.WorkloadStatus{
		WorkloadId:       w.WorkloadID,
		Phase:            phaseToProto(w.Phase),
		ContainerId:      w.ContainerID,
		ImageDigest:      w.ImageDigest,
		RestartCount:     w.RestartCount,
		ExitCode:         w.ExitCode,
		Message:          w.Message,
		StartedAt:        wireInstant(w.StartedAt),
		LastTransitionAt: wireInstant(w.LastTransitionAt),
		Runtime:          runtimeToProto(w.Runtime),
		ReleaseId:        w.ReleaseID,
	}
}

// WorkloadStatusFromProto reads a status back.
//
// The node's own statuses come back through here: internal/state stores them as the
// protobuf they will be sent as, and a restarted daemon reads them again. That is what
// makes "this container has been crash-looping for an hour" survive an upgrade, instead of
// every restart resetting the history to "just started".
func WorkloadStatusFromProto(message *wisperpb.WorkloadStatus) WorkloadStatus {
	return WorkloadStatus{
		WorkloadID:       message.GetWorkloadId(),
		Phase:            phaseFromProto(message.GetPhase()),
		ContainerID:      message.GetContainerId(),
		ImageDigest:      message.GetImageDigest(),
		RestartCount:     message.GetRestartCount(),
		ExitCode:         message.GetExitCode(),
		Message:          message.GetMessage(),
		StartedAt:        instant(message.GetStartedAt()),
		LastTransitionAt: instant(message.GetLastTransitionAt()),
		Runtime:          runtimeFromProto(message.GetRuntime()),
		ReleaseID:        message.GetReleaseId(),
	}
}

func phaseToProto(value Phase) wisperpb.WorkloadPhase {
	switch value {
	case PhasePending:
		return wisperpb.WorkloadPhase_WORKLOAD_PHASE_PENDING
	case PhasePulling:
		return wisperpb.WorkloadPhase_WORKLOAD_PHASE_PULLING
	case PhaseStarting:
		return wisperpb.WorkloadPhase_WORKLOAD_PHASE_STARTING
	case PhaseRunning:
		return wisperpb.WorkloadPhase_WORKLOAD_PHASE_RUNNING
	case PhaseUnhealthy:
		return wisperpb.WorkloadPhase_WORKLOAD_PHASE_UNHEALTHY
	case PhaseStopped:
		return wisperpb.WorkloadPhase_WORKLOAD_PHASE_STOPPED
	case PhaseFailed:
		return wisperpb.WorkloadPhase_WORKLOAD_PHASE_FAILED
	case PhaseCrashLooping:
		return wisperpb.WorkloadPhase_WORKLOAD_PHASE_CRASH_LOOPING
	case PhaseUnknown:
		return wisperpb.WorkloadPhase_WORKLOAD_PHASE_UNKNOWN
	default:
		return wisperpb.WorkloadPhase_WORKLOAD_PHASE_UNSPECIFIED
	}
}

func phaseFromProto(value wisperpb.WorkloadPhase) Phase {
	switch value {
	case wisperpb.WorkloadPhase_WORKLOAD_PHASE_PENDING:
		return PhasePending
	case wisperpb.WorkloadPhase_WORKLOAD_PHASE_PULLING:
		return PhasePulling
	case wisperpb.WorkloadPhase_WORKLOAD_PHASE_STARTING:
		return PhaseStarting
	case wisperpb.WorkloadPhase_WORKLOAD_PHASE_RUNNING:
		return PhaseRunning
	case wisperpb.WorkloadPhase_WORKLOAD_PHASE_UNHEALTHY:
		return PhaseUnhealthy
	case wisperpb.WorkloadPhase_WORKLOAD_PHASE_STOPPED:
		return PhaseStopped
	case wisperpb.WorkloadPhase_WORKLOAD_PHASE_FAILED:
		return PhaseFailed
	case wisperpb.WorkloadPhase_WORKLOAD_PHASE_CRASH_LOOPING:
		return PhaseCrashLooping
	case wisperpb.WorkloadPhase_WORKLOAD_PHASE_UNKNOWN:
		return PhaseUnknown
	default:
		return PhaseUnspecified
	}
}
