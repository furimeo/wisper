package state

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"
)

// The last run of each cron entry.
//
// Cron is the one scheduled thing that lives in the NodeSpec rather than in the panel's
// job queue, because it is the customer's schedule and it has to keep firing while the
// panel is unreachable (workload.proto, CronEntry). That makes the node the only place its
// history can live, and a daemon that forgot it on restart would report "never ran" for a
// job that has run every night for a year - and would run a job twice on a machine that
// was restarted a second after it fired.
//
// One row per entry, not one per execution. What the panel renders is CronStatus - the
// last run, the next run, whether one is in flight - and keeping every execution forever
// would grow a table on a customer's disk to answer a question nobody asks.
var cronSchema = []string{`
CREATE TABLE cron_run (
	cron_id          TEXT PRIMARY KEY,
	workload_id      TEXT    NOT NULL,
	running          INTEGER NOT NULL,
	started_at       INTEGER,
	last_run_at      INTEGER,
	last_finished_at INTEGER,
	next_run_at      INTEGER,
	last_exit_code   INTEGER NOT NULL,
	last_skipped     INTEGER NOT NULL,
	last_error       TEXT    NOT NULL
) STRICT`}

// CronRun is one cron entry's history, which is exactly what a CronStatus is built from.
type CronRun struct {
	CronID     string
	WorkloadID string
	// True while an execution is in flight, so the panel does not show "never ran" for a
	// job that is running right now.
	Running bool
	// When the execution currently in flight began. Zero when none is.
	StartedAt time.Time
	// The last execution that actually started, whether or not it has finished.
	LastRunAt      time.Time
	LastFinishedAt time.Time
	// When the schedule says it fires next. Computed by whoever owns the cron expression
	// and stored here so a restart does not have to wait for the next tick to know.
	NextRunAt    time.Time
	LastExitCode int32
	// The last run was skipped because the previous one had not finished and the entry does
	// not allow overlap. Recorded, because a schedule that appears not to be firing needs
	// to say why.
	LastSkipped bool
	LastError   string
}

// StartCronRun records that an execution has begun.
//
// Written before the command is launched, not after it returns. A daemon killed while a
// job is running leaves running = true, and ClearRunningCronRuns turns that into an honest
// "this was interrupted" on the next start - where recording it afterwards would leave no
// trace of the run at all.
func (s *Store) StartCronRun(ctx context.Context, cronID, workloadID string, at time.Time) error {
	if cronID == "" {
		return errors.New("state: a cron run needs a cron id")
	}
	_, err := s.db.ExecContext(ctx, `
INSERT INTO cron_run (
	cron_id, workload_id, running, started_at, last_run_at, last_finished_at,
	next_run_at, last_exit_code, last_skipped, last_error
) VALUES (?, ?, 1, ?, ?, NULL, NULL, 0, 0, '')
ON CONFLICT (cron_id) DO UPDATE SET
	workload_id  = excluded.workload_id,
	running      = 1,
	started_at   = excluded.started_at,
	last_run_at  = excluded.last_run_at,
	last_skipped = 0`,
		cronID, workloadID, epochMillis(at), epochMillis(at))
	if err != nil {
		return fmt.Errorf("state: record the start of cron entry %s: %w", cronID, err)
	}
	return nil
}

// FinishCronRun records how an execution ended. A non-zero exit code is a result, not an
// error to this method: the customer's script failing is information the panel shows, and
// detail carries whatever the runner wants to say about it.
func (s *Store) FinishCronRun(ctx context.Context, cronID string, exitCode int32, at time.Time, detail string) error {
	result, err := s.db.ExecContext(ctx, `
UPDATE cron_run SET
	running          = 0,
	started_at       = NULL,
	last_finished_at = ?,
	last_exit_code   = ?,
	last_error       = ?
WHERE cron_id = ?`,
		epochMillis(at), int64(exitCode), detail, cronID)
	if err != nil {
		return fmt.Errorf("state: record the end of cron entry %s: %w", cronID, err)
	}
	return requireOneRow(result, fmt.Sprintf("cron entry %s", cronID))
}

// SkipCronRun records a firing that was not run because the previous execution had not
// finished and the entry does not allow overlap.
func (s *Store) SkipCronRun(ctx context.Context, cronID, workloadID string, at time.Time) error {
	if cronID == "" {
		return errors.New("state: a skipped cron run needs a cron id")
	}
	_, err := s.db.ExecContext(ctx, `
INSERT INTO cron_run (
	cron_id, workload_id, running, started_at, last_run_at, last_finished_at,
	next_run_at, last_exit_code, last_skipped, last_error
) VALUES (?, ?, 0, NULL, NULL, NULL, NULL, 0, 1, ?)
ON CONFLICT (cron_id) DO UPDATE SET
	workload_id  = excluded.workload_id,
	last_skipped = 1,
	last_error   = excluded.last_error`,
		cronID, workloadID, skipReason(at))
	if err != nil {
		return fmt.Errorf("state: record the skipped run of cron entry %s: %w", cronID, err)
	}
	return nil
}

