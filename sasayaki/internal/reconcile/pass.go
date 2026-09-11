package reconcile

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// One pass, start to finish.
//
// Read the document, look at the machine, work out the difference, close it, tell the
// panel. Everything hard is in the files this one calls; what is here is the order, and the
// order is the part that has to be right. In particular: nothing is removed before the
// container list has been obtained successfully, and nothing is pruned from the node's own
// bookkeeping on a pass that could not see the machine.

// pass converges the machine once and returns the interval the spec asked for.
//
// The error it returns is what the loop backs off on. It is deliberately not the same thing
// as "a workload is unhealthy": a container that will not start is reported and the pass
// carries on, because forty other workloads on the node are entitled to be converged.
func (l *Loop) pass(ctx context.Context, reason string) (time.Duration, error) {
	at := l.now()

	stored, err := l.store.LoadSpec(ctx)
	switch {
	case errors.Is(err, state.ErrNoSpec):
		// A freshly enrolled node, before its first ApplySpec. Nothing to converge and -
		// this is the part that matters - nothing to remove: an empty spec means "run
		// nothing", and no spec means "nobody has said yet".
		l.log.Debug("no spec has been received yet, so there is nothing to converge")
		return 0, nil
	case err != nil:
		return 0, l.blindPass(ctx, "the desired state on disk could not be read: "+err.Error(), at)
	}

	desired := spec.FromProto(stored.Spec)
	interval := desired.ReconcileInterval

	converged, err := l.store.Convergence(ctx)
	if err != nil {
		return interval, fmt.Errorf("read how far this node has converged: %w", err)
	}
	if desired.Generation < converged.AppliedGeneration {
		// Defensive: state.SaveSpec refuses to store a generation below the one on disk,
		// so reaching here means the database was replaced or restored underneath a
		// running daemon. Converging backwards would undo work the panel believes is done.
		l.log.Warn("ignoring a spec older than what this node has already applied",
			slog.Uint64("spec_generation", desired.Generation),
			slog.Uint64("applied_generation", converged.AppliedGeneration))
		return interval, nil
	}

	containers, err := l.runtime.Containers(ctx)
	if err != nil {
		return interval, l.engineUnreachable(ctx, desired, err, at)
	}
	l.engineRecovered(at)

	published, err := l.publishedReleases(ctx, desired)
	if err != nil {
		return interval, l.recordFailure(ctx, desired, err.Error(), at)
	}

	previous := l.storedStatuses(ctx)
	work := computePlan(desired, containers, published, previous, l.drained.Load())

	l.log.Debug("reconciling",
		slog.String("reason", reason),
		slog.Uint64("generation", desired.Generation),
		slog.Int("workloads", len(work.Decisions)),
		slog.Int("orphans", len(work.Orphans)))

	statuses, events, rebuilt, problems := l.converge(ctx, work, previous, at)

	if err := l.syncEdge(ctx, desired, rebuilt); err != nil {
		problems = append(problems, err.Error())
	}
	routes, err := l.routeStatuses(ctx, desired)
	if err != nil {
		problems = append(problems, err.Error())
	}

	if err := l.persist(ctx, desired, statuses, at); err != nil {
		problems = append(problems, err.Error())
	}

	detail := strings.Join(problems, "; ")
	if len(problems) == 0 {
		if err := l.store.MarkApplied(ctx, desired.Generation, at); err != nil {
			return interval, fmt.Errorf("record that generation %d was applied: %w", desired.Generation, err)
		}
	} else if err := l.store.MarkPassFailed(ctx, at, detail); err != nil {
		return interval, fmt.Errorf("record that a pass failed: %w", err)
	}

	health, healthDetail := l.health(problems, detail)
	l.report(ctx, observation{
		Generation: l.appliedGeneration(ctx, converged.AppliedGeneration),
		Workloads:  statuses,
		Routes:     routes,
		Databases:  l.collectDatabases(ctx),
		Cron:       l.collectCron(ctx),
		Health:     health,
		Detail:     healthDetail,
		At:         at,
	})
	for _, event := range events {
		l.reporter.Emit(event)
	}

	if len(problems) > 0 {
		return interval, errors.New(detail)
	}
	return interval, nil
}

// converge applies every decision and collects what to say about each one.
//
// One workload's failure is that workload's failure. The loop over the plan never stops
// early, because a node where one image will not pull is still a node whose other
// workloads have to be running.
func (l *Loop) converge(
	ctx context.Context,
	work plan,
	previous map[string]spec.WorkloadStatus,
	at time.Time,
) (statuses []spec.WorkloadStatus, events []*wisperpb.NodeEvent, rebuilt bool, problems []string) {
	statuses = make([]spec.WorkloadStatus, 0, len(work.Decisions))

	for _, d := range work.Decisions {
		result := l.apply(ctx, d)
		if result.Err != nil {
			problems = append(problems, result.Err.Error())
		}
		if result.Container.ID != d.Container.ID {
			// A container was made, replaced or taken away, so the edge may be holding an
			// address for a backend that no longer exists. Compared by id rather than by
			// which action was chosen, because a create that is still waiting on an image
			// has changed nothing the edge could care about.
			rebuilt = true
		}
		if d.Action != actionNone && result.Err == nil {
			l.log.Info("converged a workload",
				slog.String("workload", d.Workload.ID),
				slog.String("action", string(d.Action)),
				slog.String("detail", result.Message))
		}

		status := observe(result, previous[d.Workload.ID], at)
		statuses = append(statuses, status)
		events = append(events, transitionEvents(previous[d.Workload.ID], status, result.Container, at)...)
	}

	for _, container := range work.Orphans {
		if err := l.removeOrphan(ctx, container); err != nil {
			problems = append(problems, err.Error())
			continue
		}
		rebuilt = true
		l.log.Info("removed a container whose workload is no longer in the spec",
			slog.String("workload", container.WorkloadID),
			slog.String("container", container.ID))
	}

	for _, workloadID := range work.OrphanSites {
		if err := l.sites.Discard(ctx, workloadID); err != nil {
			problems = append(problems, fmt.Sprintf("discard the release tree of site %s: %v", workloadID, err))
			continue
		}
		rebuilt = true
		l.log.Info("removed the release tree of a site that is no longer in the spec",
			slog.String("workload", workloadID))
	}

	return statuses, events, rebuilt, problems
}

// publishedReleases asks each site which release it is currently serving.
//
// A local readlink per site, which is why it is allowed to fail the whole pass: if the
// node cannot read its own state directory, converging anything on top of it would be
// guesswork.
func (l *Loop) publishedReleases(ctx context.Context, desired spec.Spec) (map[string]string, error) {
	published := make(map[string]string)
	for _, workload := range desired.Workloads {
		if !workload.IsSite() {
			continue
		}
		release, err := l.sites.Published(ctx, workload.ID)
		if err != nil {
			return nil, fmt.Errorf("read which release site %s is serving: %w", workload.ID, err)
		}
		published[workload.ID] = release
	}
	return published, nil
}
