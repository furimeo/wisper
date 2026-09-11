package state

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"strings"
	"time"

	"modernc.org/sqlite"
)

// SQLite primary result codes for the two ways a file stops being a database. The
// connection runs with extended result codes on, so the low byte is the primary code and
// the rest describes where it happened.
const (
	sqliteCorrupt = 11 // SQLITE_CORRUPT: the disk image is malformed.
	sqliteNotADB  = 26 // SQLITE_NOTADB: the header is not SQLite's at all.
)

// RecoveredFrom is where the previous database went when Open found it unreadable, or the
// empty string when the database opened normally.
//
// The daemon reports this to the panel as a node event rather than letting it pass: a node
// that quietly lost its record of every build, every upload and every certificate looks
// healthy from the outside, and the first symptom would be a customer's resumable upload
// starting again from zero with no explanation.
func (s *Store) RecoveredFrom() string {
	return s.recoveredFrom
}

// verify decides whether the file is usable before the daemon commits to it.
//
// The connection is created lazily by database/sql, so this is also where the DSN's
// pragmas run: journal_mode=WAL on a file that is not a database fails here, which is
// exactly the answer wanted. quick_check rather than integrity_check because the node's
// database is small and the check runs on every start - quick_check reads every page and
// verifies the b-tree structure, and skips only the UNIQUE and NOT NULL re-validation
// that the schema enforces on write anyway.
func verify(ctx context.Context, db *sql.DB) error {
	rows, err := db.QueryContext(ctx, "PRAGMA quick_check(1)")
	if err != nil {
		return fmt.Errorf("state: check database: %w", err)
	}
	defer rows.Close()

	findings := make([]string, 0, 1)
	for rows.Next() {
		var line string
		if err := rows.Scan(&line); err != nil {
			return fmt.Errorf("state: check database: %w", err)
		}
		if !strings.EqualFold(strings.TrimSpace(line), "ok") {
			findings = append(findings, line)
		}
	}
	if err := rows.Err(); err != nil {
		return fmt.Errorf("state: check database: %w", err)
	}
	if len(findings) > 0 {
		return &corruptFileError{detail: strings.Join(findings, "; ")}
	}
	return nil
}

// corruptFileError is what verify returns for a file SQLite could read but found broken.
// A distinct type so isCorruption does not have to guess from a message.
type corruptFileError struct {
	detail string
}

func (e *corruptFileError) Error() string {
	return "state: database failed its integrity check: " + e.detail
}

// isCorruption reports whether err means the file is not a usable database, as opposed to
// the many reasons opening one legitimately fails - a full disk, a read-only mount, a
// directory that does not exist. Only the first justifies moving a file aside, and getting
// that wrong would destroy a perfectly good database because a disk was briefly full.
func isCorruption(err error) bool {
	var checkFailure *corruptFileError
	if errors.As(err, &checkFailure) {
		return true
	}

	var sqliteErr *sqlite.Error
	if errors.As(err, &sqliteErr) {
		switch sqliteErr.Code() & 0xFF {
		case sqliteCorrupt, sqliteNotADB:
			return true
		}
	}

	// Not every failure arrives as *sqlite.Error: some come back from sqlite3_open_v2
	// before there is a handle to attach a code to, and the driver reports those as plain
	// text. Matching the two messages SQLite uses for a broken file is a fallback, not the
	// primary test, and it is deliberately narrow.
	message := strings.ToLower(err.Error())
	return strings.Contains(message, "file is not a database") ||
		strings.Contains(message, "database disk image is malformed")
}

// quarantine renames the database and its sidecars out of the way and reports where they
// went.
//
// Renamed, never removed. The file is the only evidence of what happened to a node's
// memory, and an operator who finds a state.db.corrupt-... next to a working state.db can
// hand it to sqlite3 and find out. Deleting it would make the fault unreproducible the
// moment it recovers.
func quarantine(path string) (string, error) {
	target, err := freeName(path)
	if err != nil {
		return "", err
	}

	// The write-ahead log and the shared-memory index belong to the file being moved. A
	// WAL left next to a fresh database is a different database's transactions, and SQLite
	// would either refuse the file or replay them into it.
	for _, suffix := range []string{"", "-wal", "-shm"} {
		if err := os.Rename(path+suffix, target+suffix); err != nil && !errors.Is(err, os.ErrNotExist) {
			return "", fmt.Errorf("move %s aside: %w", path+suffix, err)
		}
	}
	return target, nil
}

// freeName picks a name no file is using. The timestamp is enough on its own except when
// a node crash-loops fast enough to corrupt and quarantine twice inside one second, and
// on Windows the second rename would fail rather than overwrite.
func freeName(path string) (string, error) {
	base := path + ".corrupt-" + time.Now().UTC().Format("20060102T150405Z")
	for attempt := 0; attempt < 100; attempt++ {
		candidate := base
		if attempt > 0 {
			candidate = fmt.Sprintf("%s-%d", base, attempt)
		}
		if _, err := os.Lstat(candidate); errors.Is(err, os.ErrNotExist) {
			return candidate, nil
		} else if err != nil {
			return "", fmt.Errorf("inspect %s: %w", candidate, err)
		}
	}
	return "", fmt.Errorf("no free name for %s after 100 attempts", base)
}
