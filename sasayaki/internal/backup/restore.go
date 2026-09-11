package backup

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// One restore, from the command arriving to the answer going back.
//
// The order of the steps is the design, and it is chosen so that every way this can fail
// leaves the customer no worse off than they were:
//
//	1. download the archive and check it against its recorded checksum - before anything is
//	   stopped, moved or overwritten, so a corrupt archive costs a download and nothing else;
//	2. stop the workload, if the command asked for it;
//	3. take the rollback copy - a rename for a volume, a dump for a database;
//	4. put the data back, into a tree assembled elsewhere and moved into place in one rename;
//	5. start the workload again.
//
// A failure at step 4 undoes step 3. A failure anywhere undoes step 2. The rollback copy from
// step 3 is kept after a success, because "the restore worked" and "that was the right restore
// point" are different questions and only the customer can answer the second.
//
// A dry run stops after step 1 and then restores beside the live data instead of over it: a
// volume into a sibling directory, a database under a suffixed name. Nothing is stopped and
// nothing is moved, which is what makes rehearsing a restore cheap enough that people do it -
// and a backup nobody has ever restored is not a backup (design section 8.3).

// RestoreBackup puts one restore point back.
func (r *Runner) RestoreBackup(ctx context.Context, request *wisperpb.RestoreBackup) (*wisperpb.RestoreCompleted, error) {
	if err := validateRestore(request); err != nil {
		return nil, err
	}
	restoreID := request.GetRestoreId()

	unlock, err := r.lockSubject(ctx, request.GetSubjectId())
	if err != nil {
		return nil, err
	}
	defer unlock()

	startedAt := r.now()
	err = r.store.BeginRestore(ctx, state.RestoreRun{
		RestoreID:      restoreID,
		RestorePointID: request.GetRestorePointId(),
		Kind:           request.GetKind(),
		SubjectID:      request.GetSubjectId(),
		WorkloadID:     request.GetWorkloadId(),
		DryRun:         request.GetDryRun(),
		StopWorkload:   request.GetStopWorkload(),
		StartedAt:      startedAt,
		Detail:         "starting",
	})
	if err != nil {
		replayed, resumeErr := r.resumeRestore(ctx, restoreID, err)
		if resumeErr != nil {
			return nil, resumeErr
		}
		return replayed, nil
	}

	timeout := timeoutFor(request.GetTimeoutSeconds())
	deadline, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	completed := r.putBack(deadline, request, startedAt)

	if err := r.store.FinishRestore(context.WithoutCancel(ctx), restoreID, completed, r.now()); err != nil {
		r.log.Error("could not record the outcome of a restore",
			slog.String("restore", restoreID), slog.String("error", err.Error()))
	}

	r.log.Info("restore finished",
		slog.String("restore", restoreID),
		slog.String("subject", request.GetSubjectId()),
		slog.Bool("dry_run", request.GetDryRun()),
		slog.Bool("ok", completed.GetSuccess()),
		slog.String("detail", completed.GetDetail()))
	return completed, nil
}

// putBack is the stage machine. Like perform, it never returns an error: a restore that could
// not be done is the customer's information, not a broken RPC.
func (r *Runner) putBack(ctx context.Context, request *wisperpb.RestoreBackup, startedAt time.Time) *wisperpb.RestoreCompleted {
	restoreID := request.GetRestoreId()
	extension := extensionFor(request.GetKind())
	outcome := restoreReport{restoreID: restoreID, startedAt: startedAt}

	source, _, err := r.resolveDestination(request.GetSource())
	if err != nil {
		return outcome.failed(r.now(), err.Error())
	}

	r.note(ctx, restoreID, "downloading the archive and checking it")
	archive, err := r.fetch(ctx, source, request.GetLocation(), restoreID, extension)
	if err != nil {
		// Nothing has been touched. That is the sentence worth putting in front of a customer
		// whose restore just failed, so it is in the detail rather than only in the log.
		return outcome.failed(r.now(), fmt.Sprintf(
			"%v - nothing was stopped and nothing was overwritten", err))
	}
	defer func() {
		if err := discardStaged(r.stateDir, restoreID, extension); err != nil {
			r.log.Warn("could not remove a downloaded archive after restoring it",
				slog.String("restore", restoreID), slog.String("error", err.Error()))
		}
	}()

	holding, err := r.stopFor(ctx, request)
	if err != nil {
		return outcome.failed(r.now(), err.Error())
	}

	var detail string
	if request.GetKind() == wisperpb.BackupTargetKind_BACKUP_TARGET_KIND_DATABASE {
		detail, err = r.restoreDatabase(ctx, request, archive, &outcome)
	} else {
		detail, err = r.restoreVolume(ctx, request, archive, &outcome)
	}

	// Before the result is built, not after: workload_restarted is a field in that result, and
	// a restore that started the application again and then reported that it had not would
	// send somebody to log in and check.
	holding.start(ctx, &outcome)

	if err != nil {
		return outcome.failed(r.now(), err.Error())
	}
	return outcome.succeeded(r.now(), detail)
}

// note records what a running restore is doing, for the screen somebody is watching while a
// forty-gigabyte volume comes back.
func (r *Runner) note(ctx context.Context, restoreID, detail string) {
	if err := r.store.RecordRestoreProgress(context.WithoutCancel(ctx), restoreID, r.now(), detail); err != nil {
		r.log.Warn("could not record the progress of a restore",
			slog.String("restore", restoreID), slog.String("error", err.Error()))
	}
}

// stoppedWorkload is an application that was stopped for the duration of a restore.
type stoppedWorkload struct {
	runner     *Runner
	workloadID string
	stopped    bool
	done       bool
}

// stopFor stops the workload when the command asked for it.
//
// Restoring underneath a running process gives it a filesystem that changed while it was not
// looking, which for anything holding an open file handle - which is every database, every
// search index and most caches - means a process that carries on writing the old file into the
// new directory. A dry run never stops anything: it writes somewhere else entirely.
func (r *Runner) stopFor(ctx context.Context, request *wisperpb.RestoreBackup) (*stoppedWorkload, error) {
	holding := &stoppedWorkload{runner: r, workloadID: request.GetWorkloadId()}
	if !request.GetStopWorkload() || request.GetDryRun() || holding.workloadID == "" {
		return holding, nil
	}

	running, err := r.workloads.Running(ctx, holding.workloadID)
	if err != nil {
		return nil, fmt.Errorf("backup: could not tell whether workload %s is running, so nothing "+
			"was restored over its data: %w", holding.workloadID, err)
	}
	if !running {
		return holding, nil
	}
	if err := r.workloads.Stop(ctx, holding.workloadID); err != nil {
		return nil, fmt.Errorf("backup: stop workload %s before restoring its volume: %w",
			holding.workloadID, err)
	}
	holding.stopped = true
	return holding, nil
}

// start puts the workload back, and records in the report whether it managed to.
//
// Through a context that cannot be cancelled, for the same reason unpausing is: the restore
// that most needs its application started again is the one whose deadline expired.
func (s *stoppedWorkload) start(ctx context.Context, outcome *restoreReport) {
	if s.done || !s.stopped {
		return
	}
	s.done = true

	if err := s.runner.workloads.Start(context.WithoutCancel(ctx), s.workloadID); err != nil {
		s.runner.log.Error("could not start a workload again after a restore; it is stopped and "+
			"needs an operator",
			slog.String("workload", s.workloadID), slog.String("error", err.Error()))
		return
	}
	outcome.restarted = true
}
