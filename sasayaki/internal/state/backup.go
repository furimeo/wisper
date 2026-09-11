package state

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
	"google.golang.org/protobuf/proto"
)

// Backup runs: what this node has been asked to copy, and where it put it.
//
// backup.proto is explicit that "a repeated command with the same id is the same backup,
// not a second one", and this table is what makes that true across a restart. Without it a
// panel that resent RunBackup after a dropped stream would get two archives, two uploads
// of the same data and a retention rule that counts them as two generations.
//
// What is deliberately not here: the destination credentials. They arrive with each
// command because the node is the side with the bytes and the bandwidth, and they are
// never written to disk - /var/lib/wisper survives an uninstall, and a customer's object
// store keys surviving with it is not a trade this platform makes.
var backupSchema = []string{`
CREATE TABLE backup_run (
	backup_id        TEXT PRIMARY KEY,
	kind             INTEGER NOT NULL,
	subject_id       TEXT    NOT NULL,
	workload_id      TEXT    NOT NULL,
	stage            INTEGER NOT NULL,
	restore_point_id TEXT    NOT NULL,
	location         TEXT    NOT NULL,
	started_at       INTEGER NOT NULL,
	updated_at       INTEGER NOT NULL,
	finished_at      INTEGER,
	success          INTEGER,
	detail           TEXT    NOT NULL,
	result           BLOB
) STRICT`, `
CREATE INDEX backup_run_history ON backup_run (subject_id, started_at DESC)`}

// BackupRun is one snapshot, in flight or finished.
type BackupRun struct {
	// The panel's id for this run. The restore point is recorded against it.
	BackupID string
	Kind     wisperpb.BackupTargetKind
	// Volume id or database grant id, depending on the kind.
	SubjectID string
	// The workload whose writes were paused for the snapshot.
	WorkloadID string
	Stage      wisperpb.BackupStage
	// Minted by the node once the archive has a final name, because the node is what knows
	// it. Empty until then.
	RestorePointID string
	// The S3 key or the local relative path: enough to find the archive by hand when the
	// panel's row is the thing that was lost.
	Location   string
	StartedAt  time.Time
	UpdatedAt  time.Time
	FinishedAt time.Time
	Finished   bool
	Success    bool
	Detail     string
	// The completed result, nil while the backup is still running.
	Result *wisperpb.BackupCompleted
}

// BeginBackup records that a backup has started, and returns ErrAlreadyExists for an id
// that has been seen before. The caller replays the finished row to the panel rather than
// taking the snapshot again.
func (s *Store) BeginBackup(ctx context.Context, run BackupRun) error {
	if run.BackupID == "" {
		return errors.New("state: a backup run needs a backup id")
	}
	if run.SubjectID == "" {
		return fmt.Errorf("state: backup %s names no subject", run.BackupID)
	}

	return s.transact(ctx, func(tx *sql.Tx) error {
		var existing string
		err := tx.QueryRowContext(ctx, `SELECT backup_id FROM backup_run WHERE backup_id = ?`, run.BackupID).Scan(&existing)
		switch {
		case err == nil:
			return fmt.Errorf("state: backup %s has already been started: %w", run.BackupID, ErrAlreadyExists)
		case !errors.Is(err, sql.ErrNoRows):
			return fmt.Errorf("state: look up backup %s: %w", run.BackupID, err)
		}

		if _, err := tx.ExecContext(ctx, `
INSERT INTO backup_run (
	backup_id, kind, subject_id, workload_id, stage, restore_point_id, location,
	started_at, updated_at, finished_at, success, detail, result
) VALUES (?, ?, ?, ?, ?, '', '', ?, ?, NULL, NULL, ?, NULL)`,
			run.BackupID, int64(run.Kind), run.SubjectID, run.WorkloadID, int64(run.Stage),
			epochMillis(run.StartedAt), epochMillis(run.StartedAt), run.Detail,
		); err != nil {
			return fmt.Errorf("state: record the start of backup %s: %w", run.BackupID, err)
		}
		return nil
	})
}

// RecordBackupStage moves a running backup on.
//
// Which stage a backup is in is the difference between an application that is paused right
// now and one that is not: QUIESCE is the only stage a customer feels. A daemon that
// dies during it leaves the stage on disk, so whoever looks afterwards knows to check
// whether the workload was left stopped.
func (s *Store) RecordBackupStage(ctx context.Context, backupID string, stage wisperpb.BackupStage, at time.Time, detail string) error {
	result, err := s.db.ExecContext(ctx,
		`UPDATE backup_run SET stage = ?, updated_at = ?, detail = ? WHERE backup_id = ? AND finished_at IS NULL`,
		int64(stage), epochMillis(at), detail, backupID)
	if err != nil {
		return fmt.Errorf("state: record stage %s of backup %s: %w", stage, backupID, err)
	}
	return requireOneRow(result, fmt.Sprintf("running backup %s", backupID))
}

