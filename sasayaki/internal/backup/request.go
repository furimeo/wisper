package backup

import (
	"fmt"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Checking a command before anything is paused.
//
// Everything here is refused by returning an error from the RPC rather than by reporting a
// failed backup, and the line between the two is worth stating. A backup that failed is a fact
// about the customer's data or their object store, and belongs in a BackupCompleted the panel
// records against the schedule. A command that does not make sense - a volume backup naming
// no volume, a database backup naming no engine - is a fact about the panel, and recording it
// as a failed backup would put a row in front of a customer explaining a bug they did not
// cause.
//
// The checks are also what makes the identifier rules load-bearing. Every id here reaches a
// filesystem path or an object key, and checkIdentifier is applied before the first mkdir
// rather than somewhere in the middle of one.

func validateBackup(request *wisperpb.RunBackup) error {
	if request == nil {
		return fmt.Errorf("backup: the command is empty")
	}
	if err := checkIdentifier("backup id", request.GetBackupId()); err != nil {
		return err
	}
	if err := checkIdentifier("subject id", request.GetSubjectId()); err != nil {
		return err
	}

	switch request.GetKind() {
	case wisperpb.BackupTargetKind_BACKUP_TARGET_KIND_VOLUME:
		// The owning workload is required rather than optional. It is how the writes are
		// paused, and a volume backup taken without pausing anything is a copy of a directory
		// that happened to be open (backup.proto, RunBackup.workload_id).
		if err := checkIdentifier("workload id", request.GetWorkloadId()); err != nil {
			return fmt.Errorf("backup: a volume backup needs the workload that owns it: %w", err)
		}
	case wisperpb.BackupTargetKind_BACKUP_TARGET_KIND_DATABASE:
		if request.GetEngine() == wisperpb.DatabaseEngine_DATABASE_ENGINE_UNSPECIFIED {
			return fmt.Errorf("backup: backup %s is of a database but names no engine, and the "+
				"dump tool depends on it", request.GetBackupId())
		}
		if request.GetDatabaseName() == "" {
			return fmt.Errorf("backup: backup %s is of a database but names no database",
				request.GetBackupId())
		}
	default:
		return fmt.Errorf("backup: backup %s asks for target kind %s, which this node does not take",
			request.GetBackupId(), request.GetKind())
	}

	if request.GetDestination() == nil {
		return fmt.Errorf("backup: backup %s has nowhere to go", request.GetBackupId())
	}
	return nil
}

func validateRestore(request *wisperpb.RestoreBackup) error {
	if request == nil {
		return fmt.Errorf("backup: the command is empty")
	}
	if err := checkIdentifier("restore id", request.GetRestoreId()); err != nil {
		return err
	}
	if err := checkIdentifier("subject id", request.GetSubjectId()); err != nil {
		return err
	}
	if request.GetRestorePointId() == "" {
		return fmt.Errorf("backup: restore %s names no restore point", request.GetRestoreId())
	}
	if err := checkKey(request.GetLocation()); err != nil {
		return fmt.Errorf("backup: restore %s names a location that is not an object key: %w",
			request.GetRestoreId(), err)
	}

	switch request.GetKind() {
	case wisperpb.BackupTargetKind_BACKUP_TARGET_KIND_VOLUME:
		if err := checkIdentifier("workload id", request.GetWorkloadId()); err != nil {
			return fmt.Errorf("backup: a volume restore needs the workload that owns it: %w", err)
		}
	case wisperpb.BackupTargetKind_BACKUP_TARGET_KIND_DATABASE:
		if request.GetEngine() == wisperpb.DatabaseEngine_DATABASE_ENGINE_UNSPECIFIED {
			return fmt.Errorf("backup: restore %s is of a database but names no engine",
				request.GetRestoreId())
		}
		if request.GetDatabaseName() == "" {
			return fmt.Errorf("backup: restore %s is of a database but names no database",
				request.GetRestoreId())
		}
	default:
		return fmt.Errorf("backup: restore %s asks for target kind %s, which this node does not "+
			"put back", request.GetRestoreId(), request.GetKind())
	}

	if request.GetSource() == nil {
		return fmt.Errorf("backup: restore %s says nowhere to read from", request.GetRestoreId())
	}
	return nil
}

// timeoutFor is how long the whole operation gets.
//
// A ceiling as well as a floor. A command with no timeout gets two hours, and one asking for
// a week gets a day: the point of the field is to stop a stuck backup from running into the
// next scheduled one, and a value large enough to defeat that is a value nobody meant.
func timeoutFor(seconds int64) time.Duration {
	const ceiling = 24 * time.Hour
	if seconds <= 0 {
		return defaultTimeout
	}
	requested := time.Duration(seconds) * time.Second
	if requested > ceiling {
		return ceiling
	}
	return requested
}
