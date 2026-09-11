package reconcile

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Emptying a node on purpose (design section 7.7).
//
// The rule that shapes everything here: anything holding a volume is listed and never
// moved. Moving it would mean moving data, and a platform that migrates a customer's disk
// because an administrator typed "drain" is a platform that loses it. So a drain evacuates
// what is cheap to rebuild elsewhere, names what is not, and stops.
//
// It is also two commands in one. With evacuate_stateless false it is a survey: it reads
// the machine, says what would happen and changes nothing, which is how an administrator
// finds out what a drain would cost before committing to it.

// Drain stops accepting new workloads and, when asked, stops the ones that can move.
func (l *Loop) Drain(ctx context.Context, request *wisperpb.DrainNode) (*wisperpb.DrainReport, error) {
	stored, err := l.store.LoadSpec(ctx)
	if errors.Is(err, state.ErrNoSpec) {
		// A node holding nothing is already drained.
		if request.GetEvacuateStateless() {
			l.drained.Store(true)
		}
		return &wisperpb.DrainReport{Complete: true}, nil
	}
	if err != nil {
		return nil, fmt.Errorf("read the desired state before draining: %w", err)
	}

	// Deliberately not a partial view: a drain that could not see the machine would report
	// an empty node, and an administrator would remove it believing nothing was left.
	containers, err := l.runtime.Containers(ctx)
	if err != nil {
		return nil, fmt.Errorf("list the containers before draining: %w", err)
	}
	running := make(map[string]Container, len(containers))
	for _, container := range containers {
		if container.WorkloadID != "" {
			running[container.WorkloadID] = container
		}
	}

	desired := spec.FromProto(stored.Spec)
	report := &wisperpb.DrainReport{}
	var problems []string

	for _, workload := range desired.Workloads {
		if holdsVolume(workload) {
			// Named, never moved. The administrator decides what happens to the data, and
			// the panel shows this list next to the button they pressed.
			report.PinnedWorkloads = append(report.PinnedWorkloads, workload.ID)
			continue
		}
		report.EvacuatedWorkloads = append(report.EvacuatedWorkloads, workload.ID)

		if !request.GetEvacuateStateless() {
			continue
		}
		container, exists := running[workload.ID]
		if !exists || !container.Running {
			// A site has no process, and a workload that is already stopped needs nothing
			// doing to it. Both are evacuated as far as the panel is concerned: it is free
			// to place them somewhere else.
			continue
		}
		if err := l.runtime.Stop(ctx, container.ID, grace(workload)); err != nil {
			problems = append(problems, fmt.Sprintf("stop %s: %v", workload.ID, err))
		}
	}

	if !request.GetEvacuateStateless() {
		// A survey changes nothing, including whether this node is draining. Complete says
		// whether a real drain would have anything left to do.
		report.Complete = len(report.EvacuatedWorkloads) == 0
		return report, nil
	}

	// Set even when a stop failed. The evacuation has begun, and a reconcile pass that
	// restarted the containers this command has just stopped would undo it fifteen seconds
	// later (diff.go, decide).
	l.drained.Store(true)
	l.ReconcileNow("a drain changed what this node should be running")

	if len(problems) > 0 {
		return nil, fmt.Errorf("drained partially, %s: run drain again once the reason is fixed",
			strings.Join(problems, "; "))
	}

	report.Complete = true
	l.log.Warn("this node has been drained",
		slog.String("reason", request.GetReason()),
		slog.Int("evacuated", len(report.EvacuatedWorkloads)),
		slog.Int("pinned_by_a_volume", len(report.PinnedWorkloads)))

	return report, nil
}
