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

// Restore runs: the other half of a backup, and the half that decides whether the first
// one was worth taking.
//
// A separate table from backup_run rather than a flag on it. A restore has its own id,
// answers a different command, can be a dry run that deliberately does not overwrite
// anything, and is the operation that stops a workload - and one table holding two
// operations with different lifecycles is where a query starts having to say "and this
// column only applies when that one is set".
//
// Its own row also makes the crash case answerable. A restore killed halfway has left a
// volume partly overwritten and possibly a workload stopped, and the node is the only side
// that knows which. A restore that simply vanished would leave a customer's application
// down with nothing on any screen explaining why.
var restoreSchema = []string{`
CREATE TABLE restore_run (
	restore_id       TEXT PRIMARY KEY,
	restore_point_id TEXT    NOT NULL,
	kind             INTEGER NOT NULL,
	subject_id       TEXT    NOT NULL,
	workload_id      TEXT    NOT NULL,
	dry_run          INTEGER NOT NULL,
	stop_workload    INTEGER NOT NULL,
	started_at       INTEGER NOT NULL,
	updated_at       INTEGER NOT NULL,
	finished_at      INTEGER,
	success          INTEGER,
	detail           TEXT    NOT NULL,
	result           BLOB
) STRICT`, `
CREATE INDEX restore_run_history ON restore_run (subject_id, started_at DESC)`}

// RestoreRun is one restore attempt, in flight or finished.
type RestoreRun struct {
	// The panel's id for this attempt, distinct from the backup's.
	RestoreID      string
	RestorePointID string
	Kind           wisperpb.BackupTargetKind
	SubjectID      string
	WorkloadID     string
	// Restored alongside instead of over the top, so a customer can look before
	// committing. This is what makes testing a restore safe enough that people do it.
	DryRun bool
	// The workload was to be stopped for the duration. Recorded because a crash mid-restore
	// leaves it stopped, and whoever cleans up has to know that was intentional.
	StopWorkload bool
	StartedAt    time.Time
	UpdatedAt    time.Time
	FinishedAt   time.Time
	Finished     bool
	Success      bool
	Detail       string
	// The completed result, nil while the restore is still running.
	Result *wisperpb.RestoreCompleted
}

// BeginRestore records that a restore has started, and returns ErrAlreadyExists for an id
// that has been seen before.
//
// Idempotency matters more here than anywhere else in this package. A resent RestoreBackup
// that ran twice would overwrite a volume that the first run had already put back,
// possibly while an application was reading it.
func (s *Store) BeginRestore(ctx context.Context, run RestoreRun) error {
	if run.RestoreID == "" {
		return errors.New("state: a restore run needs a restore id")
	}
	if run.RestorePointID == "" {
		return fmt.Errorf("state: restore %s names no restore point", run.RestoreID)
	}

	return s.transact(ctx, func(tx *sql.Tx) error {
		var existing string
		err := tx.QueryRowContext(ctx, `SELECT restore_id FROM restore_run WHERE restore_id = ?`, run.RestoreID).Scan(&existing)
		switch {
		case err == nil:
			return fmt.Errorf("state: restore %s has already been started: %w", run.RestoreID, ErrAlreadyExists)
		case !errors.Is(err, sql.ErrNoRows):
			return fmt.Errorf("state: look up restore %s: %w", run.RestoreID, err)
		}

		if _, err := tx.ExecContext(ctx, `
INSERT INTO restore_run (
	restore_id, restore_point_id, kind, subject_id, workload_id, dry_run, stop_workload,
	started_at, updated_at, finished_at, success, detail, result
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, NULL)`,
			run.RestoreID, run.RestorePointID, int64(run.Kind), run.SubjectID, run.WorkloadID,
			boolean(run.DryRun), boolean(run.StopWorkload),
			epochMillis(run.StartedAt), epochMillis(run.StartedAt), run.Detail,
		); err != nil {
			return fmt.Errorf("state: record the start of restore %s: %w", run.RestoreID, err)
		}
		return nil
	})
}

// RecordRestoreProgress notes what a running restore is doing.
//
// RestoreCompleted has no stages, so this is a line of text rather than an enum - and it
// is still worth writing down, because the thing a customer watches during a restore of a
// forty-gigabyte volume is any evidence at all that it is moving.
func (s *Store) RecordRestoreProgress(ctx context.Context, restoreID string, at time.Time, detail string) error {
	if detail == "" {
		return fmt.Errorf("state: restore %s: progress with no detail says nothing", restoreID)
	}
	result, err := s.db.ExecContext(ctx,
		`UPDATE restore_run SET updated_at = ?, detail = ? WHERE restore_id = ? AND finished_at IS NULL`,
		epochMillis(at), detail, restoreID)
	if err != nil {
		return fmt.Errorf("state: record the progress of restore %s: %w", restoreID, err)
	}
	return requireOneRow(result, fmt.Sprintf("running restore %s", restoreID))
}

