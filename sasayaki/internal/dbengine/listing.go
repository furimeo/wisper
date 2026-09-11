package dbengine

import (
	"context"
	"errors"
	"fmt"

	cerrdefs "github.com/containerd/errdefs"
	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
)

// What the engine says is on this node.
//
// One file, because it is one question - "which database servers are here" - and because the
// answer to it has a rule attached that is easy to lose in a longer file: an error from
// ContainerList means the engine could not be asked, never that the machine is empty. Nothing
// in this package deletes anything on that path (AGENTS.md section 4.5).

// found is one of this package's containers as convergence needs to see it.
type found struct {
	ID          string
	Fingerprint string
	Running     bool
	// Status is the engine's own sentence - "Exited (1) 4 seconds ago" - which is what an
	// operator reads when a server will not come up.
	Status string
	// Networks is which tenant bridges it has already joined.
	Networks map[string]bool
}

// containers is every database server container on this node, by instance id.
//
// Filtered to this package's own label, which is also why the reconcile loop never sees them:
// it lists containers carrying wisper.workload, and nothing here has one.
func (e *Engines) containers(ctx context.Context) (map[string]found, error) {
	listed, err := e.engine.ContainerList(ctx, client.ContainerListOptions{
		// Including the stopped ones. A server that exited has an error somebody needs, and
		// a server an operator stopped is still this node's to look after.
		All: true,
		Filters: make(client.Filters).
			Add("label", reconcile.LabelManaged+"="+reconcile.LabelManagedValue).
			Add("label", labelInstance),
	})
	if err != nil {
		return nil, fmt.Errorf("dbengine: list this node's database servers: %w", err)
	}

	servers := make(map[string]found, len(listed.Items))
	for _, summary := range listed.Items {
		inspected, err := e.engine.ContainerInspect(ctx, summary.ID, client.ContainerInspectOptions{})
		if err != nil {
			if isNotFound(err) {
				// Removed between the list and the inspect, which is the one case where
				// absence is a fact rather than a question that failed.
				continue
			}
			return nil, fmt.Errorf("dbengine: inspect the container %s: %w", summary.ID, err)
		}

		details := inspected.Container
		labels := map[string]string{}
		if details.Config != nil && details.Config.Labels != nil {
			labels = details.Config.Labels
		}
		instanceID := labels[labelInstance]
		if instanceID == "" {
			continue
		}

		server := found{
			ID:          details.ID,
			Fingerprint: labels[labelFingerprint],
			Status:      summary.Status,
			Networks:    make(map[string]bool),
		}
		if details.State != nil {
			server.Running = details.State.Running
			if server.Status == "" {
				server.Status = string(details.State.Status)
			}
		}
		if details.NetworkSettings != nil {
			for name := range details.NetworkSettings.Networks {
				server.Networks[name] = true
			}
		}
		servers[instanceID] = server
	}
	return servers, nil
}

// isNotFound reports whether the engine said "no such thing".
//
// Worth its own function because the answer changes what a caller does more than any other
// error does: a container that is not there is a fact to act on, while every other failure
// means the engine could not be asked and nothing may be concluded from it.
func isNotFound(err error) bool {
	return errors.Is(err, cerrdefs.ErrNotFound)
}
