package stats

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"google.golang.org/protobuf/proto"

	// Registers the "sqlite" driver. Pure Go, so the daemon stays one static file.
	_ "modernc.org/sqlite"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// A bounded ring of samples for a panel that is not there.
//
// Why on disk at all, when a sample is lossy by design: because the common outage is a
// tunnel wobbling for two minutes, and losing ten minutes of every customer's chart to it
// is a gap they notice. Keeping them in RAM instead would lose them to the daemon restart
// that an upgrade performs on purpose.
//
// Why bounded: because the other outage is a tunnel that has been down since Friday, and a
// node that spent the weekend writing statistics is a node that filled the disk this package
// exists to protect. Past the bound the oldest rows go, which is the right end to lose - a
// chart with a hole in last Friday is worth more than one with a hole in the last ten
// minutes.
//
// Why its own file rather than a table in the node's state database: that database
// deliberately serialises every statement onto one connection, and a ring trimmed every
// twelve seconds does not belong in front of the spec the reconcile loop is waiting to read.
// The two also have different value: state.db is the node's memory and is recovered rather
// than deleted when it is damaged, while this one can be thrown away without losing anything
// the node needs to keep running.

// BufferFileName is what the buffer is called inside the node's state directory.
const BufferFileName = "stats.db"

// bufferSchema is the whole of it. One table, no migration runner: a file that cannot be
// read is deleted and recreated (openBuffer), because everything in it is a description of
// a moment that has already passed.
const bufferSchema = `
CREATE TABLE IF NOT EXISTS stat_buffer (
	id       INTEGER PRIMARY KEY,
	taken_at INTEGER NOT NULL,
	payload  BLOB    NOT NULL
) STRICT`

// buffer is the ring.
//
// The rowid is the order, and it is safe to rely on: every deletion here removes a prefix -
// the trim drops the oldest, the drain discards what has just been delivered - so the
// highest rowid only ever decreases when the table empties, and an empty table has no order
// to get wrong.
type buffer struct {
	db       *sql.DB
	path     string
	capacity int
}

// openBuffer prepares the ring at path.
//
// A file that will not open is moved out of the way and a new one started. Refusing to run
// because a cache of statistics is corrupt would be trading the whole node for a chart.
func openBuffer(ctx context.Context, path string, capacity int) (*buffer, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, fmt.Errorf("stats: create %s: %w", filepath.Dir(path), err)
	}

	db, err := openBufferFile(ctx, path)
	if err == nil {
		return &buffer{db: db, path: path, capacity: capacity}, nil
	}

	if removeErr := os.Remove(path); removeErr != nil && !os.IsNotExist(removeErr) {
		return nil, fmt.Errorf("stats: %s is unusable (%v) and could not be removed: %w", path, err, removeErr)
	}
	replacement, retryErr := openBufferFile(ctx, path)
	if retryErr != nil {
		return nil, fmt.Errorf("stats: %s was unusable (%v) and a replacement could not be created: %w",
			path, err, retryErr)
	}
	return &buffer{db: replacement, path: path, capacity: capacity}, nil
}

// openBufferFile is one attempt: connect, prove the file is readable, create the table.
func openBufferFile(ctx context.Context, path string) (*sql.DB, error) {
	// The path is not prefixed with "file:", so the driver hands SQLite the plain path and
	// a Windows drive letter is not parsed as a URI scheme. synchronous=OFF because this
	// file is disposable: an fsync every twelve seconds for data that is a description of
	// an already-past moment is a write amplification nobody is buying anything with.
	settings := []string{
		"_pragma=busy_timeout(5000)",
		"_pragma=journal_mode(WAL)",
		"_pragma=synchronous(OFF)",
		"_txlock=immediate",
	}
	db, err := sql.Open("sqlite", path+"?"+strings.Join(settings, "&"))
	if err != nil {
		return nil, fmt.Errorf("stats: open %s: %w", path, err)
	}
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(1)
	db.SetConnMaxLifetime(0)

	if _, err := db.ExecContext(ctx, bufferSchema); err != nil {
		db.Close()
		return nil, fmt.Errorf("stats: prepare %s: %w", path, err)
	}
	return db, nil
}

