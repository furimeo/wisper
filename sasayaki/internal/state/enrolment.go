package state

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"
)

// Which node this database belongs to, and which panel it answers to.
//
// The credential itself is not here. It lives in /etc/wisper/node.json, mode 0600, owned
// by root, and this file lives under /var/lib/wisper - which `uninstall` deliberately
// leaves behind so a reinstalled node reconverges from the spec it already had. A
// long-lived token in a file that survives the uninstall of the daemon holding it is a
// credential nobody remembers is still valid.
//
// What is here is identity, and it is here for one job: to notice when the machine has
// been re-enrolled as a different node. Everything else in this database - the spec, the
// statuses, the uploads, the certificates - describes work that belonged to the previous
// enrolment, and handing it to a new panel would have the node reconciling towards another
// node's desired state.
var enrolmentSchema = []string{`
CREATE TABLE enrolment (
	id                       INTEGER PRIMARY KEY CHECK (id = 1),
	node_id                  TEXT    NOT NULL,
	node_name                TEXT    NOT NULL,
	panel                    TEXT    NOT NULL,
	panel_certificate_sha256 TEXT    NOT NULL,
	machine_fingerprint      TEXT    NOT NULL,
	agent_version            TEXT    NOT NULL,
	protocol_version         INTEGER NOT NULL,
	enrolled_at              INTEGER NOT NULL
) STRICT`}

// Enrolment is who this node is, as agreed with the panel.
type Enrolment struct {
	// From EnrollResponse. Opaque to the node.
	NodeID string
	// What an administrator named it: used in log lines, and as the phrase
	// `uninstall --purge` demands before it destroys anything.
	NodeName string
	// The panel endpoint, in the form the credential file records it.
	Panel string
	// Hex SHA-256 of the panel's TLS leaf certificate, pinned on first use. Recorded here
	// as well as in the credential so a changed pin is visible as a change of enrolment
	// rather than only as a connection that stopped working.
	PanelCertificateSHA256 string
	// Hex SHA-256 over machine-id and hardware serials. Kept so a daemon that wakes up on a
	// cloned VM can see that the machine underneath it is not the one that enrolled.
	MachineFingerprint string
	// What was running when this node joined, and the protocol it agreed to speak.
	AgentVersion    string
	ProtocolVersion uint32
	EnrolledAt      time.Time
}

// SaveEnrolment records who this node is, and reports whether it had to discard another
// node's data to do it.
//
// A machine that is re-enrolled - the node was deleted in the panel and created again, or
// pointed at a different panel entirely - keeps its state directory, because uninstall
// does not touch customers' volumes. Everything in this database that describes the old
// enrolment is emptied in the same transaction that records the new one, so there is no
// moment at which the node holds one panel's credential and another panel's spec.
//
// The customers' data on disk is not touched. This clears the node's memory of what it was
// asked to do, not the volumes, the releases or the databases; those are removed only by
// `uninstall --purge`, and only after an operator has typed the node's name.
func (s *Store) SaveEnrolment(ctx context.Context, enrolment Enrolment) (bool, error) {
	if enrolment.NodeID == "" {
		return false, errors.New("state: an enrolment needs a node id")
	}
	if enrolment.Panel == "" {
		return false, fmt.Errorf("state: the enrolment of node %s names no panel", enrolment.NodeID)
	}

	cleared := false
	err := s.transact(ctx, func(tx *sql.Tx) error {
		previous, err := readEnrolment(ctx, tx)
		switch {
		case errors.Is(err, ErrNotFound):
			// Never enrolled, or the database is new. Nothing to discard.
		case err != nil:
			return err
		case previous.NodeID != enrolment.NodeID || previous.Panel != enrolment.Panel:
			for _, table := range nodeScopedTables() {
				if _, err := tx.ExecContext(ctx, `DELETE FROM `+table); err != nil {
					return fmt.Errorf("state: discard the state of node %s from %s: %w", previous.NodeID, table, err)
				}
			}
			cleared = true
		}

		if _, err := tx.ExecContext(ctx, `
INSERT INTO enrolment (
	id, node_id, node_name, panel, panel_certificate_sha256,
	machine_fingerprint, agent_version, protocol_version, enrolled_at
) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (id) DO UPDATE SET
	node_id                  = excluded.node_id,
	node_name                = excluded.node_name,
	panel                    = excluded.panel,
	panel_certificate_sha256 = excluded.panel_certificate_sha256,
	machine_fingerprint      = excluded.machine_fingerprint,
	agent_version            = excluded.agent_version,
	protocol_version         = excluded.protocol_version,
	enrolled_at              = excluded.enrolled_at`,
			enrolment.NodeID, enrolment.NodeName, enrolment.Panel, enrolment.PanelCertificateSHA256,
			enrolment.MachineFingerprint, enrolment.AgentVersion, int64(enrolment.ProtocolVersion),
			epochMillis(enrolment.EnrolledAt),
		); err != nil {
			return fmt.Errorf("state: record the enrolment of node %s: %w", enrolment.NodeID, err)
		}
		return nil
	})
	if err != nil {
		return false, err
	}
	return cleared, nil
}

// Enrolment is who this node is, or ErrNotFound on a database that has never been enrolled
// - which is the state of a node between `sasayaki run` finding a state directory and the
// credential being read, and is not an error.
func (s *Store) Enrolment(ctx context.Context) (Enrolment, error) {
	return readEnrolment(ctx, s.db)
}

func readEnrolment(ctx context.Context, q queryer) (Enrolment, error) {
	var (
		enrolment Enrolment
		protocol  int64
		enrolled  int64
	)
	err := q.QueryRowContext(ctx, `
SELECT node_id, node_name, panel, panel_certificate_sha256,
       machine_fingerprint, agent_version, protocol_version, enrolled_at
FROM enrolment WHERE id = 1`,
	).Scan(&enrolment.NodeID, &enrolment.NodeName, &enrolment.Panel, &enrolment.PanelCertificateSHA256,
		&enrolment.MachineFingerprint, &enrolment.AgentVersion, &protocol, &enrolled)
	if errors.Is(err, sql.ErrNoRows) {
		return Enrolment{}, fmt.Errorf("state: enrolment: %w", ErrNotFound)
	}
	if err != nil {
		return Enrolment{}, fmt.Errorf("state: read the enrolment: %w", err)
	}
	enrolment.ProtocolVersion = uint32(protocol)
	enrolment.EnrolledAt = instant(enrolled)
	return enrolment, nil
}
