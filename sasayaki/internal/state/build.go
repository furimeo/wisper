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

// Build history: what this node has been asked to build, and what came of it.
//
// It exists for two things a running build cannot survive without. The first is
// idempotency: the control stream drops, the panel resends StartBuild, and a second clone
// and compile of the same commit is wasted minutes on a machine that has customers on it.
// The second is the answer after a crash - a build that was in flight when the daemon died
// has a row saying so, and the panel is told it failed instead of waiting for a
// CommandResult that will never arrive.
//
// The finished BuildCompleted is stored whole, because it is what the panel asked for and
// re-deriving it from columns would mean a migration here every time build.proto gains a
// field.
var buildSchema = []string{`
CREATE TABLE build_run (
	build_id    TEXT PRIMARY KEY,
	workload_id TEXT    NOT NULL,
	release_id  TEXT    NOT NULL,
	stage       INTEGER NOT NULL,
	started_at  INTEGER NOT NULL,
	updated_at  INTEGER NOT NULL,
	finished_at INTEGER,
	success     INTEGER,
	detail      TEXT    NOT NULL,
	result      BLOB
) STRICT`, `
CREATE INDEX build_run_history ON build_run (workload_id, started_at DESC)`}

// BuildRun is one build, in flight or finished.
type BuildRun struct {
	// The panel's deployment id, which is also the release id: one deployment produces one
	// release, and two names for the same thing is a join nobody needs.
	BuildID    string
	WorkloadID string
	ReleaseID  string
	// How far it got. Where a build stopped is the first thing a customer needs: a failure
	// at FETCH is their repository, at BUILD it is their code.
	Stage      wisperpb.BuildStage
	StartedAt  time.Time
	UpdatedAt  time.Time
	FinishedAt time.Time
	// Finished distinguishes "still running" from "finished and failed"; Success is only
	// meaningful once Finished is true.
	Finished bool
	Success  bool
	// The last thing worth saying, in the words the panel will show. The full output went
	// up the log stream while the build ran.
	Detail string
	// The completed result, nil while the build is still running.
	Result *wisperpb.BuildCompleted
}

// BeginBuild records that a build has started.
//
// ErrAlreadyExists when that id has been seen before. The caller answers a duplicate by
// reading the existing row: a finished one is replayed to the panel as the result of the
// resent command, and an unfinished one means the build is still going and must not be
// started twice.
func (s *Store) BeginBuild(ctx context.Context, run BuildRun) error {
	if run.BuildID == "" {
		return errors.New("state: a build run needs a build id")
	}
	if run.WorkloadID == "" {
		return fmt.Errorf("state: build %s names no workload", run.BuildID)
	}

	return s.transact(ctx, func(tx *sql.Tx) error {
		var existing string
		err := tx.QueryRowContext(ctx, `SELECT build_id FROM build_run WHERE build_id = ?`, run.BuildID).Scan(&existing)
		switch {
		case err == nil:
			return fmt.Errorf("state: build %s has already been started: %w", run.BuildID, ErrAlreadyExists)
		case !errors.Is(err, sql.ErrNoRows):
			return fmt.Errorf("state: look up build %s: %w", run.BuildID, err)
		}

		if _, err := tx.ExecContext(ctx, `
INSERT INTO build_run (build_id, workload_id, release_id, stage, started_at, updated_at, finished_at, success, detail, result)
VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?, NULL)`,
			run.BuildID, run.WorkloadID, run.ReleaseID, int64(run.Stage),
			epochMillis(run.StartedAt), epochMillis(run.StartedAt), run.Detail,
		); err != nil {
			return fmt.Errorf("state: record the start of build %s: %w", run.BuildID, err)
		}
		return nil
	})
}

// RecordBuildStage moves a running build to its next stage.
//
// Written down rather than kept in the goroutine doing the work, so a build that is
// killed - by an upgrade, by the machine going down - leaves behind where it had got to
// instead of an unexplained gap.
func (s *Store) RecordBuildStage(ctx context.Context, buildID string, stage wisperpb.BuildStage, at time.Time, detail string) error {
	result, err := s.db.ExecContext(ctx,
		`UPDATE build_run SET stage = ?, updated_at = ?, detail = ? WHERE build_id = ? AND finished_at IS NULL`,
		int64(stage), epochMillis(at), detail, buildID)
	if err != nil {
		return fmt.Errorf("state: record stage %s of build %s: %w", stage, buildID, err)
	}
	return requireOneRow(result, fmt.Sprintf("running build %s", buildID))
}

// FinishBuild stores the outcome. Called for a failure as well as a success: a build that
// simply stops being mentioned is one the panel waits on forever.
func (s *Store) FinishBuild(ctx context.Context, buildID string, completed *wisperpb.BuildCompleted, at time.Time) error {
	if completed == nil {
		return fmt.Errorf("state: build %s cannot finish without a result", buildID)
	}
	payload, err := proto.MarshalOptions{Deterministic: true}.Marshal(completed)
	if err != nil {
		return fmt.Errorf("state: encode the result of build %s: %w", buildID, err)
	}

	stage := completed.GetFailedStage()
	if completed.GetSuccess() {
		stage = wisperpb.BuildStage_BUILD_STAGE_PUBLISH
	}

	outcome, err := s.db.ExecContext(ctx, `
UPDATE build_run SET
	stage       = ?,
	release_id  = ?,
	updated_at  = ?,
	finished_at = ?,
	success     = ?,
	detail      = ?,
	result      = ?
WHERE build_id = ?`,
		int64(stage), completed.GetReleaseId(), epochMillis(at), epochMillis(at),
		boolean(completed.GetSuccess()), completed.GetDetail(), payload, buildID)
	if err != nil {
		return fmt.Errorf("state: record the result of build %s: %w", buildID, err)
	}
	return requireOneRow(outcome, fmt.Sprintf("build %s", buildID))
}