func skipReason(at time.Time) string {
	return "skipped at " + at.UTC().Format(time.RFC3339) + ": the previous run had not finished"
}

// ScheduleCronRun stores when the entry fires next, so a restarted daemon can report it
// without waiting for the schedule to be evaluated again.
func (s *Store) ScheduleCronRun(ctx context.Context, cronID, workloadID string, next time.Time) error {
	if cronID == "" {
		return errors.New("state: a cron schedule needs a cron id")
	}
	_, err := s.db.ExecContext(ctx, `
INSERT INTO cron_run (
	cron_id, workload_id, running, started_at, last_run_at, last_finished_at,
	next_run_at, last_exit_code, last_skipped, last_error
) VALUES (?, ?, 0, NULL, NULL, NULL, ?, 0, 0, '')
ON CONFLICT (cron_id) DO UPDATE SET
	workload_id = excluded.workload_id,
	next_run_at = excluded.next_run_at`,
		cronID, workloadID, nullableMillis(next))
	if err != nil {
		return fmt.Errorf("state: record the next run of cron entry %s: %w", cronID, err)
	}
	return nil
}

// CronRuns is every entry's history, by cron id.
func (s *Store) CronRuns(ctx context.Context) ([]CronRun, error) {
	rows, err := s.db.QueryContext(ctx, `
SELECT cron_id, workload_id, running, started_at, last_run_at, last_finished_at,
       next_run_at, last_exit_code, last_skipped, last_error
FROM cron_run ORDER BY cron_id`)
	if err != nil {
		return nil, fmt.Errorf("state: read cron history: %w", err)
	}
	defer rows.Close()

	var out []CronRun
	for rows.Next() {
		var (
			run          CronRun
			running      int64
			startedAt    sql.NullInt64
			lastRunAt    sql.NullInt64
			lastFinished sql.NullInt64
			nextRunAt    sql.NullInt64
			exitCode     int64
			skipped      int64
		)
		if err := rows.Scan(&run.CronID, &run.WorkloadID, &running, &startedAt, &lastRunAt,
			&lastFinished, &nextRunAt, &exitCode, &skipped, &run.LastError); err != nil {
			return nil, fmt.Errorf("state: read cron history: %w", err)
		}
		run.Running = running != 0
		run.StartedAt = optionalInstant(startedAt)
		run.LastRunAt = optionalInstant(lastRunAt)
		run.LastFinishedAt = optionalInstant(lastFinished)
		run.NextRunAt = optionalInstant(nextRunAt)
		run.LastExitCode = int32(exitCode)
		run.LastSkipped = skipped != 0
		out = append(out, run)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("state: read cron history: %w", err)
	}
	return out, nil
}

// ClearRunningCronRuns turns every execution that was in flight into an interrupted one,
// and reports how many it found.
//
// Called once on startup. The daemon is crash-only, so a row saying "running" after a
// restart means the process died with the job, not that anything is still going; leaving
// it would make the entry look permanently busy and, where overlap is not allowed, stop it
// ever firing again.
func (s *Store) ClearRunningCronRuns(ctx context.Context, at time.Time) (int64, error) {
	result, err := s.db.ExecContext(ctx, `
UPDATE cron_run SET
	running          = 0,
	started_at       = NULL,
	last_finished_at = ?,
	last_exit_code   = -1,
	last_error       = 'interrupted: the daemon stopped while this run was in flight'
WHERE running <> 0`, epochMillis(at))
	if err != nil {
		return 0, fmt.Errorf("state: clear interrupted cron runs: %w", err)
	}
	cleared, err := result.RowsAffected()
	if err != nil {
		return 0, fmt.Errorf("state: clear interrupted cron runs: %w", err)
	}
	return cleared, nil
}

// PruneCronRuns forgets every entry that is not in keep, which is the set of cron ids in
// the current spec. An entry the customer deleted should stop being reported.
func (s *Store) PruneCronRuns(ctx context.Context, keep []string) (int64, error) {
	query := `DELETE FROM cron_run`
	var arguments []any
	if len(keep) > 0 {
		query += ` WHERE cron_id NOT IN (` + placeholders(len(keep)) + `)`
		arguments = anySlice(keep)
	}

	result, err := s.db.ExecContext(ctx, query, arguments...)
	if err != nil {
		return 0, fmt.Errorf("state: prune cron history: %w", err)
	}
	removed, err := result.RowsAffected()
	if err != nil {
		return 0, fmt.Errorf("state: prune cron history: %w", err)
	}
	return removed, nil
}
