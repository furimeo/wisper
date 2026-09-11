package runtime

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/moby/moby/api/types/container"
	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// Containers is every workload container this daemon manages on this node.
//
// Two label filters, and the second one matters as much as the first. wisper.managed
// keeps the loop away from containers somebody else put on the machine - a node is
// allowed to run things that have nothing to do with this platform, and eating them would
// be an unforgivable first impression. wisper.workload keeps it away from this daemon's
// *own* other containers: the shared PostgreSQL engine, a build in progress. A loop that
// removed everything it did not recognise would take out the database on its first pass.
//
// An error here means the engine could not be asked. It never means the machine is empty,
// and the reconcile loop is written to treat it that way: nothing is deleted on this path
// (AGENTS.md section 4.5).
func (d *Docker) Containers(ctx context.Context) ([]reconcile.Container, error) {
	listed, err := d.api.ContainerList(ctx, client.ContainerListOptions{
		// Including the stopped ones. A container the panel asked to stop is still this
		// node's to look after, and a crashed one has an exit code the customer is
		// waiting to be shown.
		All: true,
		Filters: make(client.Filters).
			Add("label", reconcile.LabelManaged+"="+reconcile.LabelManagedValue).
			Add("label", reconcile.LabelWorkload),
	})
	if err != nil {
		return nil, fmt.Errorf("runtime: list this node's containers: %w", err)
	}

	containers := make([]reconcile.Container, 0, len(listed.Items))
	for _, summary := range listed.Items {
		inspected, err := d.api.ContainerInspect(ctx, summary.ID, client.ContainerInspectOptions{})
		if notFound(err) {
			// Removed between the list and the inspect. It is genuinely gone, which is
			// the one case where absence is a fact rather than a failed question.
			continue
		}
		if err != nil {
			return nil, fmt.Errorf("runtime: inspect the container %s: %w", summary.ID, err)
		}
		containers = append(containers, containerFrom(inspected.Container, summary.Status))
	}
	return containers, nil
}

// ContainerFor finds the container belonging to one workload.
//
// The lookup every other package needs: the terminal is asked to open a shell in a
// workload, the log feed is asked to tail one, and neither is told a container id. It is
// answered from the engine rather than from a cache, because a container that was
// recreated between two reconcile passes has a new id and a stale one would open a
// terminal into a container that no longer exists.
//
// A false second return means there is no such container, which is a fact. An error means
// the engine could not be asked, which is not - and the difference matters to the caller,
// because one is "your service is not running" and the other is "come back in a moment".
func (d *Docker) ContainerFor(ctx context.Context, workloadID string) (reconcile.Container, bool, error) {
	if err := checkIdentifier("workload id", workloadID); err != nil {
		return reconcile.Container{}, false, err
	}
	listed, err := d.api.ContainerList(ctx, client.ContainerListOptions{
		All: true,
		Filters: make(client.Filters).
			Add("label", reconcile.LabelManaged+"="+reconcile.LabelManagedValue).
			Add("label", reconcile.LabelWorkload+"="+workloadID),
	})
	if err != nil {
		return reconcile.Container{}, false, fmt.Errorf("runtime: look for the container of "+
			"workload %s: %w", workloadID, err)
	}
	if len(listed.Items) == 0 {
		return reconcile.Container{}, false, nil
	}

	inspected, err := d.api.ContainerInspect(ctx, listed.Items[0].ID, client.ContainerInspectOptions{})
	if notFound(err) {
		return reconcile.Container{}, false, nil
	}
	if err != nil {
		return reconcile.Container{}, false, fmt.Errorf("runtime: inspect the container %s: %w",
			listed.Items[0].ID, err)
	}
	return containerFrom(inspected.Container, listed.Items[0].Status), true, nil
}

// describe is one container, read back after it was created or started.
func (d *Docker) describe(ctx context.Context, containerID string) (reconcile.Container, error) {
	inspected, err := d.api.ContainerInspect(ctx, containerID, client.ContainerInspectOptions{})
	if err != nil {
		return reconcile.Container{}, fmt.Errorf("runtime: inspect the container %s: %w", containerID, err)
	}
	return containerFrom(inspected.Container, ""), nil
}

