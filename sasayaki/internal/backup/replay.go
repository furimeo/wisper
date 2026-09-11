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

// Answering a command the panel has sent before.
//
// The control stream drops between the node finishing a backup and the panel receiving the
// result, the panel resends, and there are exactly two honest answers depending on what the
// row on disk says.
//
// A finished run is replayed. Taking the snapshot again would pause the customer's application
// for a second time and upload a second archive of the same bytes, which the retention rule
// would then count as two generations - so the same run reported twice would quietly halve how
// far back the customer can go.
//
// An unfinished run is resumed. That is not the same thing and it is the case that matters:
// the daemon was killed mid-upload, the archive is still staged on local disk and the object
// store is still holding the parts that made it across. Starting again would pause the
// application again and throw away a good archive and however much of an upload had already
// succeeded.
//
// The started_at of the original run is carried forward on a resume, because the object key
// contains it. A resumed run with a fresh timestamp would upload to a second key and leave the
// first one behind as an orphan with no checksum beside it.

// resumeBackup decides what a repeated RunBackup means.
//
// Three outcomes: a completed result to hand straight back, a moment to restart from, or an
// error for the case where the row cannot be read at all.
func (r *Runner) resumeBackup(ctx context.Context, backupID string, cause error) (*wisperpb.BackupCompleted, time.Time, error) {
	if !errors.Is(cause, state.ErrAlreadyExists) {
		return nil, time.Time{}, fmt.Errorf("backup: record the start of backup %s: %w", backupID, cause)
	}

	existing, err := r.store.Backup(ctx, backupID)
	if err != nil {
		return nil, time.Time{}, fmt.Errorf("backup: backup %s has been started before and cannot "+
			"be read back: %w", backupID, err)
	}

	if existing.Finished {
		if existing.Result == nil {
			return nil, time.Time{}, fmt.Errorf("backup: backup %s is recorded as finished at %s "+
				"with no result, which is a row nothing in this daemon writes",
				backupID, existing.FinishedAt.Format(time.RFC3339))
		}
		r.log.Info("replaying the result of a backup the panel asked for twice",
			slog.String("backup", backupID), slog.Bool("ok", existing.Success))
		return existing.Result, time.Time{}, nil
	}

	r.log.Info("carrying on a backup that was interrupted",
		slog.String("backup", backupID),
		slog.String("stage", describeStage(existing.Stage)),
		slog.String("started", existing.StartedAt.Format(time.RFC3339)))
	return nil, existing.StartedAt, nil
}

// resumeRestore is the same decision for the other direction, with one difference that
// matters: an interrupted restore is not carried on from where it stopped.
//
// A restore that died halfway has left a volume swung aside or a database half replayed, and
// the state it is in is not something a second attempt can infer. recover.go puts the volume
// back on start-up, deliberately before any command is answered, and this refuses the resent
// command until that has happened - which turns "the panel retried and made it worse" into a
// clear error naming the restore that has to be sorted out first.
func (r *Runner) resumeRestore(ctx context.Context, restoreID string, cause error) (*wisperpb.RestoreCompleted, error) {
	if !errors.Is(cause, state.ErrAlreadyExists) {
		return nil, fmt.Errorf("backup: record the start of restore %s: %w", restoreID, cause)
	}

	existing, err := r.store.Restore(ctx, restoreID)
	if err != nil {
		return nil, fmt.Errorf("backup: restore %s has been started before and cannot be read "+
			"back: %w", restoreID, err)
	}

	if existing.Finished {
		if existing.Result == nil {
			return nil, fmt.Errorf("backup: restore %s is recorded as finished at %s with no "+
				"result, which is a row nothing in this daemon writes",
				restoreID, existing.FinishedAt.Format(time.RFC3339))
		}
		r.log.Info("replaying the result of a restore the panel asked for twice",
			slog.String("restore", restoreID), slog.Bool("ok", existing.Success))
		return existing.Result, nil
	}

	return nil, fmt.Errorf("backup: restore %s is already in flight on this node; it started at "+
		"%s and the last thing it reported was %q. A restore is not resumed automatically, "+
		"because a half-written volume is not a state a second attempt can work out",
		restoreID, existing.StartedAt.Format(time.RFC3339), existing.Detail)
}
