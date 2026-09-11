package state

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
	"google.golang.org/protobuf/proto"
)

// One row, because a node has exactly one desired state. Keeping older generations would
// invite something to reconcile against one of them, and there is only ever one right
// answer to "what is this machine supposed to be running".
//
// The spec is stored as the protobuf bytes it arrived as, not as a set of columns. It is
// one document that is written, hashed and diffed as a unit, and shredding forty message
// types into tables would mean every field added to workload.proto needed a migration
// here to be remembered across a restart - which is the drift the single .proto contract
// exists to prevent.
var specSchema = []string{`
CREATE TABLE node_spec (
	id          INTEGER PRIMARY KEY CHECK (id = 1),
	generation  INTEGER NOT NULL,
	payload     BLOB    NOT NULL,
	sha256      TEXT    NOT NULL,
	reason      TEXT    NOT NULL,
	received_at INTEGER NOT NULL
) STRICT`}

// StoredSpec is the desired state as it arrived, with how and when it got here.
type StoredSpec struct {
	Spec       *wisperpb.NodeSpec
	Generation uint64
	// SHA256 of the stored encoding. The reconcile loop compares it against the spec it
	// last converged to, which is cheaper than walking two documents to find out that
	// nothing changed.
	SHA256 string
	// What the panel said it was sending this for - "deployment 412", "reconnect". Kept so
	// the daemon's log can tell a real change from the resend that follows every dropped
	// stream (node.proto, ApplySpec.reason).
	Reason     string
	ReceivedAt time.Time
}

// SaveSpec writes the desired state down, and is the whole of what the panel is waiting
// for when it sends ApplySpec: received, understood, stored. Convergence happens later,
// on the reconcile loop's own time.
//
// A generation below the stored one is refused with ErrSupersededGeneration - a retried
// frame arriving late must not roll a machine backwards. An equal generation is accepted
// and overwrites, because the panel resends the whole spec on every reconnect and the
// bytes may differ even when the number does not.
func (s *Store) SaveSpec(ctx context.Context, spec *wisperpb.NodeSpec, reason string, receivedAt time.Time) error {
	if spec == nil {
		return errors.New("state: cannot save a nil spec")
	}

	// Deterministic so the checksum of the same spec is the same checksum, on this node
	// and on the next restart. Without it the drift comparison would fire on every pass.
	payload, err := proto.MarshalOptions{Deterministic: true}.Marshal(spec)
	if err != nil {
		return fmt.Errorf("state: encode spec at generation %d: %w", spec.GetGeneration(), err)
	}
	digest := sha256.Sum256(payload)

	return s.transact(ctx, func(tx *sql.Tx) error {
		var stored int64
		err := tx.QueryRowContext(ctx, `SELECT generation FROM node_spec WHERE id = 1`).Scan(&stored)
		switch {
		case errors.Is(err, sql.ErrNoRows):
			// First spec this node has ever been given.
		case err != nil:
			return fmt.Errorf("state: read the stored generation: %w", err)
		case uint64(stored) > spec.GetGeneration():
			return fmt.Errorf("state: offered generation %d, stored generation %d: %w",
				spec.GetGeneration(), uint64(stored), ErrSupersededGeneration)
		}

		_, err = tx.ExecContext(ctx, `
INSERT INTO node_spec (id, generation, payload, sha256, reason, received_at)
VALUES (1, ?, ?, ?, ?, ?)
ON CONFLICT (id) DO UPDATE SET
	generation  = excluded.generation,
	payload     = excluded.payload,
	sha256      = excluded.sha256,
	reason      = excluded.reason,
	received_at = excluded.received_at`,
			int64(spec.GetGeneration()), payload, hex.EncodeToString(digest[:]), reason, epochMillis(receivedAt))
		if err != nil {
			return fmt.Errorf("state: store spec at generation %d: %w", spec.GetGeneration(), err)
		}
		return nil
	})
}

// LoadSpec returns the desired state, which is where a cold start begins: read this,
// compare it with what Docker and the edge are actually doing, close the gap. It works
// with the panel unreachable, which is the point.
//
// ErrNoSpec means the node has never been given one. That is not a failure - a freshly
// enrolled node is in that state until its first ApplySpec arrives - and the reconcile
// loop's answer to it is to remove nothing and wait.
func (s *Store) LoadSpec(ctx context.Context) (StoredSpec, error) {
	var (
		generation int64
		payload    []byte
		digest     string
		reason     string
		receivedAt int64
	)
	err := s.db.QueryRowContext(ctx,
		`SELECT generation, payload, sha256, reason, received_at FROM node_spec WHERE id = 1`,
	).Scan(&generation, &payload, &digest, &reason, &receivedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return StoredSpec{}, ErrNoSpec
	}
	if err != nil {
		return StoredSpec{}, fmt.Errorf("state: read the stored spec: %w", err)
	}

	// The integrity check on open verifies SQLite's own structures, not the contents of a
	// blob. A flipped bit inside the payload would decode into a spec that is subtly wrong
	// and get reconciled - containers stopped, routes withdrawn - so it is checked here,
	// where the answer is to refuse and wait for the panel to resend.
	actual := sha256.Sum256(payload)
	if encoded := hex.EncodeToString(actual[:]); encoded != digest {
		return StoredSpec{}, fmt.Errorf("state: the stored spec at generation %d does not match its checksum "+
			"(recorded %s, found %s): it is damaged and must be resent by the panel", uint64(generation), digest, encoded)
	}

	spec := &wisperpb.NodeSpec{}
	if err := proto.Unmarshal(payload, spec); err != nil {
		return StoredSpec{}, fmt.Errorf("state: decode the stored spec at generation %d: %w", uint64(generation), err)
	}

	return StoredSpec{
		Spec:       spec,
		Generation: uint64(generation),
		SHA256:     digest,
		Reason:     reason,
		ReceivedAt: instant(receivedAt),
	}, nil
}

// SpecGeneration is the generation on disk, without decoding the spec.
//
// Cheap enough to call on the heartbeat interval, which matters: the panel compares what
// it published with what the node holds and resends when they differ, so this number is
// asked for far more often than the document behind it.
func (s *Store) SpecGeneration(ctx context.Context) (uint64, error) {
	var generation int64
	err := s.db.QueryRowContext(ctx, `SELECT generation FROM node_spec WHERE id = 1`).Scan(&generation)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, ErrNoSpec
	}
	if err != nil {
		return 0, fmt.Errorf("state: read the stored generation: %w", err)
	}
	return uint64(generation), nil
}