// containerFrom flattens the engine's inspect result into what convergence needs.
//
// status is the summary line from a list - "Exited (137) 4 seconds ago" - which the
// inspect result does not carry and which is the most useful single sentence the engine
// produces. When there is none, one is built from the state, because a status field the
// panel shows as empty is a worse answer than a short one.
func containerFrom(inspected container.InspectResponse, status string) reconcile.Container {
	labels := map[string]string{}
	image := ""
	if inspected.Config != nil {
		if inspected.Config.Labels != nil {
			labels = inspected.Config.Labels
		}
		image = inspected.Config.Image
	}

	found := reconcile.Container{
		ID:           inspected.ID,
		WorkloadID:   labels[reconcile.LabelWorkload],
		Name:         strings.TrimPrefix(inspected.Name, "/"),
		Fingerprint:  labels[reconcile.LabelFingerprint],
		Image:        image,
		ImageDigest:  imageDigestOf(labels, inspected.Image),
		Health:       reconcile.HealthNone,
		RestartCount: int32(inspected.RestartCount),
		Runtime:      runtimeOf(inspected.HostConfig),
		Status:       status,
	}

	if state := inspected.State; state != nil {
		found.Running = state.Running
		found.ExitCode = int32(state.ExitCode)
		found.OOMKilled = state.OOMKilled
		found.StartedAt = instantFrom(state.StartedAt)
		found.FinishedAt = instantFrom(state.FinishedAt)
		found.Health = healthOf(state.Health)
		if found.Status == "" {
			found.Status = describeState(*state)
		}
	}
	return found
}

// imageDigestOf is what the container was created from.
//
// The label first, because it was resolved at create time against the reference the panel
// asked for and is therefore comparable with what the panel stored. The engine's own
// Image field - the content-addressable config digest - is the fallback for a container
// created before this label existed. Either way it changes when the bytes change, which
// is what makes a tag that moved visible instead of being a mystery restart.
func imageDigestOf(labels map[string]string, imageID string) string {
	if digest := labels[labelImageDigest]; digest != "" {
		return digest
	}
	return imageID
}

// runtimeOf is the runtime a container is really running under, not the one asked for. An
// empty value is the engine's own default, which is runc everywhere this daemon runs.
func runtimeOf(host *container.HostConfig) spec.Runtime {
	if host != nil && host.Runtime == "runsc" {
		return spec.RuntimeRunsc
	}
	return spec.RuntimeRunc
}

// healthOf reads Docker's own health check result.
//
// Four states rather than a bool, because "no check configured" and "the check has not
// run yet" are both not-unhealthy: reporting either as a failure would show every freshly
// started container as broken for its first thirty seconds.
func healthOf(health *container.Health) reconcile.ContainerHealth {
	if health == nil {
		return reconcile.HealthNone
	}
	switch health.Status {
	case container.Starting:
		return reconcile.HealthStarting
	case container.Healthy:
		return reconcile.HealthPassing
	case container.Unhealthy:
		return reconcile.HealthFailing
	default:
		return reconcile.HealthNone
	}
}

// describeState is a sentence for a person, for when there is no summary line to use.
func describeState(state container.State) string {
	if state.Error != "" {
		return fmt.Sprintf("%s: %s", state.Status, state.Error)
	}
	if state.OOMKilled {
		return fmt.Sprintf("%s (killed for running out of memory)", state.Status)
	}
	if !state.Running && state.Status == container.StateExited {
		return fmt.Sprintf("exited (%d)", state.ExitCode)
	}
	return string(state.Status)
}

// instantFrom parses one of the engine's RFC 3339 timestamps.
//
// The engine writes "0001-01-01T00:00:00Z" for "has not happened", which parses perfectly
// well into the year 1 and would be shown to a customer as a start time. Both that and an
// unparseable value become the zero time, which is the one thing every caller already
// knows how to render as "never".
func instantFrom(value string) time.Time {
	if value == "" {
		return time.Time{}
	}
	parsed, err := time.Parse(time.RFC3339Nano, value)
	if err != nil || parsed.Year() <= 1 {
		return time.Time{}
	}
	return parsed.UTC()
}