// FinishRestore stores the outcome, success or failure.
func (s *Store) FinishRestore(ctx context.Context, restoreID string, completed *wisperpb.RestoreCompleted, at time.Time) error {
	if completed == nil {
		return fmt.Errorf("state: restore %s cannot finish without a result", restoreID)
	}
	payload, err := proto.MarshalOptions{Deterministic: true}.Marshal(completed)
	if err != nil {
		return fmt.Errorf("state: encode the result of restore %s: %w", restoreID, err)
	}

	outcome, err := s.db.ExecContext(ctx, `
UPDATE restore_run SET
	updated_at  = ?,
	finished_at = ?,
	success     = ?,
	detail      = ?,
	result      = ?
WHERE restore_id = ?`,
		epochMillis(at), epochMillis(at), boolean(completed.GetSuccess()),
		completed.GetDetail(), payload, restoreID)
	if err != nil {
		return fmt.Errorf("state: record the result of restore %s: %w", restoreID, err)
	}
	return requireOneRow(outcome, fmt.Sprintf("restore %s", restoreID))
}

// Restore is one attempt, or ErrNotFound.
func (s *Store) Restore(ctx context.Context, restoreID string) (RestoreRun, error) {
	row := s.db.QueryRowContext(ctx, restoreColumns+` FROM restore_run WHERE restore_id = ?`, restoreID)
	run, err := scanRestore(row)
	if errors.Is(err, sql.ErrNoRows) {
		return RestoreRun{}, fmt.Errorf("state: restore %s: %w", restoreID, ErrNotFound)
	}
	return run, err
}

// Restores lists one subject's restore attempts, newest first. An empty subjectID lists
// every subject's; a limit of zero means no limit.
func (s *Store) Restores(ctx context.Context, subjectID string, limit int) ([]RestoreRun, error) {
	query := restoreColumns + ` FROM restore_run`
	var arguments []any
	if subjectID != "" {
		query += ` WHERE subject_id = ?`
		arguments = append(arguments, subjectID)
	}
	query += ` ORDER BY started_at DESC, restore_id DESC`
	if limit > 0 {
		query += ` LIMIT ?`
		arguments = append(arguments, limit)
	}
	return queryRestores(ctx, s.db, query, arguments...)
}

// UnfinishedRestores is every restore that was in flight when the daemon stopped. Each one
// is a volume that may be half written and a workload that may still be stopped.
func (s *Store) UnfinishedRestores(ctx context.Context) ([]RestoreRun, error) {
	return queryRestores(ctx, s.db,
		restoreColumns+` FROM restore_run WHERE finished_at IS NULL ORDER BY started_at`)
}

const restoreColumns = `
SELECT restore_id, restore_point_id, kind, subject_id, workload_id, dry_run, stop_workload,
       started_at, updated_at, finished_at, success, detail, result`

func queryRestores(ctx context.Context, q queryer, query string, arguments ...any) ([]RestoreRun, error) {
	rows, err := q.QueryContext(ctx, query, arguments...)
	if err != nil {
		return nil, fmt.Errorf("state: read restore history: %w", err)
	}
	defer rows.Close()

	var out []RestoreRun
	for rows.Next() {
		run, err := scanRestore(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, run)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("state: read restore history: %w", err)
	}
	return out, nil
}

func scanRestore(row scanner) (RestoreRun, error) {
	var (
		run          RestoreRun
		kind         int64
		dryRun       int64
		stopWorkload int64
		startedAt    int64
		updatedAt    int64
		finishedAt   sql.NullInt64
		success      sql.NullInt64
		payload      []byte
	)
	err := row.Scan(&run.RestoreID, &run.RestorePointID, &kind, &run.SubjectID, &run.WorkloadID,
		&dryRun, &stopWorkload, &startedAt, &updatedAt, &finishedAt, &success, &run.Detail, &payload)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return RestoreRun{}, err
		}
		return RestoreRun{}, fmt.Errorf("state: read a restore run: %w", err)
	}

	run.Kind = wisperpb.BackupTargetKind(kind)
	run.DryRun = dryRun != 0
	run.StopWorkload = stopWorkload != 0
	run.StartedAt = instant(startedAt)
	run.UpdatedAt = instant(updatedAt)
	run.FinishedAt = optionalInstant(finishedAt)
	run.Finished = finishedAt.Valid
	run.Success = success.Valid && success.Int64 != 0

	if len(payload) > 0 {
		completed := &wisperpb.RestoreCompleted{}
		if err := proto.Unmarshal(payload, completed); err != nil {
			return RestoreRun{}, fmt.Errorf("state: decode the stored result of restore %s: %w", run.RestoreID, err)
		}
		run.Result = completed
	}
	return run, nil
}
