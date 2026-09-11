package daemon

import (
	"context"
	"log/slog"
	"strings"
	"sync/atomic"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/rpc"
	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/stats"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// "Is this node alive, and is it keeping up" - answered without asking anything that can
// hang.
//
// The rule this file exists to obey: a heartbeat must never block on Docker. A node whose
// engine has gone away is degraded, not dead, and it is still serving every container it
// had a minute ago; if its liveness signal waited on the same socket that is not answering,
// the panel would count the gaps, decide the node is gone and start placing its workloads
// somewhere else - turning a recoverable engine hiccup into a migration nobody asked for
// (AGENTS.md section 4.5).
//
// So nothing here touches the engine at all. The two sources are an atomic pointer the
// sampler publishes, which cannot block by construction, and one row of SQLite, which can
// in principle and is therefore read with a deadline and a remembered answer behind it.
// That row is where a Docker outage is learned about: the reconcile loop writes
// "docker unreachable since 12:04" into it, and this is what carries it to the panel
// (state/convergence.go, Convergence.LastError).

const (
	// convergenceBudget is how long the heartbeat will wait for the disk.
	//
	// The state database serialises everything onto one connection, so a heartbeat can
	// arrive behind a reconcile pass writing a batch of statuses. Two seconds is far longer
	// than that write has ever taken and far shorter than the interval the panel counts
	// gaps in; past it the previous generation is sent, which is at worst one heartbeat out
	// of date and always better than no heartbeat at all.
	convergenceBudget = 2 * time.Second
)

// convergenceReader is how far the reconcile loop has got, and why it stopped if it did.
type convergenceReader interface {
	Convergence(ctx context.Context) (state.Convergence, error)
}

// snapshots is the sampler's published view of the machine.
type snapshots interface {
	Snapshot() stats.Snapshot
}

// drainFlag is whether this node has been emptied on purpose (design section 7.7).
type drainFlag interface {
	Draining() bool
}

// nodeHeartbeat is the daemon's rpc.HeartbeatSource.
type nodeHeartbeat struct {
	convergence convergenceReader
	sampler     snapshots
	drain       drainFlag
	log         *slog.Logger

	// budget is how long the disk read may take. Zero means convergenceBudget; a test
	// shrinks it so that "a heartbeat still goes out when the state database is wedged" can
	// be proved without the test itself waiting two seconds.
	budget time.Duration

	// The last generation that was read successfully, so a heartbeat that could not reach
	// the disk reports the number from a moment ago instead of zero. Zero would tell the
	// panel this node has applied nothing and provoke a full spec resend on every beat.
	lastGeneration atomic.Uint64
}

var _ rpc.HeartbeatSource = (*nodeHeartbeat)(nil)

// Heartbeat assembles one frame. It never returns nil and never waits on the engine.
//
// sent_at is deliberately not set here: it belongs to the moment the frame leaves rather
// than to the moment these numbers were gathered, which is what makes it usable for
// measuring clock skew, and rpc stamps it on the way out (rpc/connect.go, beat).
func (h *nodeHeartbeat) Heartbeat(ctx context.Context) *wisperpb.Heartbeat {
	snapshot := h.sampler.Snapshot()
	generation, problems := h.generation(ctx)
	problems = append(problems, h.pressure(snapshot)...)

	health, detail := h.health(problems)
	return &wisperpb.Heartbeat{
		AppliedGeneration: generation,
		Health:            health,
		HealthDetail:      detail,
		Capacity:          snapshot.Capacity,
		RunningWorkloads:  snapshot.RunningWorkloads,
	}
}

// generation is what this node has converged to, plus anything the last pass complained
// about on the way.
func (h *nodeHeartbeat) generation(ctx context.Context) (uint64, []string) {
	budget := h.budget
	if budget <= 0 {
		budget = convergenceBudget
	}
	bounded, cancel := context.WithTimeout(ctx, budget)
	defer cancel()

	converged, err := h.convergence.Convergence(bounded)
	if err != nil {
		h.log.Warn("could not read how far this node has converged; the heartbeat carries the "+
			"last number that was read",
			slog.String("error", err.Error()))
		return h.lastGeneration.Load(), []string{"the node's own state database did not answer: " + err.Error()}
	}

	h.lastGeneration.Store(converged.AppliedGeneration)
	if converged.LastError == "" {
		return converged.AppliedGeneration, nil
	}
	// Written by the reconcile loop, already phrased for a person - "docker unreachable
	// since 2026-03-04T12:04:11Z: cannot connect to the docker daemon". It is carried
	// verbatim, because rewriting it here would mean two places deciding how a node
	// describes its own failures.
	return converged.AppliedGeneration, []string{converged.LastError}
}

// pressure is the disk, which is the one resource whose exhaustion takes every customer on
// the node down at once.
//
// Critical and warning both count as degraded. An allocation ceiling deliberately does not:
// a node that has promised ninety per cent of its CPU is full, not broken, and
// Capacity.accepting_workloads already tells the panel to stop placing there.
func (h *nodeHeartbeat) pressure(snapshot stats.Snapshot) []string {
	switch snapshot.Admission.Disk {
	case stats.PressureCritical:
		return []string{"this node's disk is past the mark at which it stops accepting new bytes: " +
			snapshot.Admission.Refusal()}
	case stats.PressureWarning:
		return []string{"this node's disk is filling up"}
	default:
		return nil
	}
}

// health folds the problems into the enum, with the same precedence the reconcile loop uses
// for a status batch: degraded outranks draining.
//
// A node that is being emptied on purpose and a node that is also failing to do it need
// different things from an operator, and the second one is the one nobody would otherwise
// notice (reconcile/degraded.go, health).
func (h *nodeHeartbeat) health(problems []string) (wisperpb.NodeHealth, string) {
	draining := h.drain.Draining()
	detail := strings.Join(problems, "; ")

	switch {
	case len(problems) > 0 && draining:
		return wisperpb.NodeHealth_NODE_HEALTH_DEGRADED, "draining; " + detail
	case len(problems) > 0:
		return wisperpb.NodeHealth_NODE_HEALTH_DEGRADED, detail
	case draining:
		return wisperpb.NodeHealth_NODE_HEALTH_DRAINING,
			"this node has been drained and is not accepting new workloads"
	default:
		return wisperpb.NodeHealth_NODE_HEALTH_HEALTHY, ""
	}
}
