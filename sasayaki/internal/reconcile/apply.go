package reconcile

import (
	"context"
	"fmt"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// Executing one decision.
//
// Everything that touches the runtime or the disk is here, and nothing here decides
// anything: diff.go already chose, and this file only does. The split is what lets the
// choosing be tested with no engine at all, and it is what keeps the one dangerous
// operation in this package - removing a container - down to a single function that is
// only ever reached from a plan built on a complete container list.

// defaultStopGrace is how long SIGTERM gets when the spec does not say.
//
// Docker's own default, deliberately: the panel normally sends thirty seconds, and a
// missing value means the panel did not say rather than that it wants a long wait. It also
// bounds a pass - stopping several containers at thirty seconds each would outlast the
// systemd watchdog's window and get the daemon restarted mid-drain.
const defaultStopGrace = 10 * time.Second

// applied is what became of one decision.
type applied struct {
	Decision decision
	// The container as it stands after the action, when there is one.
	Container    Container
	HasContainer bool
	// Set when the action itself settles the phase and looking at the container would be
	// misleading: PULLING while an image comes down, STARTING for a container this pass
	// has only just started, FAILED when the action did not work.
	Phase   spec.Phase
	Message string
	// The release the site serves after this pass.
	Published string
	// Non-nil when the action failed. A pass with any of these does not mark the
	// generation applied: the machine is not where the panel asked for it to be.
	Err error
}

// apply carries out one decision.
//
// It never returns an error of its own - every failure belongs to exactly one workload and
// is reported against it. One image that will not pull must not stop the other forty
// workloads on the node from being converged.
func (l *Loop) apply(ctx context.Context, d decision) applied {
	out := applied{
		Decision:     d,
		Container:    d.Container,
		HasContainer: d.HasContainer,
		Message:      d.Note,
		Published:    d.Published,
	}

	if d.Blocked {
		out.Phase = spec.PhaseFailed
		return out
	}

	switch d.Action {
	case actionNone:
		return out

	case actionCreate:
		return l.create(ctx, out)

	case actionRecreate:
		if d.Container.Running {
			if err := l.runtime.Stop(ctx, d.Container.ID, grace(d.Workload)); err != nil {
				return fail(out, fmt.Errorf("stop %s before recreating it: %w", d.Workload.ID, err))
			}
		}
		if err := l.runtime.Remove(ctx, d.Container.ID); err != nil {
			return fail(out, fmt.Errorf("remove %s before recreating it: %w", d.Workload.ID, err))
		}
		out.HasContainer = false
		out.Container = Container{}
		return l.create(ctx, out)

	case actionStart:
		if err := l.runtime.Start(ctx, d.Container.ID); err != nil {
			return fail(out, fmt.Errorf("start %s: %w", d.Workload.ID, err))
		}
		out.Container.Running = true
		out.Phase = spec.PhaseStarting
		out.Message = "started"
		return out

	case actionStop:
		if err := l.runtime.Stop(ctx, d.Container.ID, grace(d.Workload)); err != nil {
			return fail(out, fmt.Errorf("stop %s: %w", d.Workload.ID, err))
		}
		out.Container.Running = false
		out.Phase = spec.PhaseStopped
		if out.Message == "" {
			out.Message = "stopped"
		}
		return out

	case actionPublish:
		if err := l.sites.Publish(ctx, d.Workload.ID, d.Workload.ReleaseID); err != nil {
			return fail(out, fmt.Errorf("publish release %s of site %s: %w",
				d.Workload.ReleaseID, d.Workload.ID, err))
		}
		out.Published = d.Workload.ReleaseID
		out.Message = "serving release " + d.Workload.ReleaseID
		return out

	default:
		// Unreachable: computePlan produces the constants above and nothing else. Reported
		// rather than ignored, because an action nobody performs is a workload that sits
		// in the wrong state while the panel is told everything converged.
		return fail(out, fmt.Errorf("workload %s: no handler for action %q", d.Workload.ID, d.Action))
	}
}

// create pulls what is needed, builds the container and starts it if it should be running.
//
// The pull is the reason this is not three lines. A cold image can take minutes, and a
// pass that waited for one would blow through the systemd watchdog's window and get the
// daemon restarted in the middle of a deployment. So the runtime starts the pull and
// answers immediately, the workload is reported PULLING - which is what the customer is
// waiting on - and the next pass asks again.
func (l *Loop) create(ctx context.Context, out applied) applied {
	workload := out.Decision.Workload

	image, err := l.runtime.EnsureImage(ctx, workload)
	if err != nil {
		return fail(out, fmt.Errorf("pull the image for %s: %w", workload.ID, err))
	}
	if !image.Ready {
		out.Phase = spec.PhasePulling
		out.Message = image.Progress
		if out.Message == "" {
			out.Message = "pulling " + workload.Image
		}
		return out
	}

	container, err := l.runtime.Create(ctx, workload, Fingerprint(workload))
	if err != nil {
		return fail(out, fmt.Errorf("create the container for %s: %w", workload.ID, err))
	}
	out.Container = container
	out.HasContainer = true

	if out.Decision.Want != spec.DesiredRunning {
		out.Phase = spec.PhaseStopped
		out.Message = "created, and left stopped as the spec asks"
		return out
	}

	if err := l.runtime.Start(ctx, container.ID); err != nil {
		return fail(out, fmt.Errorf("start the new container for %s: %w", workload.ID, err))
	}
	out.Container.Running = true
	out.Phase = spec.PhaseStarting
	out.Message = "created and started"
	return out
}

// removeOrphan takes away a container whose workload has left the spec.
//
// The one place this package deletes anything, and it is reachable only from a plan built
// on a container list the runtime actually returned. Stopped first so the process gets its
// SIGTERM and a chance to finish what it was writing; a customer's application being cut
// off mid-transaction is a corrupt database with extra steps.
//
// The workload's volumes are not touched. Omission from the spec means the panel no longer
// wants this running here, and that is not the same as a customer agreeing to lose their
// data (docs/contracts/node-spec.md section 1).
func (l *Loop) removeOrphan(ctx context.Context, container Container) error {
	if container.Running {
		if err := l.runtime.Stop(ctx, container.ID, defaultStopGrace); err != nil {
			return fmt.Errorf("stop the orphaned container of %s: %w", container.WorkloadID, err)
		}
	}
	if err := l.runtime.Remove(ctx, container.ID); err != nil {
		return fmt.Errorf("remove the orphaned container of %s: %w", container.WorkloadID, err)
	}
	return nil
}

// grace is how long a workload's process gets between SIGTERM and SIGKILL.
func grace(workload spec.Workload) time.Duration {
	if workload.StopGrace <= 0 {
		return defaultStopGrace
	}
	return workload.StopGrace
}

// fail records that an action did not work.
func fail(out applied, err error) applied {
	out.Err = err
	out.Phase = spec.PhaseFailed
	out.Message = err.Error()
	return out
}
