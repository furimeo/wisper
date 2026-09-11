package backup

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// One backup, from the command arriving to the answer going back.
//
// A backup that failed is not an error. It comes back as a BackupCompleted with success false
// and the stage it stopped at, because "your object store answered 403" is the customer's
// information and the RPC did exactly what it was asked; rpc/dispatch.go turns a returned
// error into a command that failed rather than a result, which is the right shape only for a
// command that could not be attempted at all.
//
// The stage in the message is the whole diagnostic. A failure at QUIESCE is the customer's
// application, at SNAPSHOT their disk, at UPLOAD their object store, at VERIFY something
// between the two - and those are four different people to talk to.

// stageFailure carries which stage a failure happened at, so the stage machine below does not
// have to guess from the text of an error raised three files away.
type stageFailure struct {
	stage wisperpb.BackupStage
	inner error
}

func (s stageFailure) Error() string { return s.inner.Error() }
func (s stageFailure) Unwrap() error { return s.inner }

// at marks an error as belonging to a stage. Nil stays nil.
func at(stage wisperpb.BackupStage, err error) error {
	if err == nil {
		return nil
	}
	return stageFailure{stage: stage, inner: err}
}

// stageOf reads the mark back, falling back for an error that was never marked.
func stageOf(err error, fallback wisperpb.BackupStage) wisperpb.BackupStage {
	var marked stageFailure
	if errors.As(err, &marked) {
		return marked.stage
	}
	return fallback
}

// RunBackup takes one snapshot to completion.
func (r *Runner) RunBackup(ctx context.Context, request *wisperpb.RunBackup) (*wisperpb.BackupCompleted, error) {
	if err := validateBackup(request); err != nil {
		return nil, err
	}
	backupID := request.GetBackupId()

	unlock, err := r.lockSubject(ctx, request.GetSubjectId())
	if err != nil {
		return nil, err
	}
	defer unlock()

	startedAt := r.now()
	err = r.store.BeginBackup(ctx, state.BackupRun{
		BackupID:   backupID,
		Kind:       request.GetKind(),
		SubjectID:  request.GetSubjectId(),
		WorkloadID: request.GetWorkloadId(),
		Stage:      wisperpb.BackupStage_BACKUP_STAGE_QUIESCE,
		StartedAt:  startedAt,
		Detail:     "starting",
	})
	if err != nil {
		replayed, resumedFrom, resumeErr := r.resumeBackup(ctx, backupID, err)
		if resumeErr != nil {
			return nil, resumeErr
		}
		if replayed != nil {
			return replayed, nil
		}
		// The object key carries the moment the backup started, so a resumed run has to use
		// the original one or it would upload to a second key and leave the first behind.
		startedAt = resumedFrom
	}

	timeout := timeoutFor(request.GetTimeoutSeconds())
	deadline, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	completed, remains := r.perform(deadline, request, startedAt, timeout)

	if !completed.GetSuccess() && ctx.Err() != nil {
		// The daemon is shutting down, or the panel withdrew the command. Nothing is recorded
		// and nothing is cleaned up: the row stays open, the staged archive and the upload
		// journal stay on disk, and the same command sent again carries on from where this one
		// stopped. Reporting a failed backup here instead would tell the panel to write this
		// off and schedule a fresh one, which is a second pause of the customer's application
		// and a second upload of bytes that are already most of the way there.
		//
		// An error rather than a result, because that is the difference: a backup that failed
		// is a fact about the customer's data, and this is a command that did not finish.
		r.log.Warn("a backup was interrupted and is left resumable",
			slog.String("backup", backupID), slog.String("subject", request.GetSubjectId()))
		return nil, fmt.Errorf("backup: backup %s was interrupted before it finished; it is left "+
			"resumable and the same command will carry on from where it stopped: %w",
			backupID, ctx.Err())
	}

	// Deliberately not the deadline context: the most important record to write is the one for
	// a backup that ran out of time, and writing it through a cancelled context is how a
	// schedule gets stuck at "running" forever.
	if err := r.store.FinishBackup(context.WithoutCancel(ctx), backupID, completed, r.now()); err != nil {
		// The panel still gets its answer. Said at error level rather than failing the
		// command: the backup really did happen, and refusing to report it because a row would
		// not write would lose the outcome as well as the record of it.
		r.log.Error("could not record the outcome of a backup",
			slog.String("backup", backupID), slog.String("error", err.Error()))
	}

	if !completed.GetSuccess() {
		// A failure that was reported is a failure the panel will schedule a fresh run for, so
		// nothing here will ever be resumed and everything it left is waste.
		remains.clear(context.WithoutCancel(ctx), r.log)
	}

	r.log.Info("backup finished",
		slog.String("backup", backupID),
		slog.String("subject", request.GetSubjectId()),
		slog.Bool("ok", completed.GetSuccess()),
		slog.String("detail", completed.GetDetail()))
	return completed, nil
}

