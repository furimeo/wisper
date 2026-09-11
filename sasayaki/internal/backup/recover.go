package backup

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Finishing what a killed daemon left half-done.
//
// Crash-only means there is no cleanup on the way out, so there has to be some on the way in.
// Called by the composition root before the control stream opens, so that a node is never
// answering commands while a customer's application is still frozen from last night.
//
// Two very different jobs, and the difference is the point.
//
// An unfinished backup is left resumable. Its row stays open, its staged archive stays on
// disk, and the parts already at the object store stay where they are; the panel resends the
// command with the same id and the run carries on from where it stopped. The one thing that
// cannot wait is the pause: a backup killed during QUIESCE may have left the workload frozen,
// and a frozen application does not recover by itself.
//
// An unfinished restore is closed out as a failure. A restore is deliberately not resumed -
// a half-written volume is not a state a second attempt can infer - so leaving the row open
// would mean the panel's retry is refused forever. What is recovered instead is the data: if
// the volume was moved aside and the new one never arrived, the original goes back.

// Recover puts the node back into a state where every command can be answered.
func (r *Runner) Recover(ctx context.Context) error {
	var failures []error
	if err := r.thawAbandonedBackups(ctx); err != nil {
		failures = append(failures, err)
	}
	if err := r.closeAbandonedRestores(ctx); err != nil {
		failures = append(failures, err)
	}
	if err := r.sweepWorkDirectory(ctx); err != nil {
		failures = append(failures, err)
	}
	return errors.Join(failures...)
}

// sweepWorkDirectory removes staged archives and journals that belong to nothing.
//
// The work directory is the one place on the node where a forty-gigabyte file can be left
// behind by a run that will never be resumed, and it sits on the same disk as the customers'
// volumes. What is kept is exactly what an unfinished run needs; everything else belonged to
// a run that has already been reported one way or the other.
//
// Deliberately after the two recovery passes above, so a restore closed out a moment ago has
// its download swept in the same start-up rather than surviving until the next one.
func (r *Runner) sweepWorkDirectory(ctx context.Context) error {
	live := make(map[string]bool)
	backups, err := r.store.UnfinishedBackups(ctx)
	if err != nil {
		// Without the list there is no way to tell waste from work in progress, and deleting on
		// a guess would throw away the archive a resumed backup is about to upload.
		return fmt.Errorf("backup: work out which staged archives are still needed: %w", err)
	}
	for _, run := range backups {
		live[run.BackupID] = true
	}
	restores, err := r.store.UnfinishedRestores(ctx)
	if err != nil {
		return fmt.Errorf("backup: work out which downloads are still needed: %w", err)
	}
	for _, run := range restores {
		live[run.RestoreID] = true
	}

	directory := filepath.Join(r.stateDir, workDirectory)
	entries, err := os.ReadDir(directory)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("backup: read %s: %w", directory, err)
	}

	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		if live[runIDOf(entry.Name())] {
			continue
		}
		path := filepath.Join(directory, entry.Name())
		if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
			r.log.Warn("could not remove a leftover file from the backup work directory",
				slog.String("file", path), slog.String("error", err.Error()))
			continue
		}
		r.log.Info("removed a leftover file from the backup work directory", slog.String("file", path))
	}
	return nil
}

// runIDOf reads the run id back out of a work-directory file name.
//
// Every name in there is the run id followed by a suffix this package chose, and every suffix
// begins at the first dot - which is why checkIdentifier refuses a run id that starts with one.
func runIDOf(name string) string {
	if dot := strings.IndexByte(name, '.'); dot > 0 {
		return name[:dot]
	}
	return name
}

