package runtime

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	cerrdefs "github.com/containerd/errdefs"
	"github.com/moby/moby/client"
)

// defaultStopGrace is how long SIGTERM gets when the spec did not say.
//
// The engine's own default is ten seconds, which is short enough that a database-backed
// application loses whatever it had in flight. Thirty matches what the panel sends
// (docs/contracts/node-spec.md section 3.2), so a workload created before the field
// existed behaves the same as one created after it.
const defaultStopGrace = 30 * time.Second

// Start runs a container that already exists.
//
// Already running is success. The reconcile loop is not a state machine that tracks what
// it did last pass - it looks at the machine and closes the gap - so it will ask for this
// again whenever its view is a moment out of date, and answering "already started" as an
// error would put a healthy workload into FAILED.
func (d *Docker) Start(ctx context.Context, containerID string) error {
	_, err := d.api.ContainerStart(ctx, containerID, client.ContainerStartOptions{})
	if err == nil || errors.Is(err, cerrdefs.ErrNotModified) {
		return nil
	}
	return fmt.Errorf("runtime: start the container %s: %w", containerID, err)
}

// Stop asks a container to finish, and kills it if it will not.
//
// SIGTERM, then grace, then SIGKILL - and the engine does all three, so there is no
// window here where this daemon dying leaves a container half-stopped. Already stopped is
// success, for the same reason as Start.
func (d *Docker) Stop(ctx context.Context, containerID string, grace time.Duration) error {
	if grace <= 0 {
		grace = defaultStopGrace
	}
	seconds := int(grace.Seconds())

	_, err := d.api.ContainerStop(ctx, containerID, client.ContainerStopOptions{Timeout: &seconds})
	if err == nil || errors.Is(err, cerrdefs.ErrNotModified) || notFound(err) {
		return nil
	}
	return fmt.Errorf("runtime: stop the container %s within %s: %w", containerID, grace, err)
}

// Remove deletes a container and the logs that belong to it.
//
// It does not delete the customer's data, and the flag that would is set to false on
// purpose. A workload leaving the spec means the panel no longer wants it running here;
// it does not mean anybody agreed to lose a volume. Bind-mounted volumes are outside the
// container's lifetime anyway, and anonymous volumes an image declared are left behind
// deliberately - an orphaned directory can be collected later, and one that has been
// deleted cannot be brought back.
//
// Force, because a container that is still running has to go: the reconcile loop stops it
// first, but between the two passes the engine may have restarted it under its own restart
// policy, and failing here would leave the workload half-removed forever.
//
// Not found is success. Something else removed it, or this is a retry.
func (d *Docker) Remove(ctx context.Context, containerID string) error {
	_, err := d.api.ContainerRemove(ctx, containerID, client.ContainerRemoveOptions{
		Force:         true,
		RemoveVolumes: false,
	})
	if err == nil || notFound(err) {
		d.log.Info("removed a container", slog.String("container", containerID))
		return nil
	}
	return fmt.Errorf("runtime: remove the container %s: %w", containerID, err)
}
