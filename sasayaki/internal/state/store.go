// Package state is the node's memory.
//
// sasayaki is crash-only: it does no cleanup on the way out, and being killed has to be
// indistinguishable from a clean stop (AGENTS.md section 4.4). That is only true when
// everything the daemon would otherwise hold in RAM is already on disk at the moment it
// dies, and this package is that disk. Its predecessor kept the running state in memory,
// lost it on every restart and never reconverged; a restarted sasayaki reads the last
// spec, the statuses it observed, the uploads that were in flight, the certificates it
// knows about and the runs it started straight out of this database and carries on with
// the panel unreachable.
//
// SQLite through modernc.org/sqlite, which is a pure-Go implementation: cgo would cost
// the single static binary that makes installing a node "copy one file".
//
// One concern per file, and each one owns its own table and its own migration:
//
//	spec.go             the last NodeSpec the panel published
//	convergence.go      how far the reconcile loop has got through it
//	workload_status.go  what each workload was last observed doing
//	upload.go           resumable upload sessions
//	upload_chunk.go     the byte ranges of each session already on disk
//	certificate.go      what the edge knows about each domain's certificate
//	build.go            build history
//	backup.go           backup runs
//	restore.go          restore runs
//	cron.go             the last run of each cron entry
//	enrolment.go        which node, and which panel, this database belongs to
//
// Every method takes a context and every timestamp crosses the boundary as a time.Time;
// nothing in here reads the clock for itself, so a caller can replay a sequence of
// events at exact times and the tests do not have to sleep.
package state

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	// Registers the "sqlite" driver. Imported by name as well because the corruption
	// check in recover.go reads the SQLite result code off the returned error.
	_ "modernc.org/sqlite"
)

// FileName is what the state database is called inside the node's state directory. The
// installer, the uninstaller and `sasayaki doctor` all name the same file, so it is a
// constant rather than a string repeated in four packages.
const FileName = "state.db"

// Path is the state database of a node whose --state-dir is stateDir. The default state
// directory is /var/lib/wisper, which `uninstall` deliberately leaves behind: a node that
// was removed and reinstalled reconverges from the spec it already had.
func Path(stateDir string) string {
	return filepath.Join(stateDir, FileName)
}

// Store is an open node state database.
//
// It is safe for concurrent use. Serialising every statement onto one connection is what
// makes that true, and it is affordable here because the daemon's write volume is tiny:
// one spec per deployment, one batch of statuses per reconcile pass, one row per upload
// chunk. The alternative - a pool plus a write mutex - buys parallel reads that nothing
// in the daemon is waiting on, and costs a class of bug where a read and a write on two
// connections deadlock against each other.
//
// The consequence, and the one rule for anyone adding a method here: a function that
// holds a *sql.Tx must never call back into a method that uses Store.db. There is one
// connection, so that waits forever rather than failing.
type Store struct {
	db   *sql.DB
	path string

	// Set when Open found the file unreadable and started a fresh one. Empty otherwise.
	recoveredFrom string
}

// Open prepares the state database at path, creating and migrating it when necessary.
//
// A database that cannot be read is not a reason to refuse to start. The node's job is to
// keep customers' containers running, and it can rebuild everything in here from the next
// spec the panel sends; refusing to boot would turn one bad file into an outage. So a
// corrupt file is moved aside - never deleted, because it is evidence - and a new one is
// created in its place. RecoveredFrom reports where the old one went, so the daemon can
// say so on the panel rather than recovering silently.
func Open(ctx context.Context, path string) (*Store, error) {
	if strings.TrimSpace(path) == "" {
		return nil, errors.New("state: database path is empty")
	}

	directory := filepath.Dir(path)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return nil, fmt.Errorf("state: create %s: %w", directory, err)
	}

	store, err := open(ctx, path)
	if err == nil {
		return store, nil
	}
	if !isCorruption(err) {
		return nil, err
	}

	quarantined, moveErr := quarantine(path)
	if moveErr != nil {
		return nil, fmt.Errorf("state: %s is unreadable (%v) and could not be moved aside: %w", path, err, moveErr)
	}

	replacement, retryErr := open(ctx, path)
	if retryErr != nil {
		return nil, fmt.Errorf("state: %s was unreadable (%v) and was moved to %s, but a replacement could not be created: %w",
			path, err, quarantined, retryErr)
	}
	replacement.recoveredFrom = quarantined
	return replacement, nil
}

// open is one attempt: connect, prove the file is readable, bring the schema up to date.
// It owns the handle it creates and closes it on every failure, so the caller is free to
// rename the file afterwards - which matters on Windows, where an open file cannot be
// moved.
func open(ctx context.Context, path string) (*Store, error) {
	db, err := sql.Open("sqlite", dsn(path))
	if err != nil {
		return nil, fmt.Errorf("state: open %s: %w", path, err)
	}
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(1)
	// No lifetime: recycling the single connection would drop the WAL mode and the busy
	// timeout that the DSN applies once, at connect time.
	db.SetConnMaxLifetime(0)

	if err := verify(ctx, db); err != nil {
		db.Close()
		return nil, err
	}
	if _, err := migrate(ctx, db, schema()); err != nil {
		db.Close()
		return nil, err
	}
	return &Store{db: db, path: path}, nil
}