// perform is the stage machine. It never returns an error: everything that can go wrong here
// is something the customer is entitled to see, so it comes back as a result.
func (r *Runner) perform(ctx context.Context, request *wisperpb.RunBackup, startedAt time.Time,
	timeout time.Duration) (*wisperpb.BackupCompleted, leftovers) {

	backupID := request.GetBackupId()
	extension := extensionFor(request.GetKind())
	outcome := backupReport{backupID: backupID, startedAt: startedAt}
	remains := leftovers{stateDir: r.stateDir, runID: backupID, extension: extension}

	fail := func(stage wisperpb.BackupStage, detail string) (*wisperpb.BackupCompleted, leftovers) {
		if ctx.Err() != nil && errors.Is(ctx.Err(), context.DeadlineExceeded) {
			detail = fmt.Sprintf("the %s stage ran past the backup's %s timeout and was stopped",
				describeStage(stage), timeout)
		}
		return outcome.failed(r.now(), stage, detail), remains
	}

	destination, prefix, err := r.resolveDestination(request.GetDestination())
	if err != nil {
		return fail(wisperpb.BackupStage_BACKUP_STAGE_UPLOAD, err.Error())
	}
	key, err := objectKey(prefix, request.GetSubjectId(), startedAt, backupID, request.GetKind())
	if err != nil {
		return fail(wisperpb.BackupStage_BACKUP_STAGE_UPLOAD, err.Error())
	}
	journal, err := openJournal(r.stateDir, backupID)
	if err != nil {
		return fail(wisperpb.BackupStage_BACKUP_STAGE_UPLOAD, err.Error())
	}
	remains.destination, remains.key, remains.journal = destination, key, journal

	r.enterStage(ctx, backupID, wisperpb.BackupStage_BACKUP_STAGE_QUIESCE)
	archive, quiesce, err := r.snapshot(ctx, request)
	outcome.quiesce = quiesce
	if err != nil {
		return fail(stageOf(err, wisperpb.BackupStage_BACKUP_STAGE_SNAPSHOT), err.Error())
	}
	outcome.size = archive.Size
	outcome.sha256 = archive.SHA256
	outcome.restorePointID = restorePointID(startedAt, backupID)
	outcome.location = key

	r.enterStage(ctx, backupID, wisperpb.BackupStage_BACKUP_STAGE_UPLOAD)
	if err := r.upload(ctx, destination, key, archive, journal); err != nil {
		return fail(wisperpb.BackupStage_BACKUP_STAGE_UPLOAD, err.Error())
	}

	if request.GetVerify() {
		r.enterStage(ctx, backupID, wisperpb.BackupStage_BACKUP_STAGE_VERIFY)
		if err := r.verify(ctx, destination, key, archive); err != nil {
			return fail(wisperpb.BackupStage_BACKUP_STAGE_VERIFY, err.Error())
		}
		outcome.verified = true
	}

	r.enterStage(ctx, backupID, wisperpb.BackupStage_BACKUP_STAGE_PRUNE)
	pruned, err := r.prune(ctx, pruneRequest{
		Destination: destination,
		Prefix:      prefix,
		SubjectID:   request.GetSubjectId(),
		Protected:   key,
		Rule:        request.GetRetention(),
	})
	outcome.pruned = pruned
	if err != nil {
		// Not a failure. The archive is at the destination and the customer has their backup;
		// a retention pass that could not finish is tried again by the next successful run,
		// and reporting the whole backup as failed because of it would be a lie about where
		// their data is.
		r.log.Warn("the backup succeeded but the retention rule could not be applied in full",
			slog.String("backup", backupID), slog.String("error", err.Error()))
	}

	// The staged archive has done its job. The journal went with the completed upload.
	if err := discardStaged(r.stateDir, backupID, extension); err != nil {
		r.log.Warn("could not remove a staged archive after uploading it",
			slog.String("backup", backupID), slog.String("error", err.Error()))
	}

	detail := fmt.Sprintf("%d bytes at %s", archive.Size, outcome.location)
	if outcome.verified {
		detail += ", read back and checked"
	}
	if pruned > 0 {
		detail += fmt.Sprintf(", %d older backup(s) removed", pruned)
	}
	return outcome.succeeded(r.now(), detail), leftovers{}
}

// enterStage records where a backup has got to, in the row that survives the daemon being
// killed.
//
// Failures are logged and not propagated. A backup that stopped because a progress row would
// not write would be an outage caused by bookkeeping, and the stage is a diagnostic rather
// than a decision anything downstream makes.
func (r *Runner) enterStage(ctx context.Context, backupID string, stage wisperpb.BackupStage) {
	err := r.store.RecordBackupStage(context.WithoutCancel(ctx), backupID, stage, r.now(), describeStage(stage))
	if err != nil {
		r.log.Warn("could not record which stage a backup reached",
			slog.String("backup", backupID),
			slog.String("stage", describeStage(stage)),
			slog.String("error", err.Error()))
	}
}

// leftovers is what a run that failed and was reported left behind.
type leftovers struct {
	destination Destination
	key         string
	journal     *uploadJournal
	stateDir    string
	runID       string
	extension   string
}

func (l leftovers) clear(ctx context.Context, log *slog.Logger) {
	if l.destination != nil && l.journal != nil {
		l.destination.Abandon(ctx, l.key, l.journal)
	}
	if l.stateDir == "" || l.runID == "" {
		return
	}
	if err := discardStaged(l.stateDir, l.runID, l.extension); err != nil {
		log.Warn("could not remove the staged archive of a failed backup",
			slog.String("backup", l.runID), slog.String("error", err.Error()))
	}
}
