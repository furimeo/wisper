package dbengine

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/state"
)

// Making the servers on this node match the ones in the spec.
//
// The same shape as the workload loop next door and for the same reasons: read the document,
// look at the machine, close the gap, and do it on a timer whether or not anything happened.
// A database server is reconciled rather than commanded because the alternative - creating it
// when the panel says so and never looking again - is a node whose PostgreSQL has been down
// since a reboot three weeks ago and a panel that still shows it running.
//
// The one rule that is different from the workload loop: **a container may be removed, a data
// directory never is.** An image can be pulled again; a customer's database cannot. So a
// server that has left the spec has its container taken away and its directory left exactly
// where it was, ready for the next node that is told to run it.

const (
	// pullTimeout is the whole budget for fetching a server image. Generous, because a cold
	// PostgreSQL image on a small connection is minutes, and bounded, because a registry that
	// has been dribbling bytes for half an hour is not going to finish.
	pullTimeout = 30 * time.Minute

	// stopGrace is how long a server gets to shut down cleanly before it is killed. A
	// database flushing its buffers is the one process on a node where the difference between
	// a clean stop and a kill is measured in a customer's recovery time.
	stopGrace = 60 * time.Second
)

// Converge makes the servers on this node match the spec, once.
//
// It returns the first thing that went wrong joined with everything else that did, and it
// never stops early: a node where one image will not pull is still a node whose other server
// is entitled to be running.
func (e *Engines) Converge(ctx context.Context) error {
	e.converging.Lock()
	defer e.converging.Unlock()
	return e.converge(ctx)
}

// converge is Converge with the lock already held, so a command that has taken it for its own
// reasons does not deadlock against itself.
func (e *Engines) converge(ctx context.Context) error {
	desired, err := e.desired(ctx)
	if err != nil {
		if errors.Is(err, state.ErrNoSpec) {
			// A freshly enrolled node, before its first ApplySpec. Nothing to run and -
			// this is the part that matters - nothing to remove.
			return nil
		}
		return err
	}

	wanted, problems := e.resolve(desired)
	existing, err := e.containers(ctx)
	if err != nil {
		// The engine could not be asked. Nothing is concluded from that and nothing is
		// removed: mistaking "cannot see it" for "does not exist" is how a node deletes a
		// customer's database server (AGENTS.md section 4.5).
		return err
	}

	keep := make(map[string]bool, len(wanted))
	for _, built := range wanted {
		keep[built.ID] = true
		if err := e.ensure(ctx, built, existing[built.ID]); err != nil {
			problems = append(problems, err.Error())
		}
	}

	for id, found := range existing {
		if keep[id] {
			continue
		}
		if err := e.retire(ctx, id, found); err != nil {
			problems = append(problems, err.Error())
		}
	}

	if err := e.attachTenants(ctx, desired, wanted); err != nil {
		problems = append(problems, err.Error())
	}
	if err := e.settle(ctx, desired, wanted); err != nil {
		problems = append(problems, err.Error())
	}

	if len(problems) > 0 {
		return errors.New(strings.Join(problems, "; "))
	}
	return nil
}

// desired reads the spec from disk. state.ErrNoSpec is passed through, because "nobody has
// said yet" and "run nothing" are different instructions and only the caller knows which one
// it is answering.
func (e *Engines) desired(ctx context.Context) (spec.Spec, error) {
	stored, err := e.store.LoadSpec(ctx)
	if err != nil {
		return spec.Spec{}, err
	}
	return spec.FromProto(stored.Spec), nil
}

// resolve turns the spec's engines into instances, collecting the ones that cannot be resolved
// rather than failing on them.
//
// A server whose image is empty or whose kind this binary does not know is one server this
// node cannot run. Reporting it and carrying on is what keeps the MySQL on the machine working
// while somebody works out what happened to the PostgreSQL.
func (e *Engines) resolve(desired spec.Spec) ([]instance, []string) {
	wanted := make([]instance, 0, len(desired.Engines))
	problems := make([]string, 0, 2)
	for _, engine := range desired.Engines {
		built, err := instanceFor(e.stateDir, engine)
		if err != nil {
			problems = append(problems, err.Error())
			continue
		}
		wanted = append(wanted, built)
	}
	return wanted, problems
}

