package state

import (
	"context"
	"database/sql"
	"path/filepath"
	"strings"
	"testing"
)

// rawDatabase is a connection with the daemon's settings but without the schema, so the
// runner can be exercised on its own.
func rawDatabase(t *testing.T) *sql.DB {
	t.Helper()
	db, err := sql.Open("sqlite", dsn(filepath.Join(t.TempDir(), FileName)))
	if err != nil {
		t.Fatalf("open a database: %v", err)
	}
	db.SetMaxOpenConns(1)
	t.Cleanup(func() { db.Close() })
	return db
}

func stepsUpTo(n int) []migration {
	all := []migration{
		{Version: 1, Name: "one", Statements: []string{`CREATE TABLE one (id INTEGER PRIMARY KEY) STRICT`}},
		{Version: 2, Name: "two", Statements: []string{`CREATE TABLE two (id INTEGER PRIMARY KEY) STRICT`}},
		{Version: 3, Name: "three", Statements: []string{`CREATE TABLE three (id INTEGER PRIMARY KEY) STRICT`}},
	}
	return all[:n]
}

func TestMigrateRunsOnlyWhatIsMissing(t *testing.T) {
	// The whole point of the ledger: a node that upgrades applies the new steps and leaves
	// the ones it already has alone.
	ctx := context.Background()
	db := rawDatabase(t)

	ran, err := migrate(ctx, db, stepsUpTo(2))
	if err != nil {
		t.Fatalf("first migration: %v", err)
	}
	if len(ran) != 2 {
		t.Fatalf("a fresh database ran %d migrations, want 2", len(ran))
	}

	ran, err = migrate(ctx, db, stepsUpTo(3))
	if err != nil {
		t.Fatalf("second migration: %v", err)
	}
	if len(ran) != 1 || ran[0].Version != 3 {
		t.Fatalf("the upgrade ran %v, want only version 3", ran)
	}

	ran, err = migrate(ctx, db, stepsUpTo(3))
	if err != nil {
		t.Fatalf("third migration: %v", err)
	}
	if len(ran) != 0 {
		t.Fatalf("an up-to-date database ran %v", ran)
	}
}

func TestMigrateRefusesADatabaseWrittenByANewerBuild(t *testing.T) {
	// Downgrading the binary and reusing the file means reading columns this build does not
	// know about. Refusing here names the problem; the alternative is a constraint error at
	// two in the morning.
	ctx := context.Background()
	db := rawDatabase(t)

	if _, err := migrate(ctx, db, stepsUpTo(3)); err != nil {
		t.Fatalf("migrate to version 3: %v", err)
	}
	_, err := migrate(ctx, db, stepsUpTo(1))
	if err == nil {
		t.Fatal("a database from a newer build was accepted by an older one")
	}
	if !strings.Contains(err.Error(), "newer sasayaki") {
		t.Fatalf("the error does not say what to do about it: %v", err)
	}
}

func TestAFailedMigrationLeavesTheOldSchemaIntact(t *testing.T) {
	// SQLite makes DDL transactional, and the ledger entry is written in the same
	// transaction. Half a migration recorded as applied is a schema nobody can repair.
	ctx := context.Background()
	db := rawDatabase(t)

	broken := []migration{
		{Version: 1, Name: "one", Statements: []string{`CREATE TABLE one (id INTEGER PRIMARY KEY) STRICT`}},
		{Version: 2, Name: "broken", Statements: []string{
			`CREATE TABLE two (id INTEGER PRIMARY KEY) STRICT`,
			`CREATE TABLE two (id INTEGER PRIMARY KEY) STRICT`, // already exists
		}},
	}
	ran, err := migrate(ctx, db, broken)
	if err == nil {
		t.Fatal("a migration whose second statement fails was accepted")
	}
	if len(ran) != 1 || ran[0].Version != 1 {
		t.Fatalf("the runner reported %v as applied", ran)
	}

	var recorded int
	if err := db.QueryRowContext(ctx, `SELECT count(*) FROM schema_migration`).Scan(&recorded); err != nil {
		t.Fatalf("read the ledger: %v", err)
	}
	if recorded != 1 {
		t.Fatalf("the ledger records %d migrations, want only the one that succeeded", recorded)
	}
	var tables int
	if err := db.QueryRowContext(ctx,
		`SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'two'`).Scan(&tables); err != nil {
		t.Fatalf("look for the half-created table: %v", err)
	}
	if tables != 0 {
		t.Fatal("the failed migration left its first statement behind")
	}
}

func TestCheckOrderCatchesTheMergeThatProducedTwoVersionSevens(t *testing.T) {
	// On the developer's own machine the database is already migrated and nothing happens;
	// on a fresh node the versions apply in the wrong order or one of them never runs.
	cases := map[string][]migration{
		"a duplicate version": {
			{Version: 1, Name: "one", Statements: []string{"SELECT 1"}},
			{Version: 1, Name: "also one", Statements: []string{"SELECT 1"}},
		},
		"one inserted in the middle": {
			{Version: 2, Name: "two", Statements: []string{"SELECT 1"}},
			{Version: 1, Name: "one", Statements: []string{"SELECT 1"}},
		},
		"no name": {
			{Version: 1, Name: "", Statements: []string{"SELECT 1"}},
		},
		"no statements": {
			{Version: 1, Name: "one"},
		},
	}
	for name, steps := range cases {
		t.Run(name, func(t *testing.T) {
			if err := checkOrder(steps); err == nil {
				t.Fatalf("a migration list with %s was accepted", name)
			}
			if _, err := migrate(context.Background(), rawDatabase(t), steps); err == nil {
				t.Fatalf("migrate accepted a list with %s", name)
			}
		})
	}
}

func TestTheShippedSchemaIsWellFormed(t *testing.T) {
	// The real list, checked by the same rule. A bad merge here breaks every fresh node in
	// the fleet and no existing one, which is the hardest kind of fault to notice.
	if err := checkOrder(schema()); err != nil {
		t.Fatalf("the shipped schema is not append-only: %v", err)
	}
}

func TestAnOpenDatabaseHasTheWholeSchemaRecorded(t *testing.T) {
	store := openStore(t)

	applied, err := store.appliedMigrations(context.Background())
	if err != nil {
		t.Fatalf("read the ledger: %v", err)
	}
	shipped := schema()
	if len(applied) != len(shipped) {
		t.Fatalf("%d migrations applied, want %d", len(applied), len(shipped))
	}
	for i, step := range shipped {
		if applied[i].Version != step.Version || applied[i].Name != step.Name {
			t.Fatalf("migration %d is %d/%s, want %d/%s",
				i, applied[i].Version, applied[i].Name, step.Version, step.Name)
		}
	}
}

func TestReopeningDoesNotRunTheMigrationsAgain(t *testing.T) {
	path := filepath.Join(t.TempDir(), FileName)
	ctx := context.Background()

	first := openStoreAt(t, path)
	before, err := first.appliedMigrations(ctx)
	if err != nil {
		t.Fatalf("read the ledger: %v", err)
	}
	if err := first.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}

	second := openStoreAt(t, path)
	after, err := second.appliedMigrations(ctx)
	if err != nil {
		t.Fatalf("read the ledger after reopening: %v", err)
	}
	if len(after) != len(before) {
		t.Fatalf("reopening changed the ledger from %d to %d entries", len(before), len(after))
	}
}
