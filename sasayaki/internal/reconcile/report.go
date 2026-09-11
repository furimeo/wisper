package reconcile

import (
	"context"
	"log/slog"
	"sort"
	"time"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// What one pass saw, on its way to the panel and to the disk.
//
// A status batch is one snapshot of the whole node at one moment, which is why the pieces
// other packages own - engine sizes, cron history - are collected here and travel with the
// containers rather than being reported on their own. The panel compares a batch against
// what it published, and two halves of one machine observed a minute apart is a comparison
// that finds drift that was never there.

// observation is one pass's whole answer.
type observation struct {
	// What the node has converged to, which the panel uses for drift detection. Read back
	// from the disk after the pass recorded itself, so it is the same number a heartbeat
	// sent a second later would carry.
	Generation uint64
	Workloads  []spec.WorkloadStatus
	Routes     []spec.RouteStatus
	Databases  []spec.DatabaseStatus
	Cron       []spec.CronStatus
	Health     wisperpb.NodeHealth
	Detail     string
	// True when Docker did not answer, so the workload statuses are last-known rather than
	// observed. The most important boolean on the wire: the panel must not conclude that
	// anything missing has gone away (docs/contracts/node-spec.md section 5).
	Partial bool
	At      time.Time
}

// persist writes down what was observed, so a daemon that is killed and restarted reports
// history rather than starting again from "just created".
//
// Never called on a partial pass. Overwriting a container's real history with a row that
// says "unknown" would lose exactly the fact - how long this has been failing - that the
// outage makes valuable, and pruning against a workload set that could not be checked
// would drop rows for containers that are still there.
func (l *Loop) persist(ctx context.Context, desired spec.Spec, statuses []spec.WorkloadStatus, at time.Time) error {
	wire := make([]*wisperpb.WorkloadStatus, 0, len(statuses))
	for _, status := range statuses {
		wire = append(wire, status.ToProto())
	}
	if err := l.store.SaveWorkloadStatuses(ctx, wire, at); err != nil {
		return err
	}

	// The bookkeeping half of "omission is deletion": the containers are already gone by
	// the time this runs, and a row left behind would be a workload the panel is told
	// about and nothing on the machine answers for.
	if _, err := l.store.PruneWorkloadStatuses(ctx, desired.WorkloadIDs()); err != nil {
		return err
	}
	return nil
}

// storedStatuses is what the node last observed, by workload id.
//
// A read failure is logged and answered with an empty map rather than an error. Every
// caller is already on a path where something has gone wrong, and none of them can do
// anything useful with a second failure except lose the first one.
func (l *Loop) storedStatuses(ctx context.Context) map[string]spec.WorkloadStatus {
	observed, err := l.store.WorkloadStatuses(ctx)
	if err != nil {
		l.log.Warn("could not read the last observed statuses", slog.String("error", err.Error()))
		return map[string]spec.WorkloadStatus{}
	}

	previous := make(map[string]spec.WorkloadStatus, len(observed))
	for _, entry := range observed {
		status := spec.WorkloadStatusFromProto(entry.Status)
		previous[status.WorkloadID] = status
	}
	return previous
}

// lastKnown is what the node reports when Docker could not be asked.
//
// Every workload in the spec appears: the one the node has seen before appears exactly as
// it was last observed, and the one it has not appears as UNKNOWN. Reporting last-known
// beats reporting nothing, because an empty batch would tell the panel that every workload
// on this node had gone (AGENTS.md section 4.5).
func (l *Loop) lastKnown(ctx context.Context, desired spec.Spec, detail string, at time.Time) []spec.WorkloadStatus {
	previous := l.storedStatuses(ctx)

	statuses := make([]spec.WorkloadStatus, 0, len(desired.Workloads))
	for _, workload := range desired.Workloads {
		if status, known := previous[workload.ID]; known {
			statuses = append(statuses, status)
			continue
		}
		statuses = append(statuses, unknownStatus(workload, detail, at))
	}
	return statuses
}

// everythingKnown is lastKnown for the pass that could not read the spec at all.
//
// There is no desired list to walk, so the node reports what it has: the statuses on disk,
// in workload id order, marked partial. Saying nothing would be read as an empty machine.
func (l *Loop) everythingKnown(ctx context.Context) []spec.WorkloadStatus {
	previous := l.storedStatuses(ctx)

	ids := make([]string, 0, len(previous))
	for id := range previous {
		ids = append(ids, id)
	}
	sort.Strings(ids)

	statuses := make([]spec.WorkloadStatus, 0, len(ids))
	for _, id := range ids {
		statuses = append(statuses, previous[id])
	}
	return statuses
}

// collectDatabases asks the engine layer for the sizes that go in this batch.
//
// A failure here is logged and nothing is reported, which is deliberate: the panel writes
// only the rows it receives, so an engine that could not be measured leaves the previous
// figures in place. Sending zeroes would show every customer as using nothing.
func (l *Loop) collectDatabases(ctx context.Context) []spec.DatabaseStatus {
	statuses, err := l.databases.Statuses(ctx)
	if err != nil {
		l.log.Warn("could not measure the managed databases", slog.String("error", err.Error()))
		return nil
	}
	return statuses
}

// collectCron turns the node's own cron history into statuses.
//
// The schedule is evaluated here, not on the panel, so this is the only place the panel
// can learn that last night's job ran, failed or was skipped because the previous one had
// not finished.
func (l *Loop) collectCron(ctx context.Context) []spec.CronStatus {
	runs, err := l.store.CronRuns(ctx)
	if err != nil {
		l.log.Warn("could not read the cron history", slog.String("error", err.Error()))
		return nil
	}

	statuses := make([]spec.CronStatus, 0, len(runs))
	for _, run := range runs {
		statuses = append(statuses, cronStatus(run))
	}
	return statuses
}

func cronStatus(run state.CronRun) spec.CronStatus {
	return spec.CronStatus{
		CronID:         run.CronID,
		LastRunAt:      run.LastRunAt,
		NextRunAt:      run.NextRunAt,
		LastExitCode:   run.LastExitCode,
		Running:        run.Running,
		LastRunSkipped: run.LastSkipped,
		LastError:      run.LastError,
	}
}

// report hands the batch to the panel.
//
// Best effort, and not part of whether the pass succeeded. A pass that converged the
// machine and could not reach the panel still converged the machine; the tunnel drops
// routinely, the next batch is fifteen seconds behind this one, and tearing anything down
// over it would turn a network blip into an outage (design section 5.2).
func (l *Loop) report(ctx context.Context, o observation) {
	batch := &wisperpb.StatusBatch{
		AppliedGeneration: o.Generation,
		ObservedAt:        timestamppb.New(o.At),
		Health:            o.Health,
		HealthDetail:      o.Detail,
		Partial:           o.Partial,
	}

	batch.Workloads = make([]*wisperpb.WorkloadStatus, 0, len(o.Workloads))
	for _, status := range o.Workloads {
		batch.Workloads = append(batch.Workloads, status.ToProto())
	}
	batch.Routes = make([]*wisperpb.RouteStatus, 0, len(o.Routes))
	for _, status := range o.Routes {
		batch.Routes = append(batch.Routes, status.ToProto())
	}
	batch.Databases = make([]*wisperpb.DatabaseStatus, 0, len(o.Databases))
	for _, status := range o.Databases {
		batch.Databases = append(batch.Databases, status.ToProto())
	}
	batch.Cron = make([]*wisperpb.CronStatus, 0, len(o.Cron))
	for _, status := range o.Cron {
		batch.Cron = append(batch.Cron, status.ToProto())
	}

	acknowledgement, err := l.reporter.ReportStatus(ctx, batch)
	if err != nil {
		l.log.Info("could not report this pass to the panel",
			slog.Uint64("generation", o.Generation), slog.String("error", err.Error()))
		return
	}

	// The panel restarted, or has no record of this node's state. It has just been given
	// one; asking for another pass makes the next batch reflect the machine as it is now
	// rather than as it was when this one was assembled.
	if acknowledgement.GetRequestStatus() {
		l.ReconcileNow("the panel asked for a fresh status")
	}
}