// ensure brings one server to what the spec asks for.
//
// Create when there is nothing, replace when the fingerprint has moved, start when it is
// stopped and leave it alone otherwise. Replacing is a stop, a remove and a create over the
// same data directory, which is what makes a version bump or a memory-limit change something
// the panel can publish rather than something an operator has to do by hand.
func (e *Engines) ensure(ctx context.Context, built instance, existing found) error {
	if err := built.Paths.ensure(); err != nil {
		return err
	}

	switch {
	case existing.ID == "":
		return e.create(ctx, built)

	case existing.Fingerprint != built.Fingerprint:
		e.log.Info("replacing a database server whose configuration has changed",
			slog.String("instance", built.ID),
			slog.String("kind", string(built.Kind)),
			slog.String("container", existing.ID))
		if err := e.remove(ctx, existing.ID); err != nil {
			return err
		}
		return e.create(ctx, built)

	case !existing.Running:
		if _, err := e.engine.ContainerStart(ctx, existing.ID, client.ContainerStartOptions{}); err != nil {
			return fmt.Errorf("dbengine: start the %s server %s (%s): %w",
				built.Kind, built.ID, existing.Status, err)
		}
		e.log.Info("started a database server that was not running",
			slog.String("instance", built.ID),
			slog.String("kind", string(built.Kind)),
			slog.String("was", existing.Status))
		return nil

	default:
		return nil
	}
}

// create makes the container and starts it.
func (e *Engines) create(ctx context.Context, built instance) error {
	if err := e.ensureImage(ctx, built); err != nil {
		return err
	}

	created, err := e.engine.ContainerCreate(ctx, client.ContainerCreateOptions{
		Name:       built.Name,
		Config:     configFor(built),
		HostConfig: hostConfigFor(built),
	})
	if err != nil {
		return fmt.Errorf("dbengine: create the %s server %s: %w", built.Kind, built.ID, err)
	}
	for _, warning := range created.Warnings {
		// The engine warns about what it accepted and could not honour - a swap limit with no
		// kernel support, an unsupported ulimit. Silence here is how a node applies less than
		// the panel asked for while reporting success.
		e.log.Warn("the engine accepted a database server with a warning",
			slog.String("instance", built.ID),
			slog.String("container", created.ID),
			slog.String("warning", warning))
	}

	if _, err := e.engine.ContainerStart(ctx, created.ID, client.ContainerStartOptions{}); err != nil {
		return fmt.Errorf("dbengine: start the %s server %s: %w", built.Kind, built.ID, err)
	}
	e.log.Info("created a database server",
		slog.String("instance", built.ID),
		slog.String("kind", string(built.Kind)),
		slog.String("image", built.Spec.Image),
		slog.String("container", created.ID),
		slog.Uint64("port", uint64(built.Plan.Port)))
	return nil
}

// ensureImage makes the server's image local, pulling it if it is not.
//
// Synchronous, unlike the workload pull next door. This loop is not the one systemd's watchdog
// is watching, and a server whose image is still downloading has no half-useful state to
// report in the meantime - it either exists or it does not.
func (e *Engines) ensureImage(ctx context.Context, built instance) error {
	if _, err := e.engine.ImageInspect(ctx, built.Spec.Image); err == nil {
		return nil
	} else if !isNotFound(err) {
		return fmt.Errorf("dbengine: look for the image %s: %w", built.Spec.Image, err)
	}

	e.log.Info("pulling a database server image",
		slog.String("instance", built.ID), slog.String("image", built.Spec.Image))

	pulling, cancel := context.WithTimeout(ctx, pullTimeout)
	defer cancel()

	response, err := e.engine.ImagePull(pulling, built.Spec.Image, client.ImagePullOptions{})
	if err != nil {
		return fmt.Errorf("dbengine: pull the image %s: %w", built.Spec.Image, err)
	}
	defer response.Close()
	if err := response.Wait(pulling); err != nil {
		return fmt.Errorf("dbengine: pull the image %s: %w", built.Spec.Image, err)
	}
	return nil
}

// retire takes away a server the spec no longer asks for.
//
// The container goes; the data directory stays. Omission from the spec means the panel no
// longer wants this server running here, and that is not the same statement as a customer
// having agreed to lose their databases - a directory left behind can be collected
// deliberately, and one that is gone cannot be brought back.
func (e *Engines) retire(ctx context.Context, instanceID string, existing found) error {
	if err := e.remove(ctx, existing.ID); err != nil {
		return err
	}
	e.hardeningFailed(instanceID)
	e.log.Info("removed a database server that is no longer in the spec, and kept its data",
		slog.String("instance", instanceID),
		slog.String("container", existing.ID))
	return nil
}

// remove stops a container and deletes it, giving the server time to shut down cleanly first.
func (e *Engines) remove(ctx context.Context, containerID string) error {
	grace := int(stopGrace.Seconds())
	if _, err := e.engine.ContainerStop(ctx, containerID, client.ContainerStopOptions{
		Timeout: &grace,
	}); err != nil && !isNotFound(err) {
		return fmt.Errorf("dbengine: stop the container %s: %w", containerID, err)
	}
	if _, err := e.engine.ContainerRemove(ctx, containerID, client.ContainerRemoveOptions{
		// Not the volumes: the data directory is a bind mount the node owns and there is
		// nothing here for the engine to delete, which is worth saying explicitly given what
		// the alternative would take with it.
		RemoveVolumes: false,
	}); err != nil && !isNotFound(err) {
		return fmt.Errorf("dbengine: remove the container %s: %w", containerID, err)
	}
	return nil
}
