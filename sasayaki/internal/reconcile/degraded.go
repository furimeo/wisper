package reconcile

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// What the node says and does when it cannot converge.
//
// Three ways a pass can be blind - the engine did not answer, the spec on disk could not be
// read, the node's own state directory refused a read - and one answer to all three:
//
//	change nothing, report what was last known, mark the batch partial, say why, retry.
//
// The temptation each time is to treat an absent answer as an empty machine. Every removal
// this package performs is deliberately unreachable from anything in this file.

// engineUnreachable is the safety path, and the most important function in this package.
//
// Docker did not answer. Every container on this machine is almost certainly still running
// exactly as it was a second ago, and the node's job is to say so and wait. Nothing is
// removed, nothing is pruned, and no status on disk is overwritten - the rows already there
// are more truthful than anything this pass could write, and they are what the panel is
// given, marked partial so it draws no conclusions from what is missing.
func (l *Loop) engineUnreachable(ctx context.Context, desired spec.Spec, cause error, at time.Time) error {
	if l.dockerDownSince.IsZero() {
		l.dockerDownSince = at
		l.reporter.Emit(&wisperpb.NodeEvent{
			Kind:     wisperpb.NodeEventKind_NODE_EVENT_KIND_DOCKER_UNREACHABLE,
			Severity: wisperpb.EventSeverity_EVENT_SEVERITY_CRITICAL,
			Detail:   cause.Error(),
			At:       timestamppb.New(at),
		})
	}
	detail := fmt.Sprintf("docker unreachable since %s: %v",
		l.dockerDownSince.UTC().Format(time.RFC3339), cause)

	l.log.Error("the container engine did not answer, so nothing on this node will be changed",
		slog.String("error", cause.Error()),
		slog.String("meaning", "every container is left exactly as it is: not being able to see one "+
			"is not the same as it not existing"))

	if err := l.store.MarkPassFailed(ctx, at, detail); err != nil {
		l.log.Warn("could not record the failed pass", slog.String("error", err.Error()))
	}

	// The edge is in this process and does not need Docker to answer for a hostname, so
	// its statuses are still real and worth sending.
	routes, err := l.routeStatuses(ctx, desired)
	if err != nil {
		l.log.Warn("could not read the edge's route statuses", slog.String("error", err.Error()))
	}

	l.report(ctx, observation{
		Generation: l.appliedGeneration(ctx, 0),
		Workloads:  l.lastKnown(ctx, desired, detail, at),
		Routes:     routes,
		Cron:       l.collectCron(ctx),
		Health:     wisperpb.NodeHealth_NODE_HEALTH_DEGRADED,
		Detail:     detail,
		Partial:    true,
		At:         at,
	})

	return fmt.Errorf("list the containers on this node: %w", cause)
}

// engineRecovered says so, once, when Docker starts answering again.
func (l *Loop) engineRecovered(at time.Time) {
	if l.dockerDownSince.IsZero() {
		return
	}
	away := at.Sub(l.dockerDownSince)
	l.dockerDownSince = time.Time{}

	l.log.Info("the container engine is answering again", slog.Duration("was_away_for", away))
	l.reporter.Emit(&wisperpb.NodeEvent{
		Kind:     wisperpb.NodeEventKind_NODE_EVENT_KIND_DOCKER_RECOVERED,
		Severity: wisperpb.EventSeverity_EVENT_SEVERITY_INFO,
		Detail:   "docker answered again after " + away.String(),
		At:       timestamppb.New(at),
	})
}

// blindPass is the pass that could not read the spec.
//
// The same discipline as a Docker outage, for the same reason: with no desired state there
// is nothing to compare against, so there is nothing that can honestly be called an orphan.
// The node reports what it last saw, marked partial, and waits for the panel to resend.
func (l *Loop) blindPass(ctx context.Context, detail string, at time.Time) error {
	l.log.Error("this pass changed nothing", slog.String("reason", detail))

	if err := l.store.MarkPassFailed(ctx, at, detail); err != nil {
		l.log.Warn("could not record the failed pass", slog.String("error", err.Error()))
	}
	l.report(ctx, observation{
		Generation: l.appliedGeneration(ctx, 0),
		Workloads:  l.everythingKnown(ctx),
		Cron:       l.collectCron(ctx),
		Health:     wisperpb.NodeHealth_NODE_HEALTH_DEGRADED,
		Detail:     detail,
		Partial:    true,
		At:         at,
	})
	return errors.New(detail)
}

// recordFailure reports a pass that got far enough to know the spec but not far enough to
// converge it.
func (l *Loop) recordFailure(ctx context.Context, desired spec.Spec, detail string, at time.Time) error {
	l.log.Error("this pass could not converge the node", slog.String("reason", detail))

	if err := l.store.MarkPassFailed(ctx, at, detail); err != nil {
		l.log.Warn("could not record the failed pass", slog.String("error", err.Error()))
	}
	l.report(ctx, observation{
		Generation: l.appliedGeneration(ctx, 0),
		Workloads:  l.lastKnown(ctx, desired, detail, at),
		Cron:       l.collectCron(ctx),
		Health:     wisperpb.NodeHealth_NODE_HEALTH_DEGRADED,
		Detail:     detail,
		Partial:    true,
		At:         at,
	})
	return errors.New(detail)
}

// health is what the node says about itself in this batch.
//
// Degraded outranks draining. A node that is being emptied on purpose and a node that is
// also failing to do it need different things from an operator, and the second one is the
// one nobody would otherwise notice.
func (l *Loop) health(problems []string, detail string) (wisperpb.NodeHealth, string) {
	draining := l.drained.Load()
	switch {
	case len(problems) > 0 && draining:
		return wisperpb.NodeHealth_NODE_HEALTH_DEGRADED, "draining; " + detail
	case len(problems) > 0:
		return wisperpb.NodeHealth_NODE_HEALTH_DEGRADED, detail
	case draining:
		return wisperpb.NodeHealth_NODE_HEALTH_DRAINING, "this node has been drained and is not accepting new workloads"
	default:
		return wisperpb.NodeHealth_NODE_HEALTH_HEALTHY, ""
	}
}

// appliedGeneration is the number that goes in the batch, read back from disk so it is the
// same one a heartbeat sent a moment later would carry. fallback is used when the record
// cannot be read, which is not worth failing a report over.
func (l *Loop) appliedGeneration(ctx context.Context, fallback uint64) uint64 {
	converged, err := l.store.Convergence(ctx)
	if err != nil {
		l.log.Warn("could not read the applied generation", slog.String("error", err.Error()))
		return fallback
	}
	return converged.AppliedGeneration
}
