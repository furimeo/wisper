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

// What each workload was last seen doing. The panel owns intent and the node owns fact;
// this table is the node's half, and nothing in it is ever written back into a spec
// (AGENTS.md section 4.2).
//
// It survives a restart for one reason that matters more than the rest: the phase a
// workload was in is the difference between "this container has been crash-looping for an
// hour" and "this container has just started", and a daemon that forgets loses the second
// piece of information every time it is upgraded.
//
// The status is stored whole, as the protobuf that goes on the wire, with phase lifted
// out as a column. Lifted because it is what a query filters on and what an operator
// reading the file with sqlite3 wants to see without decoding anything.
var workloadStatusSchema = []string{`
CREATE TABLE workload_status (
	workload_id TEXT PRIMARY KEY,
	phase       INTEGER NOT NULL,
	payload     BLOB    NOT NULL,
	observed_at INTEGER NOT NULL
) STRICT`}

// WorkloadObservation is one workload's status and when the node looked.
//
// ObservedAt is separate from the timestamps inside the status: last_transition_at is when
// the container changed, and this is when the daemon last managed to ask. During a Docker
// outage the first stops moving and the second keeps going, which is how "stale" is told
// apart from "unchanged".
type WorkloadObservation struct {
	Status     *wisperpb.WorkloadStatus
	ObservedAt time.Time
}

// SaveWorkloadStatuses records the result of one reconcile pass.
//
// One transaction for the whole batch: a pass observed the machine at a moment, and half a
// pass on disk describes a machine that never existed. An empty batch is accepted and
// writes nothing - it is what a pass that found no workloads produces, and it must not be
// confused with a pass that found none because Docker did not answer. That distinction
// lives in the batch's `partial` flag, and the caller keeps the previous rows by simply
// not calling this.
func (s *Store) SaveWorkloadStatuses(ctx context.Context, statuses []*wisperpb.WorkloadStatus, observedAt time.Time) error {
	if len(statuses) == 0 {
		return nil
	}
	return s.transact(ctx, func(tx *sql.Tx) error {
		for _, status := range statuses {
			if status == nil {
				return errors.New("state: cannot save a nil workload status")
			}
			if status.GetWorkloadId() == "" {
				return errors.New("state: cannot save a workload status with no workload id")
			}
			payload, err := proto.MarshalOptions{Deterministic: true}.Marshal(status)
			if err != nil {
				return fmt.Errorf("state: encode the status of workload %s: %w", status.GetWorkloadId(), err)
			}
			if _, err := tx.ExecContext(ctx, `
INSERT INTO workload_status (workload_id, phase, payload, observed_at)
VALUES (?, ?, ?, ?)
ON CONFLICT (workload_id) DO UPDATE SET
	phase       = excluded.phase,
	payload     = excluded.payload,
	observed_at = excluded.observed_at`,
				status.GetWorkloadId(), int64(status.GetPhase()), payload, epochMillis(observedAt),
			); err != nil {
				return fmt.Errorf("state: store the status of workload %s: %w", status.GetWorkloadId(), err)
			}
		}
		return nil
	})
}

// WorkloadStatuses is everything the node last observed, by workload id.
//
// This is what the first status batch after a restart is built from, before the reconcile
// loop has had a chance to look at Docker. Reporting last-known statuses immediately beats
// reporting nothing: an empty batch would tell the panel that every workload had gone.
func (s *Store) WorkloadStatuses(ctx context.Context) ([]WorkloadObservation, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT payload, observed_at FROM workload_status ORDER BY workload_id`)
	if err != nil {
		return nil, fmt.Errorf("state: read workload statuses: %w", err)
	}
	defer rows.Close()

	var out []WorkloadObservation
	for rows.Next() {
		var (
			payload    []byte
			observedAt int64
		)
		if err := rows.Scan(&payload, &observedAt); err != nil {
			return nil, fmt.Errorf("state: read workload statuses: %w", err)
		}
		status := &wisperpb.WorkloadStatus{}
		if err := proto.Unmarshal(payload, status); err != nil {
			return nil, fmt.Errorf("state: decode a stored workload status: %w", err)
		}
		out = append(out, WorkloadObservation{Status: status, ObservedAt: instant(observedAt)})
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("state: read workload statuses: %w", err)
	}
	return out, nil
}

// WorkloadStatus is one workload's last known state, or ErrNotFound.
func (s *Store) WorkloadStatus(ctx context.Context, workloadID string) (WorkloadObservation, error) {
	var (
		payload    []byte
		observedAt int64
	)
	err := s.db.QueryRowContext(ctx,
		`SELECT payload, observed_at FROM workload_status WHERE workload_id = ?`, workloadID,
	).Scan(&payload, &observedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return WorkloadObservation{}, fmt.Errorf("state: workload %s: %w", workloadID, ErrNotFound)
	}
	if err != nil {
		return WorkloadObservation{}, fmt.Errorf("state: read the status of workload %s: %w", workloadID, err)
	}
	status := &wisperpb.WorkloadStatus{}
	if err := proto.Unmarshal(payload, status); err != nil {
		return WorkloadObservation{}, fmt.Errorf("state: decode the stored status of workload %s: %w", workloadID, err)
	}
	return WorkloadObservation{Status: status, ObservedAt: instant(observedAt)}, nil
}

// PruneWorkloadStatuses forgets every workload that is not in keep, and reports how many
// it dropped.
//
// keep is the set of workload ids in the current spec, so this is the bookkeeping half of
// "anything running on this node that is not in the spec is removed". Call it only after
// the containers themselves are gone: a row removed while its container still exists
// leaves a workload the panel is told nothing about.
//
// An empty keep list empties the table, which is correct - a spec with no workloads means
// a node with no workloads - and is why the caller must not reach here on a pass where
// Docker was unreachable and the spec could not be read.
func (s *Store) PruneWorkloadStatuses(ctx context.Context, keep []string) (int64, error) {
	query := `DELETE FROM workload_status`
	var arguments []any
	if len(keep) > 0 {
		query += ` WHERE workload_id NOT IN (` + placeholders(len(keep)) + `)`
		arguments = anySlice(keep)
	}

	result, err := s.db.ExecContext(ctx, query, arguments...)
	if err != nil {
		return 0, fmt.Errorf("state: prune workload statuses: %w", err)
	}
	removed, err := result.RowsAffected()
	if err != nil {
		return 0, fmt.Errorf("state: prune workload statuses: %w", err)
	}
	return removed, nil
}
