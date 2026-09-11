package backup

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Putting a volume back.
//
// The live directory is never extracted into. The archive is unpacked into a staging tree
// beside it, and only when that has finished without error does anything move - and then it
// moves with two renames rather than a copy, so the window in which the volume is neither the
// old one nor the new one is measured in microseconds and cannot leave a half-written tree
// behind whatever happens to the process.
//
//	1. extract into  backup-restore/<workload>/<subject>/.staging-<restore>/
//	2. rename         volumes/<workload>/<subject>  ->  backup-rollback/<workload>/<subject>/<restore>
//	3. rename         .staging-<restore>            ->  volumes/<workload>/<subject>
//
// Both renames are within the state tree, which is one filesystem. A rename across devices
// fails, and finding that out after step 2 - with the customer's data moved and their volume
// missing - is the failure this layout is arranged to make impossible.
//
// If step 3 fails, step 2 is undone. If the daemon dies between them, recover.go does the same
// thing on the next start-up, which is why the copy in backup-rollback is named after the
// restore that took it.

// restoreVolume unpacks an archive over a volume, or beside it for a dry run. It returns the
// line that describes what happened.
func (r *Runner) restoreVolume(ctx context.Context, request *wisperpb.RestoreBackup,
	archive staged, outcome *restoreReport) (string, error) {

	workloadID := request.GetWorkloadId()
	subjectID := request.GetSubjectId()
	restoreID := request.GetRestoreId()

	if request.GetDryRun() {
		return r.rehearseVolume(ctx, request, archive, outcome)
	}

	live, err := volumePath(r.stateDir, workloadID, subjectID)
	if err != nil {
		return "", err
	}
	staging, err := stagingPath(r.stateDir, workloadID, subjectID, restoreID)
	if err != nil {
		return "", err
	}

	r.note(ctx, restoreID, "unpacking the archive")
	written, err := r.unpack(ctx, archive, staging)
	outcome.bytes = written
	if err != nil {
		os.RemoveAll(staging)
		return "", err
	}

	r.note(ctx, restoreID, "moving the current data aside")
	copied, err := takeSafetyCopy(r.stateDir, workloadID, subjectID, restoreID, live)
	if err != nil {
		os.RemoveAll(staging)
		return "", err
	}

	if err := makeDirectory(filepath.Dir(live)); err != nil {
		os.RemoveAll(staging)
		return "", r.undoOrShout(copied, err)
	}
	if err := os.Rename(staging, live); err != nil {
		// The tree that was going to become the volume is removed before the original goes
		// back, because undo renames onto that path and a directory in the way would turn a
		// recoverable failure into a volume that is neither version.
		os.RemoveAll(staging)
		return "", r.undoOrShout(copied, fmt.Errorf("backup: move the restored volume into place "+
			"at %s: %w", live, err))
	}
	// The mode runtime/storage.go gives a volume root. A restored tree carries the mode it had
	// inside the archive, and a container that does not run as root cannot write to its own
	// data directory if that mode came from somewhere stricter.
	if err := os.Chmod(live, volumeMode); err != nil {
		r.log.Warn("restored a volume but could not set the mode of its root",
			slog.String("volume", live), slog.String("error", err.Error()))
	}

	outcome.restoredTo = live
	if !copied.Taken {
		return fmt.Sprintf("%d bytes restored to %s; there was no previous data to keep",
			written, live), nil
	}
	r.log.Info("restored a volume and kept the previous data",
		slog.String("volume", live),
		slog.String("rollback_copy", copied.To),
		slog.Int64("bytes", written))
	return fmt.Sprintf("%d bytes restored to %s; the previous data is kept at %s",
		written, live, copied.To), nil
}

// rehearseVolume restores beside the live data instead of over it.
//
// This is the verify-restore path, and the reason it exists is that a backup nobody has ever
// put back is a backup nobody knows is any good. It stops nothing, moves nothing and can be
// run against production data on a Tuesday afternoon; what it proves is that the archive
// unpacks into a tree the customer recognises.
func (r *Runner) rehearseVolume(ctx context.Context, request *wisperpb.RestoreBackup,
	archive staged, outcome *restoreReport) (string, error) {

	target, err := dryRunPath(r.stateDir, request.GetWorkloadId(), request.GetSubjectId(),
		request.GetRestoreId())
	if err != nil {
		return "", err
	}
	// A rehearsal with the same restore id that was interrupted. Removed rather than merged
	// into: half of one archive on top of half of another is a tree that proves nothing.
	if err := os.RemoveAll(target); err != nil {
		return "", fmt.Errorf("backup: clear %s before rehearsing a restore into it: %w", target, err)
	}

	r.note(ctx, request.GetRestoreId(), "unpacking the archive beside the live data")
	written, err := r.unpack(ctx, archive, target)
	outcome.bytes = written
	if err != nil {
		os.RemoveAll(target)
		return "", err
	}

	outcome.restoredTo = target
	r.log.Info("rehearsed a volume restore",
		slog.String("volume", request.GetSubjectId()),
		slog.String("at", target),
		slog.Int64("bytes", written))
	return fmt.Sprintf("%d bytes unpacked to %s, beside the live data rather than over it; "+
		"nothing was stopped and nothing was replaced", written, target), nil
}

// unpack extracts an archive into a directory it creates.
func (r *Runner) unpack(ctx context.Context, archive staged, target string) (int64, error) {
	if err := os.RemoveAll(target); err != nil {
		return 0, fmt.Errorf("backup: clear %s: %w", target, err)
	}
	if err := makeDirectory(target); err != nil {
		return 0, err
	}

	file, err := os.Open(archive.Path)
	if err != nil {
		return 0, fmt.Errorf("backup: open the downloaded archive %s: %w", archive.Path, err)
	}
	defer file.Close()

	written, err := extractVolumeArchive(contextReader(ctx, file), target)
	if err != nil {
		return written, err
	}
	return written, nil
}

// undoOrShout puts the original data back, and says so very loudly if it cannot.
//
// The second case is the worst state this package can produce: the volume has been moved and
// could not be moved back. It is still recoverable - the data is in the rollback tree and
// recover.go will try again on the next start-up - and the error a customer sees says exactly
// where it is, because a message that only says "restore failed" would send somebody looking
// for a backup of the backup.
func (r *Runner) undoOrShout(copied safetyCopy, cause error) error {
	if err := copied.undo(); err != nil {
		r.log.Error("a restore failed and the original data could not be put back",
			slog.String("moved_to", copied.To),
			slog.String("belongs_at", copied.From),
			slog.String("error", err.Error()))
		return fmt.Errorf("%w; putting the original data back also failed (%v), and it is at %s",
			cause, err, copied.To)
	}
	r.log.Warn("a restore failed and the original data was put back",
		slog.String("volume", copied.From), slog.String("error", cause.Error()))
	return fmt.Errorf("%w; the original data was put back", cause)
}