// Build is one run, or ErrNotFound.
func (s *Store) Build(ctx context.Context, buildID string) (BuildRun, error) {
	row := s.db.QueryRowContext(ctx, buildColumns+` FROM build_run WHERE build_id = ?`, buildID)
	run, err := scanBuild(row)
	if errors.Is(err, sql.ErrNoRows) {
		return BuildRun{}, fmt.Errorf("state: build %s: %w", buildID, ErrNotFound)
	}
	return run, err
}

// Builds lists a workload's history, newest first. An empty workloadID lists every
// workload's, which is what an operator looking at a node wants. limit of zero means no
// limit.
func (s *Store) Builds(ctx context.Context, workloadID string, limit int) ([]BuildRun, error) {
	query := buildColumns + ` FROM build_run`
	var arguments []any
	if workloadID != "" {
		query += ` WHERE workload_id = ?`
		arguments = append(arguments, workloadID)
	}
	query += ` ORDER BY started_at DESC, build_id DESC`
	if limit > 0 {
		query += ` LIMIT ?`
		arguments = append(arguments, limit)
	}
	return queryBuilds(ctx, s.db, query, arguments...)
}

// UnfinishedBuilds is every build that was in flight, which after a restart means every
// build that died with the daemon.
//
// The daemon reports these as failed on startup rather than leaving them. A deployment
// that is stuck at "building" forever is the state a customer cannot get out of on their
// own, and the node is the only side that knows it is not still happening.
func (s *Store) UnfinishedBuilds(ctx context.Context) ([]BuildRun, error) {
	return queryBuilds(ctx, s.db,
		buildColumns+` FROM build_run WHERE finished_at IS NULL ORDER BY started_at`)
}

// PruneBuilds keeps the most recent keepPerWorkload finished builds of each workload and
// removes the rest, reporting how many it dropped.
//
// Unfinished runs are never pruned: they are the ones something is still waiting on. The
// history has to be bounded by something, because a node that builds on every push
// accumulates rows forever and this database is on the same disk as the customers' data.
func (s *Store) PruneBuilds(ctx context.Context, keepPerWorkload int) (int64, error) {
	if keepPerWorkload < 0 {
		return 0, fmt.Errorf("state: cannot keep %d builds per workload", keepPerWorkload)
	}
	result, err := s.db.ExecContext(ctx, `
DELETE FROM build_run WHERE build_id IN (
	SELECT build_id FROM (
		SELECT build_id, ROW_NUMBER() OVER (
			PARTITION BY workload_id ORDER BY started_at DESC, build_id DESC
		) AS position
		FROM build_run
		WHERE finished_at IS NOT NULL
	)
	WHERE position > ?
)`, keepPerWorkload)
	if err != nil {
		return 0, fmt.Errorf("state: prune build history: %w", err)
	}
	removed, err := result.RowsAffected()
	if err != nil {
		return 0, fmt.Errorf("state: prune build history: %w", err)
	}
	return removed, nil
}

const buildColumns = `
SELECT build_id, workload_id, release_id, stage, started_at, updated_at, finished_at, success, detail, result`

func queryBuilds(ctx context.Context, q queryer, query string, arguments ...any) ([]BuildRun, error) {
	rows, err := q.QueryContext(ctx, query, arguments...)
	if err != nil {
		return nil, fmt.Errorf("state: read build history: %w", err)
	}
	defer rows.Close()

	var out []BuildRun
	for rows.Next() {
		run, err := scanBuild(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, run)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("state: read build history: %w", err)
	}
	return out, nil
}

func scanBuild(row scanner) (BuildRun, error) {
	var (
		run        BuildRun
		stage      int64
		startedAt  int64
		updatedAt  int64
		finishedAt sql.NullInt64
		success    sql.NullInt64
		payload    []byte
	)
	err := row.Scan(&run.BuildID, &run.WorkloadID, &run.ReleaseID, &stage,
		&startedAt, &updatedAt, &finishedAt, &success, &run.Detail, &payload)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return BuildRun{}, err
		}
		return BuildRun{}, fmt.Errorf("state: read a build run: %w", err)
	}

	run.Stage = wisperpb.BuildStage(stage)
	run.StartedAt = instant(startedAt)
	run.UpdatedAt = instant(updatedAt)
	run.FinishedAt = optionalInstant(finishedAt)
	run.Finished = finishedAt.Valid
	run.Success = success.Valid && success.Int64 != 0

	if len(payload) > 0 {
		completed := &wisperpb.BuildCompleted{}
		if err := proto.Unmarshal(payload, completed); err != nil {
			return BuildRun{}, fmt.Errorf("state: decode the stored result of build %s: %w", run.BuildID, err)
		}
		run.Result = completed
	}
	return run, nil
}

// requireOneRow turns "the UPDATE matched nothing" into ErrNotFound. Without it a stage
// recorded against a build id that does not exist succeeds silently, and the bug surfaces
// as a build whose progress never moves.
func requireOneRow(result sql.Result, subject string) error {
	affected, err := result.RowsAffected()
	if err != nil {
		return fmt.Errorf("state: %s: %w", subject, err)
	}
	if affected == 0 {
		return fmt.Errorf("state: %s: %w", subject, ErrNotFound)
	}
	return nil
}