// dsn builds the connection string.
//
// The path is not prefixed with "file:", so the driver strips the query string from it
// and hands SQLite the plain path. That keeps Windows drive letters out of URI parsing,
// where "D:/var/state.db" is ambiguous and the escaping rules differ from POSIX.
//
//   - busy_timeout: a second connection to the same file - a test, a second daemon that
//     has not exited yet, sqlite3 on the console - waits instead of failing immediately.
//   - journal_mode=WAL: a reader never blocks on a writer, and an interrupted write
//     leaves a replayable log rather than a half-updated page.
//   - synchronous=NORMAL: with WAL this is durable across a process being killed, which
//     is the failure this daemon is built around. It is not durable across the machine
//     losing power mid-commit; FULL would cost an fsync per transaction, and the answer
//     to a lost spec is that the panel resends it.
//   - foreign_keys: upload_range rows are deleted with the session they belong to. SQLite
//     does not enforce that unless it is asked, per connection.
//   - txlock=immediate: a transaction takes its write lock at BEGIN instead of upgrading
//     partway through, so two writers queue rather than deadlocking.
func dsn(path string) string {
	settings := []string{
		"_pragma=busy_timeout(5000)",
		"_pragma=journal_mode(WAL)",
		"_pragma=synchronous(NORMAL)",
		"_pragma=foreign_keys(1)",
		"_txlock=immediate",
	}
	return path + "?" + strings.Join(settings, "&")
}

// Close releases the database.
//
// Nothing is flushed here that was not already committed: every method in this package
// commits before it returns, because the daemon may be killed between any two of them.
func (s *Store) Close() error {
	if err := s.db.Close(); err != nil {
		return fmt.Errorf("state: close %s: %w", s.path, err)
	}
	return nil
}

// Path is the file this store is backed by.
func (s *Store) Path() string {
	return s.path
}

// queryer is the part of *sql.DB and *sql.Tx this package uses, so a read can be written
// once and called both inside a transaction and outside one.
type queryer interface {
	ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error)
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

// transact runs fn in a transaction and commits it, or rolls back and returns fn's error.
func (s *Store) transact(ctx context.Context, fn func(tx *sql.Tx) error) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("state: begin transaction: %w", err)
	}
	if err := fn(tx); err != nil {
		// The rollback's own error is dropped on purpose: fn's error is why we are here,
		// and replacing it with "rollback failed" hides the cause of the failure.
		_ = tx.Rollback()
		return err
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("state: commit: %w", err)
	}
	return nil
}

// epochMillis is how a time is stored: milliseconds since the Unix epoch, in a plain
// INTEGER column. Not a string, because string timestamps sort correctly only by accident
// and compare correctly only if everyone agrees on the format forever.
//
// A zero time.Time stores as 0. The daemon has no use for 1970, so the ambiguity costs
// nothing and buys "unset" a representation in a NOT NULL column.
func epochMillis(t time.Time) int64 {
	if t.IsZero() {
		return 0
	}
	return t.UnixMilli()
}

// instant is epochMillis backwards. Always UTC: a node's local zone is not the panel's,
// and a timestamp that changes meaning depending on which machine printed it is worse
// than no timestamp.
func instant(millis int64) time.Time {
	if millis == 0 {
		return time.Time{}
	}
	return time.UnixMilli(millis).UTC()
}

// nullableMillis stores a zero time as SQL NULL, for the columns where "has not happened
// yet" is a real state that a query has to be able to ask about - a build that is still
// running, an upload that has not been completed.
func nullableMillis(t time.Time) any {
	if t.IsZero() {
		return nil
	}
	return t.UnixMilli()
}

// optionalInstant reads a nullable timestamp column.
func optionalInstant(value sql.NullInt64) time.Time {
	if !value.Valid {
		return time.Time{}
	}
	return instant(value.Int64)
}

// boolean binds a Go bool to a STRICT INTEGER column. Explicit rather than relying on the
// driver's own conversion, because a STRICT table rejects whatever it does not expect and
// the failure would appear as a constraint error a long way from here.
func boolean(value bool) int64 {
	if value {
		return 1
	}
	return 0
}

// placeholders builds "?, ?, ?" for an IN or NOT IN clause of n values.
func placeholders(n int) string {
	if n <= 0 {
		return ""
	}
	return strings.TrimSuffix(strings.Repeat("?, ", n), ", ")
}

// anySlice converts identifiers into the argument slice ExecContext wants.
func anySlice(values []string) []any {
	out := make([]any, len(values))
	for i, value := range values {
		out[i] = value
	}
	return out
}
