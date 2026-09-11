package state

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"
)

// How far the reconcile loop has got. One row, alongside the one row holding the spec it
// is working towards.
//
// This is deliberately not a column on node_spec. Received and applied are two different
// facts about two different moments: a spec arrives in milliseconds and converges in
// minutes, and during that gap the panel needs to be told both numbers - it is the
// difference between "the node has not heard me" and "the node is still working on it".
var convergenceSchema = []string{`
CREATE TABLE convergence (
	id                 INTEGER PRIMARY KEY CHECK (id = 1),
	applied_generation INTEGER NOT NULL,
	applied_at         INTEGER NOT NULL,
	passes             INTEGER NOT NULL,
	last_pass_at       INTEGER NOT NULL,
	last_error         TEXT    NOT NULL
) STRICT`}

// Convergence is what the reconcile loop has achieved so far.
type Convergence struct {
	// The generation the last successful pass converged the machine to. This is the number
	// that goes in NodeHello and in every Heartbeat, and it is read straight off the disk
	// at startup - before Docker has been asked anything - so a node that reconnects
	// during a slow start still tells the panel the truth about where it was.
	AppliedGeneration uint64
	AppliedAt         time.Time
	// Passes since this database was created. A node whose pass count is not moving has a
	// loop that has stopped ticking, which is a different fault from one that is failing.
	Passes     uint64
	LastPassAt time.Time
	// Why the last pass did not finish, empty when it did. Carried into the heartbeat's
	// health_detail, because "degraded" on its own tells an operator nothing.
	LastError string
}

// MarkApplied records a reconcile pass that converged the machine to a generation.
//
// The applied generation only ever moves forward. A pass that ran against an older spec -
// because it started before a newer one arrived - must not report the node as having gone
// backwards, which would make the panel resend a spec the node already has.
func (s *Store) MarkApplied(ctx context.Context, generation uint64, at time.Time) error {
	return s.transact(ctx, func(tx *sql.Tx) error {
		current, err := readConvergence(ctx, tx)
		if err != nil {
			return err
		}
		next := current
		next.Passes++
		next.LastPassAt = at
		next.LastError = ""
		if generation >= current.AppliedGeneration {
			next.AppliedGeneration = generation
			next.AppliedAt = at
		}
		return writeConvergence(ctx, tx, next)
	})
}

// MarkPassFailed records a pass that could not finish, leaving the applied generation
// where it was.
//
// Failing to converge is not the same as regressing: the containers that were already
// right are still right. Docker being unreachable is the ordinary case here, and the node
// reports degraded and retries rather than concluding that anything has gone away
// (AGENTS.md section 4.5).
func (s *Store) MarkPassFailed(ctx context.Context, at time.Time, detail string) error {
	if detail == "" {
		return errors.New("state: a failed reconcile pass must say why")
	}
	return s.transact(ctx, func(tx *sql.Tx) error {
		current, err := readConvergence(ctx, tx)
		if err != nil {
			return err
		}
		next := current
		next.Passes++
		next.LastPassAt = at
		next.LastError = detail
		return writeConvergence(ctx, tx, next)
	})
}

// Convergence reads the whole record.
func (s *Store) Convergence(ctx context.Context) (Convergence, error) {
	return readConvergence(ctx, s.db)
}

// AppliedGeneration is the number the control stream asks for on every heartbeat.
func (s *Store) AppliedGeneration(ctx context.Context) (uint64, error) {
	current, err := readConvergence(ctx, s.db)
	if err != nil {
		return 0, err
	}
	return current.AppliedGeneration, nil
}

// readConvergence returns the zero value for a database that has never completed a pass,
// which is the honest answer: generation zero, converged never. Callers do not have to
// distinguish "no row" from "nothing applied yet" because there is no difference.
func readConvergence(ctx context.Context, q queryer) (Convergence, error) {
	var (
		generation int64
		appliedAt  int64
		passes     int64
		lastPassAt int64
		lastError  string
	)
	err := q.QueryRowContext(ctx,
		`SELECT applied_generation, applied_at, passes, last_pass_at, last_error FROM convergence WHERE id = 1`,
	).Scan(&generation, &appliedAt, &passes, &lastPassAt, &lastError)
	if errors.Is(err, sql.ErrNoRows) {
		return Convergence{}, nil
	}
	if err != nil {
		return Convergence{}, fmt.Errorf("state: read convergence: %w", err)
	}
	return Convergence{
		AppliedGeneration: uint64(generation),
		AppliedAt:         instant(appliedAt),
		Passes:            uint64(passes),
		LastPassAt:        instant(lastPassAt),
		LastError:         lastError,
	}, nil
}

func writeConvergence(ctx context.Context, q queryer, value Convergence) error {
	_, err := q.ExecContext(ctx, `
INSERT INTO convergence (id, applied_generation, applied_at, passes, last_pass_at, last_error)
VALUES (1, ?, ?, ?, ?, ?)
ON CONFLICT (id) DO UPDATE SET
	applied_generation = excluded.applied_generation,
	applied_at         = excluded.applied_at,
	passes             = excluded.passes,
	last_pass_at       = excluded.last_pass_at,
	last_error         = excluded.last_error`,
		int64(value.AppliedGeneration), epochMillis(value.AppliedAt), int64(value.Passes),
		epochMillis(value.LastPassAt), value.LastError)
	if err != nil {
		return fmt.Errorf("state: record convergence: %w", err)
	}
	return nil
}