// thawAbandonedBackups unfreezes every workload a backup may have left paused.
//
// Unconditional rather than only for runs recorded at QUIESCE. The stage is written before the
// pause is taken, so a run recorded at SNAPSHOT was certainly paused at some point, and a run
// killed between the two is recorded at QUIESCE and may not have been - and Unpause on a
// container that is already running is a no-op, while a missed one is a customer's application
// down until somebody notices.
func (r *Runner) thawAbandonedBackups(ctx context.Context) error {
	running, err := r.store.UnfinishedBackups(ctx)
	if err != nil {
		return fmt.Errorf("backup: look for backups that were in flight: %w", err)
	}

	for _, run := range running {
		if run.WorkloadID == "" {
			continue
		}
		r.log.Warn("a backup was interrupted; unfreezing the workload it may have paused",
			slog.String("backup", run.BackupID),
			slog.String("workload", run.WorkloadID),
			slog.String("stage", describeStage(run.Stage)))

		if err := r.workloads.Unpause(ctx, run.WorkloadID); err != nil {
			// Logged, not returned. The daemon has to come up: a node that refuses to start
			// because one container will not thaw is a node whose other two hundred containers
			// stop being managed as well.
			r.log.Error("could not unfreeze a workload after an interrupted backup",
				slog.String("workload", run.WorkloadID), slog.String("error", err.Error()))
		}
	}
	return nil
}

// closeAbandonedRestores puts back what an interrupted restore moved, and records the failure.
func (r *Runner) closeAbandonedRestores(ctx context.Context) error {
	running, err := r.store.UnfinishedRestores(ctx)
	if err != nil {
		return fmt.Errorf("backup: look for restores that were in flight: %w", err)
	}

	for _, run := range running {
		detail := r.recoverOneRestore(run)
		completed := &wisperpb.RestoreCompleted{
			RestoreId: run.RestoreID,
			Success:   false,
			Detail:    trimDetail(detail, "the restore was interrupted by the node restarting"),
		}
		if err := r.store.FinishRestore(ctx, run.RestoreID, completed, r.now()); err != nil {
			r.log.Error("could not close out a restore that was interrupted",
				slog.String("restore", run.RestoreID), slog.String("error", err.Error()))
		}
	}
	return nil
}

// recoverOneRestore does whatever putting the data back requires, and returns the line the
// panel is told.
func (r *Runner) recoverOneRestore(run state.RestoreRun) string {
	if run.DryRun {
		// A rehearsal writes only to its own directory, so there is nothing to put back. The
		// half-unpacked tree is left where it is: it is outside the live data, and an operator
		// looking at why the node restarted mid-rehearsal may want to see it.
		return "the node restarted during a rehearsal; nothing live was touched"
	}
	if run.Kind != wisperpb.BackupTargetKind_BACKUP_TARGET_KIND_VOLUME {
		// A database restore that was interrupted has left the engine in whatever state the
		// replay reached, and the dump taken beforehand is in the rollback tree. Replaying it
		// unasked could destroy the half that the customer would rather have kept.
		return fmt.Sprintf("the node restarted while replaying a dump into %s; the database as "+
			"it was beforehand is in this node's rollback directory", run.SubjectID)
	}

	live, err := volumePath(r.stateDir, run.WorkloadID, run.SubjectID)
	if err != nil {
		return err.Error()
	}
	aside, err := rollbackPath(r.stateDir, run.WorkloadID, run.SubjectID, run.RestoreID)
	if err != nil {
		return err.Error()
	}

	if _, err := os.Lstat(aside); err != nil {
		// Nothing was moved, so the restore was killed before it touched anything.
		return "the node restarted before the restore replaced anything"
	}
	if _, err := os.Lstat(live); err == nil {
		// Both exist: the restore got as far as moving the new tree into place, and the copy
		// is the rollback copy it was always going to keep. Nothing to do.
		return "the node restarted after the data had been replaced; the previous data is kept " +
			"in this node's rollback directory"
	} else if !errors.Is(err, os.ErrNotExist) {
		return fmt.Sprintf("could not tell whether %s survived the restart: %v", live, err)
	}

	// The volume is missing and the copy is there: killed between the two renames.
	copied := safetyCopy{From: live, To: aside, Taken: true}
	if err := copied.undo(); err != nil {
		r.log.Error("a restore was interrupted and the original data could not be put back",
			slog.String("volume", live), slog.String("copy", aside), slog.String("error", err.Error()))
		return fmt.Sprintf("the node restarted mid-restore and %s could not be put back at %s: %v",
			aside, live, err)
	}
	r.log.Warn("put a volume back after a restore the node restarted during",
		slog.String("volume", live), slog.String("from", aside))
	return fmt.Sprintf("the node restarted mid-restore; the data as it was before has been put "+
		"back at %s", live)
}
