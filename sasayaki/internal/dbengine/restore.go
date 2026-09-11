package dbengine

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
)

// Putting a logical backup back.
//
// The mirror image of dump.go and for the same reason: the archive is written to the transfer
// directory the server's container can see, and the server's own client reads it from there,
// so a ten-gigabyte restore costs disk rather than memory.
//
// # What Replace means
//
// A dump loaded into a database that already has tables produces a mixture of the two, which
// is almost never what "restore" means. So `Target.Replace` drops the database and recreates
// it empty first, and the backup package sets it for a real restore and leaves it off for a
// dry run - where the point is to load the archive into a sibling database and look at it
// before committing to anything (backup.proto, RestoreBackup.dry_run).
//
// The database is created when it is missing, whether or not Replace is set. That is what
// makes a dry run work at all, and it is also the right answer for a real restore of a
// database that has been dropped - which is the case a restore point exists for.

// Restore loads a dump into one database and returns how many bytes it read.
func (e *Engines) Restore(ctx context.Context, target Target, from io.Reader) (int64, error) {
	if err := checkDatabaseName(target.DatabaseName); err != nil {
		return 0, err
	}
	if target.Username != "" {
		if err := checkUsername(target.Username); err != nil {
			return 0, err
		}
	}

	// Held for the whole restore. A reconcile pass that replaced the container underneath a
	// twenty-minute restore would leave a half-loaded database and no way to tell.
	e.converging.Lock()
	defer e.converging.Unlock()

	seen, err := e.serverOf(ctx, target.Engine)
	if err != nil {
		return 0, err
	}

	name, err := transferName("restore", seen.Instance.Talk.dumpExtension())
	if err != nil {
		return 0, err
	}
	hostPath := filepath.Join(seen.Instance.Paths.Transfer, name)
	containerPath := transferMount + "/" + name
	defer removeTransfer(e.log, hostPath)

	read, err := stage(hostPath, from)
	if err != nil {
		return read, err
	}
	if read == 0 {
		return 0, fmt.Errorf("dbengine: the archive for %s was empty, and loading nothing over a "+
			"database is not a restore", target.DatabaseName)
	}

	if err := e.prepare(ctx, seen, target); err != nil {
		return read, err
	}

	statement, err := seen.Instance.Talk.restore(seen.Instance.Spec, target.DatabaseName, containerPath)
	if err != nil {
		return read, err
	}
	if _, err := e.runner(seen.ContainerID)(ctx, statement); err != nil {
		return read, err
	}

	e.log.Info("restored a database",
		slog.String("database", target.DatabaseName),
		slog.String("instance", seen.Instance.ID),
		slog.Bool("replaced", target.Replace),
		slog.Int64("bytes", read))
	return read, nil
}

// prepare makes the target database ready to be loaded into: empty when Replace was asked for,
// and present either way.
func (e *Engines) prepare(ctx context.Context, seen observed, target Target) error {
	existing, present := seen.database(target.DatabaseName)

	owner := target.Username
	if present && existing.Owner != "" {
		// Keep whoever owns it now rather than what the caller guessed, so a restore over a
		// live database leaves the customer's own login owning it afterwards.
		owner = existing.Owner
	}

	statement, err := e.opening(seen, target, present, owner)
	if err != nil {
		return err
	}
	_, err = e.runner(seen.ContainerID)(ctx, statement)
	return err
}

// opening is the statement that makes room for the archive.
func (e *Engines) opening(seen observed, target Target, present bool, owner string) (command, error) {
	if present && target.Replace {
		return seen.Instance.Talk.resetDatabase(seen.Instance.Spec, target.DatabaseName, owner)
	}
	return seen.Instance.Talk.ensureDatabase(seen.Instance.Spec, target.DatabaseName, owner)
}

// stage writes the incoming archive into the transfer directory.
//
// Created with the mode the server's own user can read, because the process inside the
// container is not root by the time it opens the file, and a restore that fails on permissions
// after a twenty-minute download is the worst possible place to find that out.
func stage(path string, from io.Reader) (int64, error) {
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o666)
	if err != nil {
		return 0, fmt.Errorf("dbengine: open %s to stage the archive: %w", path, err)
	}
	defer file.Close()

	written, err := io.Copy(file, from)
	if err != nil {
		return written, fmt.Errorf("dbengine: write the archive to %s: %w", path, err)
	}
	// The umask the daemon inherits from systemd would otherwise take the group and other
	// read bits back off, and the server does not run as the daemon's user.
	if err := os.Chmod(path, 0o666); err != nil {
		return written, fmt.Errorf("dbengine: set the mode of %s: %w", path, err)
	}
	if err := file.Sync(); err != nil {
		return written, fmt.Errorf("dbengine: flush the archive to %s: %w", path, err)
	}
	return written, nil
}