// FinishBackup stores the outcome, success or failure.
func (s *Store) FinishBackup(ctx context.Context, backupID string, completed *wisperpb.BackupCompleted, at time.Time) error {
	if completed == nil {
		return fmt.Errorf("state: backup %s cannot finish without a result", backupID)
	}
	payload, err := proto.MarshalOptions{Deterministic: true}.Marshal(completed)
	if err != nil {
		return fmt.Errorf("state: encode the result of backup %s: %w", backupID, err)
	}

	stage := completed.GetFailedStage()
	if completed.GetSuccess() {
		stage = wisperpb.BackupStage_BACKUP_STAGE_PRUNE
	}

	outcome, err := s.db.ExecContext(ctx, `
UPDATE backup_run SET
	stage            = ?,
	restore_point_id = ?,
	location         = ?,
	updated_at       = ?,
	finished_at      = ?,
	success          = ?,
	detail           = ?,
	result           = ?
WHERE backup_id = ?`,
		int64(stage), completed.GetRestorePointId(), completed.GetLocation(),
		epochMillis(at), epochMillis(at), boolean(completed.GetSuccess()),
		completed.GetDetail(), payload, backupID)
	if err != nil {
		return fmt.Errorf("state: record the result of backup %s: %w", backupID, err)
	}
	return requireOneRow(outcome, fmt.Sprintf("backup %s", backupID))
}

// Backup is one run, or ErrNotFound.
func (s *Store) Backup(ctx context.Context, backupID string) (BackupRun, error) {
	row := s.db.QueryRowContext(ctx, backupColumns+` FROM backup_run WHERE backup_id = ?`, backupID)
	run, err := scanBackup(row)
	if errors.Is(err, sql.ErrNoRows) {
		return BackupRun{}, fmt.Errorf("state: backup %s: %w", backupID, ErrNotFound)
	}
	return run, err
}

// Backups lists one subject's history, newest first. An empty subjectID lists every
// subject's; a limit of zero means no limit.
func (s *Store) Backups(ctx context.Context, subjectID string, limit int) ([]BackupRun, error) {
	query := backupColumns + ` FROM backup_run`
	var arguments []any
	if subjectID != "" {
		query += ` WHERE subject_id = ?`
		arguments = append(arguments, subjectID)
	}
	query += ` ORDER BY started_at DESC, backup_id DESC`
	if limit > 0 {
		query += ` LIMIT ?`
		arguments = append(arguments, limit)
	}
	return queryBackups(ctx, s.db, query, arguments...)
}

// UnfinishedBackups is every run that was in flight when the daemon stopped.
//
// The one that matters is a backup killed during QUIESCE, because the workload it paused
// may still be paused. Reporting these on startup is how a customer's application does not
// stay stopped after a backup nobody remembers starting.
func (s *Store) UnfinishedBackups(ctx context.Context) ([]BackupRun, error) {
	return queryBackups(ctx, s.db,
		backupColumns+` FROM backup_run WHERE finished_at IS NULL ORDER BY started_at`)
}

// PruneBackupRuns keeps the most recent keepPerSubject finished runs of each subject.
//
// This prunes the node's record of a backup, never the archive at the destination. Those
// are removed by the retention rule at the end of a successful backup, where the
// destination credentials are; deleting a customer's offsite copy because a local row aged
// out would be the worst kind of bug this file could contain.
func (s *Store) PruneBackupRuns(ctx context.Context, keepPerSubject int) (int64, error) {
	if keepPerSubject < 0 {
		return 0, fmt.Errorf("state: cannot keep %d backup runs per subject", keepPerSubject)
	}
	result, err := s.db.ExecContext(ctx, `
DELETE FROM backup_run WHERE backup_id IN (
	SELECT backup_id FROM (
		SELECT backup_id, ROW_NUMBER() OVER (
			PARTITION BY subject_id ORDER BY started_at DESC, backup_id DESC
		) AS position
		FROM backup_run
		WHERE finished_at IS NOT NULL
	)
	WHERE position > ?
)`, keepPerSubject)
	if err != nil {
		return 0, fmt.Errorf("state: prune backup history: %w", err)
	}
	removed, err := result.RowsAffected()
	if err != nil {
		return 0, fmt.Errorf("state: prune backup history: %w", err)
	}
	return removed, nil
}

const backupColumns = `
SELECT backup_id, kind, subject_id, workload_id, stage, restore_point_id, location,
       started_at, updated_at, finished_at, success, detail, result`

func queryBackups(ctx context.Context, q queryer, query string, arguments ...any) ([]BackupRun, error) {
	rows, err := q.QueryContext(ctx, query, arguments...)
	if err != nil {
		return nil, fmt.Errorf("state: read backup history: %w", err)
	}
	defer rows.Close()

	var out []BackupRun
	for rows.Next() {
		run, err := scanBackup(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, run)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("state: read backup history: %w", err)
	}
	return out, nil
}

func scanBackup(row scanner) (BackupRun, error) {
	var (
		run        BackupRun
		kind       int64
		stage      int64
		startedAt  int64
		updatedAt  int64
		finishedAt sql.NullInt64
		success    sql.NullInt64
		payload    []byte
	)
	err := row.Scan(&run.BackupID, &kind, &run.SubjectID, &run.WorkloadID, &stage,
		&run.RestorePointID, &run.Location, &startedAt, &updatedAt, &finishedAt,
		&success, &run.Detail, &payload)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return BackupRun{}, err
		}
		return BackupRun{}, fmt.Errorf("state: read a backup run: %w", err)
	}

	run.Kind = wisperpb.BackupTargetKind(kind)
	run.Stage = wisperpb.BackupStage(stage)
	run.StartedAt = instant(startedAt)
	run.UpdatedAt = instant(updatedAt)
	run.FinishedAt = optionalInstant(finishedAt)
	run.Finished = finishedAt.Valid
	run.Success = success.Valid && success.Int64 != 0

	if len(payload) > 0 {
		completed := &wisperpb.BackupCompleted{}
		if err := proto.Unmarshal(payload, completed); err != nil {
			return BackupRun{}, fmt.Errorf("state: decode the stored result of backup %s: %w", run.BackupID, err)
		}
		run.Result = completed
	}
	return run, nil
}
