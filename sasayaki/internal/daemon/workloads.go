package daemon

import (
	"context"
	"errors"
	"fmt"
	"time"

	cerrdefs "github.com/containerd/errdefs"
	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/backup"
	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
)

// Freezing a customer's application for as long as it takes to copy their data, and no
// longer.
//
// backup.Workloads is keyed by workload id because that is the vocabulary a backup is asked
// for in; the runtime package is keyed by container id because that is what the engine
// takes. Joining the two is this file, and it is in the composition root rather than in
// either package because it is the only place that already knows both.
//
// Pause and unpause do not come from the runtime package at all. That package's engine
// interface is a written statement of what this daemon asks a container engine to do, and
// freezing a container is not on the list - it is a backup's tool and nothing else's. So
// they come straight off the shared Engine API connection, which is also the only reason
// this adapter needs two collaborators rather than one.

// workloadContainers is the container runtime, as a backup needs it: find the container
// behind a workload, and stop or start it.
type workloadContainers interface {
	ContainerFor(ctx context.Context, workloadID string) (reconcile.Container, bool, error)
	Start(ctx context.Context, containerID string) error
	Stop(ctx context.Context, containerID string, grace time.Duration) error
}

// freezer is the engine's own suspend, which is what makes a volume snapshot a backup: an
// application paused for four hundred milliseconds notices nothing, while one that is
// stopped and started loses every connection it had open.
type freezer interface {
	ContainerPause(ctx context.Context, containerID string, options client.ContainerPauseOptions) (client.ContainerPauseResult, error)
	ContainerUnpause(ctx context.Context, containerID string, options client.ContainerUnpauseOptions) (client.ContainerUnpauseResult, error)
}

// frozenWorkloads is the daemon's backup.Workloads.
type frozenWorkloads struct {
	containers workloadContainers
	engine     freezer
}

var _ backup.Workloads = frozenWorkloads{}

// Running reports whether this workload has a container that is running right now.
//
// A workload that is not running needs no pause, and false with no error is the normal
// answer for one the customer has scaled to zero. An error is the engine failing to answer,
// which the caller turns into "the volume was not copied" - because taking a snapshot
// without knowing whether anything is writing to it is how a half-written database file
// becomes somebody's only backup.
func (w frozenWorkloads) Running(ctx context.Context, workloadID string) (bool, error) {
	container, found, err := w.containers.ContainerFor(ctx, workloadID)
	if err != nil {
		return false, err
	}
	if !found {
		return false, nil
	}
	// True while paused as well, which is what the caller wants: a container the engine has
	// frozen is one that is still holding its files open.
	return container.Running, nil
}

// Pause suspends every process in the workload's container.
func (w frozenWorkloads) Pause(ctx context.Context, workloadID string) error {
	container, found, err := w.containers.ContainerFor(ctx, workloadID)
	if err != nil {
		return err
	}
	if !found {
		return fmt.Errorf("daemon: workload %s has no container on this node, so nothing could "+
			"be paused before its volume was read", workloadID)
	}

	if _, err := w.engine.ContainerPause(ctx, container.ID, client.ContainerPauseOptions{}); err != nil {
		return fmt.Errorf("daemon: pause the container of workload %s: %w", workloadID, err)
	}
	return nil
}

// Unpause lets the processes go again.
//
// Called from a deferred function on every path, including the one where the snapshot
// failed and the one where a previous daemon was killed mid-backup, so the two ways of
// already being thawed are both success. A container that is gone cannot be frozen, and one
// the engine says is not paused is one nobody needs to unpause - reporting either as a
// failure would have an operator go looking for an application that is running perfectly
// well.
func (w frozenWorkloads) Unpause(ctx context.Context, workloadID string) error {
	container, found, err := w.containers.ContainerFor(ctx, workloadID)
	if err != nil {
		return err
	}
	if !found {
		return nil
	}

	_, err = w.engine.ContainerUnpause(ctx, container.ID, client.ContainerUnpauseOptions{})
	if err == nil || errors.Is(err, cerrdefs.ErrNotFound) || errors.Is(err, cerrdefs.ErrConflict) {
		return nil
	}
	return fmt.Errorf("daemon: unpause the container of workload %s: %w", workloadID, err)
}

// Stop is the heavier pair, used by a restore: overwriting a volume underneath a running
// process gives it a filesystem that changed while it was not looking.
//
// A workload with no container is already in the state the caller asked for. The grace
// period is left to the runtime, which uses the same thirty seconds the panel sends in a
// spec, so a database being stopped for a restore gets as long to flush as it does for a
// deployment.
func (w frozenWorkloads) Stop(ctx context.Context, workloadID string) error {
	container, found, err := w.containers.ContainerFor(ctx, workloadID)
	if err != nil {
		return err
	}
	if !found {
		return nil
	}
	return w.containers.Stop(ctx, container.ID, 0)
}

// Start puts the workload back after a restore.
//
// A container that has disappeared is reported rather than shrugged off. The reconcile loop
// will recreate it within the next pass, but the restore has to say that it did not manage
// to start what it stopped: the caller logs that at error level and marks the report, which
// is the difference between an operator knowing an application is down and finding out from
// the customer.
func (w frozenWorkloads) Start(ctx context.Context, workloadID string) error {
	container, found, err := w.containers.ContainerFor(ctx, workloadID)
	if err != nil {
		return err
	}
	if !found {
		return fmt.Errorf("daemon: workload %s no longer has a container on this node, so it "+
			"could not be started again; the next reconcile pass will recreate it", workloadID)
	}
	return w.containers.Start(ctx, container.ID)
}