// Close releases the file.
func (b *buffer) Close() error {
	if err := b.db.Close(); err != nil {
		return fmt.Errorf("stats: close %s: %w", b.path, err)
	}
	return nil
}

// append writes samples down and trims the ring back to its bound in one transaction, so a
// daemon killed mid-append leaves either all of a pass's samples or none of them.
func (b *buffer) append(ctx context.Context, samples []*wisperpb.StatSample) error {
	if len(samples) == 0 {
		return nil
	}
	tx, err := b.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("stats: begin a buffer write: %w", err)
	}
	defer tx.Rollback()

	for _, sample := range samples {
		payload, err := proto.Marshal(sample)
		if err != nil {
			return fmt.Errorf("stats: encode a sample for the buffer: %w", err)
		}
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO stat_buffer (taken_at, payload) VALUES (?, ?)`,
			sample.GetTakenAt().AsTime().UnixMilli(), payload,
		); err != nil {
			return fmt.Errorf("stats: buffer a sample: %w", err)
		}
	}

	// Everything older than the capacity-th newest row. A subquery that returns no row -
	// fewer rows than the bound - compares as NULL and deletes nothing, which is the
	// behaviour wanted and the reason this is one statement rather than a count and a
	// branch.
	if _, err := tx.ExecContext(ctx,
		`DELETE FROM stat_buffer WHERE id < (SELECT id FROM stat_buffer ORDER BY id DESC LIMIT 1 OFFSET ?)`,
		b.capacity-1,
	); err != nil {
		return fmt.Errorf("stats: trim the buffer to %d samples: %w", b.capacity, err)
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("stats: commit a buffer write: %w", err)
	}
	return nil
}

// buffered is one sample waiting, with the row it came from.
type buffered struct {
	id     int64
	sample *wisperpb.StatSample
}

// take reads the oldest limit samples without removing them.
//
// Reading and deleting are separate because the panel is between them: a row removed before
// the uplink accepted it is a row lost to the reconnect that was about to happen. A row that
// is delivered twice is a duplicate the panel folds into the same rollup bucket, which is
// the cheaper of the two mistakes.
//
// A row that will not decode is dropped rather than returned: it was written by a build with
// a different wire format, and leaving it at the head of the queue would block every sample
// behind it forever.
func (b *buffer) take(ctx context.Context, limit int) ([]buffered, error) {
	rows, err := b.db.QueryContext(ctx,
		`SELECT id, payload FROM stat_buffer ORDER BY id LIMIT ?`, limit)
	if err != nil {
		return nil, fmt.Errorf("stats: read the buffer: %w", err)
	}
	defer rows.Close()

	var taken []buffered
	var undecodable []int64
	for rows.Next() {
		var id int64
		var payload []byte
		if err := rows.Scan(&id, &payload); err != nil {
			return nil, fmt.Errorf("stats: read the buffer: %w", err)
		}
		sample := &wisperpb.StatSample{}
		if err := proto.Unmarshal(payload, sample); err != nil {
			undecodable = append(undecodable, id)
			continue
		}
		taken = append(taken, buffered{id: id, sample: sample})
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("stats: read the buffer: %w", err)
	}
	rows.Close()

	if len(undecodable) > 0 {
		if err := b.discard(ctx, undecodable); err != nil {
			return taken, err
		}
	}
	return taken, nil
}

// discard removes rows that have been handed to the uplink.
func (b *buffer) discard(ctx context.Context, ids []int64) error {
	if len(ids) == 0 {
		return nil
	}
	arguments := make([]any, len(ids))
	for index, id := range ids {
		arguments[index] = id
	}
	query := `DELETE FROM stat_buffer WHERE id IN (` +
		strings.TrimSuffix(strings.Repeat("?, ", len(ids)), ", ") + `)`
	if _, err := b.db.ExecContext(ctx, query, arguments...); err != nil {
		return fmt.Errorf("stats: remove %d delivered samples from the buffer: %w", len(ids), err)
	}
	return nil
}

// count is how many samples are waiting. Reported in the snapshot, so a node that has been
// away is visibly holding something rather than merely quiet.
func (b *buffer) count(ctx context.Context) (int, error) {
	var total int
	err := b.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM stat_buffer`).Scan(&total)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return 0, fmt.Errorf("stats: count the buffered samples: %w", err)
	}
	return total, nil
}
