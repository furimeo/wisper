package state

import (
	"context"
	"database/sql"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestOpenCreatesTheStateDirectory(t *testing.T) {
	// The installer creates /var/lib/wisper, but `run --state-dir ./var/dev` on a developer's
	// machine and the Makefile's run-dev target both point at a directory that may not exist.
	path := filepath.Join(t.TempDir(), "nested", "deeper", FileName)
	store := openStoreAt(t, path)

	if store.Path() != path {
		t.Fatalf("Path() = %q, want %q", store.Path(), path)
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("the database file was not created: %v", err)
	}
	if store.RecoveredFrom() != "" {
		t.Fatalf("a fresh database reported a recovery from %q", store.RecoveredFrom())
	}
}

func TestOpenRefusesAnEmptyPath(t *testing.T) {
	if _, err := Open(context.Background(), "   "); err == nil {
		t.Fatal("opening an empty path succeeded")
	}
}

func TestOpenAppliesTheConnectionSettings(t *testing.T) {
	// Each of these is load-bearing and each is silent when it is missing: without WAL an
	// interrupted write is not replayable, without the busy timeout a second writer fails
	// instead of waiting, and without foreign keys an upload's ranges outlive its session.
	store := openStore(t)
	ctx := context.Background()

	var journal string
	if err := store.db.QueryRowContext(ctx, "PRAGMA journal_mode").Scan(&journal); err != nil {
		t.Fatalf("read journal_mode: %v", err)
	}
	if !strings.EqualFold(journal, "wal") {
		t.Fatalf("journal_mode = %q, want wal", journal)
	}

	var busyTimeout int
	if err := store.db.QueryRowContext(ctx, "PRAGMA busy_timeout").Scan(&busyTimeout); err != nil {
		t.Fatalf("read busy_timeout: %v", err)
	}
	if busyTimeout < 1000 {
		t.Fatalf("busy_timeout = %d, want at least 1000ms", busyTimeout)
	}

	var foreignKeys int
	if err := store.db.QueryRowContext(ctx, "PRAGMA foreign_keys").Scan(&foreignKeys); err != nil {
		t.Fatalf("read foreign_keys: %v", err)
	}
	if foreignKeys != 1 {
		t.Fatal("foreign keys are not enforced, so an upload's ranges would outlive its session")
	}
}

func TestReopeningAnExistingDatabaseKeepsItsContents(t *testing.T) {
	path := filepath.Join(t.TempDir(), FileName)
	ctx := context.Background()

	first := openStoreAt(t, path)
	if err := first.SaveSpec(ctx, sampleSpec(3), "deployment 3", noon); err != nil {
		t.Fatalf("save spec: %v", err)
	}
	if err := first.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}

	second := openStoreAt(t, path)
	if second.RecoveredFrom() != "" {
		t.Fatalf("reopening a healthy database reported a recovery from %q", second.RecoveredFrom())
	}
	generation, err := second.SpecGeneration(ctx)
	if err != nil {
		t.Fatalf("read the generation after reopening: %v", err)
	}
	if generation != 3 {
		t.Fatalf("generation = %d after reopening, want 3", generation)
	}
}

func TestPathIsUnderTheStateDirectory(t *testing.T) {
	want := filepath.Join("/var/lib/wisper", FileName)
	if got := Path("/var/lib/wisper"); got != want {
		t.Fatalf("Path() = %q, want %q", got, want)
	}
}

func TestEpochRoundTrip(t *testing.T) {
	cases := []struct {
		name string
		in   time.Time
	}{
		{"zero", time.Time{}},
		{"noon", noon},
		{"with milliseconds", noon.Add(1500 * time.Millisecond)},
	}
	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			out := instant(epochMillis(testCase.in))
			if !out.Equal(testCase.in) {
				t.Fatalf("round trip of %s produced %s", testCase.in, out)
			}
		})
	}
}

func TestOptionalInstantTreatsNullAsUnset(t *testing.T) {
	if got := optionalInstant(sql.NullInt64{}); !got.IsZero() {
		t.Fatalf("a NULL timestamp read back as %s", got)
	}
	if got := optionalInstant(sql.NullInt64{Int64: noon.UnixMilli(), Valid: true}); !got.Equal(noon) {
		t.Fatalf("a stored timestamp read back as %s, want %s", got, noon)
	}
	if nullableMillis(time.Time{}) != nil {
		t.Fatal("a zero time did not bind as NULL")
	}
}

func TestPlaceholdersMatchTheArgumentCount(t *testing.T) {
	cases := map[int]string{0: "", 1: "?", 3: "?, ?, ?"}
	for count, want := range cases {
		if got := placeholders(count); got != want {
			t.Fatalf("placeholders(%d) = %q, want %q", count, got, want)
		}
	}
}

func TestCloseIsIdempotent(t *testing.T) {
	// The daemon is crash-only and its shutdown path is best-effort, so a second Close from a
	// deferred call must not turn a clean stop into an error on the way out.
	store := openStore(t)
	if err := store.Close(); err != nil {
		t.Fatalf("first close: %v", err)
	}
	if err := store.Close(); err != nil {
		t.Fatalf("second close: %v", err)
	}
	if _, err := store.SpecGeneration(context.Background()); err == nil {
		t.Fatal("a closed store still answered a query")
	} else if errors.Is(err, ErrNoSpec) {
		t.Fatal("a closed store answered with ErrNoSpec rather than a closed-database error")
	}
}
