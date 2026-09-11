package backup

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Producing the archive, which is the only part of a backup that touches the customer's data.
//
// Both kinds end in the same place - one gzipped file under backup-work, with a length and a
// digest - and the reason they go through one function is that everything after this point
// is identical. The upload does not know whether it is carrying a filesystem or a database,
// retention counts generations the same way for both, and a restore only branches again at
// the very last step.
//
// The resume check comes first. A daemon killed during an upload comes back with the archive
// already on disk, and taking the snapshot again would pause a customer's application a
// second time for data that has not changed since - and would invalidate the parts the object
// store is still holding.

// snapshot produces the archive for a run, reusing one a previous attempt left behind.
func (r *Runner) snapshot(ctx context.Context, request *wisperpb.RunBackup) (staged, time.Duration, error) {
	extension := extensionFor(request.GetKind())

	existing, found, err := loadStagedArchive(r.stateDir, request.GetBackupId(), extension)
	if err != nil {
		return staged{}, 0, err
	}
	if found {
		// Zero pause, and that is the honest number for this attempt: the workload was frozen
		// during the run that was interrupted, and reporting that window again here would count
		// one pause twice against a customer who only felt it once.
		r.log.Info("reusing the archive an interrupted run of this backup already produced",
			slog.String("backup", request.GetBackupId()),
			slog.Int64("bytes", existing.Size))
		return existing, 0, nil
	}

	if request.GetKind() == wisperpb.BackupTargetKind_BACKUP_TARGET_KIND_DATABASE {
		result, err := r.snapshotDatabase(ctx, request)
		return result, 0, err
	}
	return r.snapshotVolume(ctx, request)
}

// snapshotVolume pauses the owning workload, reads the tree, and lets it go.
func (r *Runner) snapshotVolume(ctx context.Context, request *wisperpb.RunBackup) (staged, time.Duration, error) {
	root, err := volumePath(r.stateDir, request.GetWorkloadId(), request.GetSubjectId())
	if err != nil {
		return staged{}, 0, err
	}

	holding, err := r.hold(ctx, request.GetWorkloadId())
	if err != nil {
		// Marked as QUIESCE rather than SNAPSHOT: a pause that would not take is the
		// customer's application misbehaving, and telling them their disk failed instead
		// sends them to look in the wrong place.
		return staged{}, 0, at(wisperpb.BackupStage_BACKUP_STAGE_QUIESCE, err)
	}
	// Covers every path out of here, including a panic and a deadline. The success path calls
	// release explicitly a few lines down; this one is for everything else.
	defer holding.release(ctx)

	var stats archiveStats
	result, err := stageArchive(r.stateDir, request.GetBackupId(), volumeExtension, func(out io.Writer) error {
		found, walkErr := writeVolumeArchive(root, out)
		stats = found
		return walkErr
	})
	// The pause ends here, before the digest is compared, before anything is uploaded and
	// before the retention rule is applied.
	quiesce := holding.release(ctx)
	if err != nil {
		return staged{}, quiesce, at(wisperpb.BackupStage_BACKUP_STAGE_SNAPSHOT, err)
	}

	r.log.Info("copied a volume",
		slog.String("backup", request.GetBackupId()),
		slog.String("volume", request.GetSubjectId()),
		slog.Int("files", stats.Files),
		slog.Int("symlinks", stats.Symlinks),
		slog.Int("skipped", stats.Skipped),
		slog.Int64("compressed_bytes", result.Size),
		slog.Duration("paused_for", quiesce))
	if stats.Skipped > 0 {
		// Sockets, devices and fifos. Said out loud rather than left to be discovered after a
		// restore that produced a directory a customer's application would not start against.
		r.log.Warn("some entries were not copied because they are not data",
			slog.String("volume", request.GetSubjectId()), slog.Int("entries", stats.Skipped))
	}
	return result, quiesce, nil
}

// snapshotDatabase asks the engine for a logical dump.
//
// No pause. A dump taken inside a transaction is consistent by construction, which is the
// whole reason a database backup does not cost the customer a stopped application - and why
// quiesce_millis is honestly zero here rather than a small number invented for symmetry.
func (r *Runner) snapshotDatabase(ctx context.Context, request *wisperpb.RunBackup) (staged, error) {
	result, err := stageArchive(r.stateDir, request.GetBackupId(), databaseExtension, func(out io.Writer) error {
		if err := r.databases.DumpDatabase(ctx, request.GetEngine(), request.GetDatabaseName(), out); err != nil {
			return fmt.Errorf("backup: dump the %s database %s: %w",
				request.GetEngine(), request.GetDatabaseName(), err)
		}
		return nil
	})
	if err != nil {
		return staged{}, err
	}
	if result.Size == 0 {
		// An empty gzip stream is a few bytes, never zero, so this can only mean the dump
		// produced nothing at all - which for a database that exists is a failure of the dump
		// tool rather than an empty database.
		return staged{}, fmt.Errorf("backup: the dump of %s produced no bytes at all",
			request.GetDatabaseName())
	}

	r.log.Info("dumped a database",
		slog.String("backup", request.GetBackupId()),
		slog.String("database", request.GetDatabaseName()),
		slog.String("engine", request.GetEngine().String()),
		slog.Int64("compressed_bytes", result.Size))
	return result, nil
}
