package state

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"
)

// migration is one irreversible step of the node's schema.
//
// There is no down migration. A node's state database is a cache of things the panel can
// resend and things the node can re-observe; the recovery from a schema it cannot use is
// to start a new file, not to unwind it statement by statement into a shape nobody has
// tested.
type migration struct {
	// Version is the order and the identity. It is written to schema_migration and never
	// changes once a release has shipped it.
	Version int
	// Name is for the operator reading schema_migration months later.
	Name string
	// Statements run in one transaction, in order. SQLite makes DDL transactional, so a
	// migration that fails halfway leaves the previous schema intact.
	Statements []string
}

// The ledger. Created before anything else, and by the same runner that fills it, so a
// database that has never been migrated and one that is up to date take the same path.
const migrationLedger = `
CREATE TABLE IF NOT EXISTS schema_migration (
	version    INTEGER PRIMARY KEY,
	name       TEXT    NOT NULL,
	applied_at INTEGER NOT NULL
) STRICT`

// migrate brings db up to the schema described by steps and reports which ones ran.
//
// steps is a parameter rather than a call to schema() so the runner itself is testable:
// a test can hand it two migrations, then three, and check that the third is the only one
// applied the second time.
func migrate(ctx context.Context, db *sql.DB, steps []migration) ([]migration, error) {
	if err := checkOrder(steps); err != nil {
		return nil, err
	}
	if _, err := db.ExecContext(ctx, migrationLedger); err != nil {
		return nil, fmt.Errorf("state: create the migration ledger: %w", err)
	}

	applied, highest, err := appliedVersions(ctx, db)
	if err != nil {
		return nil, err
	}

	// A database written by a newer sasayaki. Downgrading the binary and reusing the file
	// would mean reading columns this build does not know about and writing rows the newer
	// build would reject; refusing here names the problem, where the alternative is a
	// constraint error at two in the morning.
	if len(steps) > 0 && highest > steps[len(steps)-1].Version {
		return nil, fmt.Errorf("state: the database is at schema version %d and this build only knows %d: "+
			"it was written by a newer sasayaki, so downgrade the binary or move the file aside",
			highest, steps[len(steps)-1].Version)
	}

	ran := make([]migration, 0, len(steps))
	for _, step := range steps {
		if applied[step.Version] {
			continue
		}
		if err := applyMigration(ctx, db, step); err != nil {
			return ran, err
		}
		ran = append(ran, step)
	}
	return ran, nil
}

// applyMigration runs one step and records it, both or neither.
func applyMigration(ctx context.Context, db *sql.DB, step migration) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("state: begin migration %d (%s): %w", step.Version, step.Name, err)
	}
	defer tx.Rollback()

	for index, statement := range step.Statements {
		if _, err := tx.ExecContext(ctx, statement); err != nil {
			return fmt.Errorf("state: migration %d (%s) statement %d: %w", step.Version, step.Name, index+1, err)
		}
	}
	if _, err := tx.ExecContext(ctx,
		`INSERT INTO schema_migration (version, name, applied_at) VALUES (?, ?, ?)`,
		step.Version, step.Name, time.Now().UTC().UnixMilli(),
	); err != nil {
		return fmt.Errorf("state: record migration %d (%s): %w", step.Version, step.Name, err)
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("state: commit migration %d (%s): %w", step.Version, step.Name, err)
	}
	return nil
}

// appliedVersions reads the ledger.
func appliedVersions(ctx context.Context, db *sql.DB) (map[int]bool, int, error) {
	rows, err := db.QueryContext(ctx, `SELECT version FROM schema_migration`)
	if err != nil {
		return nil, 0, fmt.Errorf("state: read the migration ledger: %w", err)
	}
	defer rows.Close()

	applied := make(map[int]bool)
	highest := 0
	for rows.Next() {
		var version int
		if err := rows.Scan(&version); err != nil {
			return nil, 0, fmt.Errorf("state: read the migration ledger: %w", err)
		}
		applied[version] = true
		if version > highest {
			highest = version
		}
	}
	if err := rows.Err(); err != nil {
		return nil, 0, fmt.Errorf("state: read the migration ledger: %w", err)
	}
	return applied, highest, nil
}

// checkOrder catches the mistake that only shows up on somebody else's machine: two
// migrations given the same version, or a new one inserted in the middle. On the
// developer's own machine the database is already migrated and nothing happens; on a
// fresh node the versions apply in the wrong order or one of them silently never runs.
func checkOrder(steps []migration) error {
	previous := 0
	for index, step := range steps {
		if step.Version <= previous {
			return fmt.Errorf("state: migration %d has version %d, which does not follow %d: "+
				"versions are append-only and strictly increasing", index+1, step.Version, previous)
		}
		if step.Name == "" {
			return fmt.Errorf("state: migration %d has no name", step.Version)
		}
		if len(step.Statements) == 0 {
			return fmt.Errorf("state: migration %d (%s) has no statements", step.Version, step.Name)
		}
		previous = step.Version
	}
	return nil
}

// appliedMigrations lists what has been applied, newest last. Used by the tests and by
// anyone diagnosing a node whose schema is not what they expected.
func (s *Store) appliedMigrations(ctx context.Context) ([]migration, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT version, name FROM schema_migration ORDER BY version`)
	if err != nil {
		return nil, fmt.Errorf("state: read the migration ledger: %w", err)
	}
	defer rows.Close()

	var out []migration
	for rows.Next() {
		var step migration
		if err := rows.Scan(&step.Version, &step.Name); err != nil {
			return nil, fmt.Errorf("state: read the migration ledger: %w", err)
		}
		out = append(out, step)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("state: read the migration ledger: %w", err)
	}
	if len(out) == 0 {
		return nil, errors.New("state: the migration ledger is empty on an open database")
	}
	return out, nil
}
