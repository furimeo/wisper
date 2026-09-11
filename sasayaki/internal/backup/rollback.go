package backup

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The copy taken before a restore overwrites anything.
//
// A restore is the operation somebody reaches for when they are already having a bad day, and
// the two ways it goes wrong are both recoverable only if this exists: the wrong restore point
// was chosen, or the archive was right and the application still does not start. Both leave the
// customer wanting the state from five minutes ago, and five minutes ago is the thing a restore
// destroys.
//
// So the live data is never written over. It is moved - a rename, which is atomic and costs
// nothing regardless of size - and the restored tree is moved into the space it left. What was
// moved is kept under backup-rollback/ after the restore succeeds rather than deleted, because
// "it restored successfully" and "it was the right restore point" are different claims and only
// the customer can make the second one.
//
// A database has no directory to rename, so its safety copy is a dump taken immediately before
// the replay, written to the same tree. It costs a second dump of the database, which is the
// cheapest insurance in this package.

// safetyCopy is the live tree, moved aside.
type safetyCopy struct {
	// From is where it was, and where it goes back to if the restore does not finish.
	From string
	// To is where it is now. Reported to the customer, because a rollback copy nobody can
	// find is not one.
	To string
	// Taken is false when there was nothing to move: a first restore into a volume that has
	// never existed. Not an error, and not something to invent a copy of.
	Taken bool
}

// takeSafetyCopy moves the live directory into the rollback tree.
func takeSafetyCopy(stateDir, workloadID, subjectID, restoreID, live string) (safetyCopy, error) {
	destination, err := rollbackPath(stateDir, workloadID, subjectID, restoreID)
	if err != nil {
		return safetyCopy{}, err
	}

	if _, err := os.Lstat(live); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return safetyCopy{From: live}, nil
		}
		return safetyCopy{}, fmt.Errorf("backup: look at %s before restoring over it: %w", live, err)
	}

	if err := makeDirectory(filepath.Dir(destination)); err != nil {
		return safetyCopy{}, err
	}
	// A leftover from a restore with the same id that was interrupted before it finished. It
	// cannot be the copy this restore is about to take, and leaving it would make the rename
	// fail on every retry.
	if err := os.RemoveAll(destination); err != nil {
		return safetyCopy{}, fmt.Errorf("backup: clear %s: %w", destination, err)
	}
	if err := os.Rename(live, destination); err != nil {
		return safetyCopy{}, fmt.Errorf("backup: move %s aside to %s before restoring: %w",
			live, destination, err)
	}
	return safetyCopy{From: live, To: destination, Taken: true}, nil
}

// undo puts the live tree back where it was, for a restore that failed after moving it.
//
// This is the path that decides whether a failed restore is an inconvenience or an outage. It
// removes whatever the failed attempt managed to put at the live path first, because a rename
// onto a non-empty directory fails and the one thing that must not happen here is that the
// recovery is refused because the wreckage is in the way.
func (s safetyCopy) undo() error {
	if !s.Taken {
		// Nothing was moved, so putting anything "back" would be inventing it. What may exist
		// is a partly restored tree, and that is removed: the volume did not exist before this
		// restore, and it should not exist after one that failed.
		if err := os.RemoveAll(s.From); err != nil {
			return fmt.Errorf("backup: remove the half-restored %s: %w", s.From, err)
		}
		return nil
	}
	if err := os.RemoveAll(s.From); err != nil {
		return fmt.Errorf("backup: clear %s before putting the original back: %w", s.From, err)
	}
	if err := os.Rename(s.To, s.From); err != nil {
		return fmt.Errorf("backup: put %s back at %s after a restore that did not finish: %w",
			s.To, s.From, err)
	}
	return nil
}

// dumpBeforeRestore takes the database equivalent of moving a directory aside.
//
// Written through the same staging machinery as a backup - compressed, hashed, renamed into
// place only when whole - so what lands in the rollback tree is a file somebody can actually
// restore from rather than a dump that was half written when the restore overwrote the thing
// it was a copy of.
func (r *Runner) dumpBeforeRestore(ctx context.Context, request *wisperpb.RestoreBackup) (string, error) {
	destination, err := rollbackPath(r.stateDir, request.GetWorkloadId(), request.GetSubjectId(),
		request.GetRestoreId())
	if err != nil {
		return "", err
	}
	if err := makeDirectory(filepath.Dir(destination)); err != nil {
		return "", err
	}

	// Staged in the work directory like every other archive, then moved. Writing straight into
	// the rollback tree would leave a half-finished dump there if the daemon died mid-write,
	// and a rollback copy that is not whole is worse than none: somebody would try it.
	dump, err := stageArchive(r.stateDir, request.GetRestoreId(), rollbackExtension,
		func(out io.Writer) error {
			return r.databases.DumpDatabase(ctx, request.GetEngine(), request.GetDatabaseName(), out)
		})
	if err != nil {
		return "", fmt.Errorf("backup: dump %s before restoring over it: %w",
			request.GetDatabaseName(), err)
	}

	target := destination + databaseExtension
	if err := os.Rename(dump.Path, target); err != nil {
		return "", fmt.Errorf("backup: file the rollback dump of %s at %s: %w",
			request.GetDatabaseName(), target, err)
	}
	if err := discardStaged(r.stateDir, request.GetRestoreId(), rollbackExtension); err != nil {
		r.log.Warn("could not tidy up after taking a rollback dump",
			slog.String("restore", request.GetRestoreId()), slog.String("error", err.Error()))
	}

	r.log.Info("took a rollback dump before restoring a database",
		slog.String("database", request.GetDatabaseName()),
		slog.String("at", target),
		slog.Int64("bytes", dump.Size))
	return target, nil
}
