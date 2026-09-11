package state

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"
)

// Resumable uploads, and the parts of them that are already on the node's disk.
//
// Most customers upload from a phone on mobile data, so an upload is a series of
// acknowledged chunks against a session that outlives losing signal - and outlives the
// daemon being restarted underneath it, which is why the session is here rather than in a
// map in the files package. The bytes live in a staging file; this table is the record of
// which of them arrived.
//
// upload_range is in the same migration as upload_session because the foreign key that
// deletes the ranges with their session cannot be declared before the table it points at.
//
// Slightly over three hundred lines and deliberately not split: the received half of an
// upload already lives in upload_chunk.go, and what is left is one table, the struct that
// mirrors it and the five statements that touch it. Separating the DDL from the queries
// that depend on its column list is the split that makes a schema change miss a caller.
var uploadSchema = []string{`
CREATE TABLE upload_session (
	session_id     TEXT PRIMARY KEY,
	root_id        TEXT    NOT NULL,
	path           TEXT    NOT NULL,
	staging_path   TEXT    NOT NULL,
	total_bytes    INTEGER NOT NULL,
	chunk_size     INTEGER NOT NULL,
	content_sha256 TEXT    NOT NULL,
	overwrite      INTEGER NOT NULL,
	created_at     INTEGER NOT NULL,
	updated_at     INTEGER NOT NULL,
	expires_at     INTEGER NOT NULL,
	completed_at   INTEGER
) STRICT`, `
CREATE INDEX upload_session_expiry ON upload_session (completed_at, expires_at)`, `
CREATE TABLE upload_range (
	session_id    TEXT    NOT NULL REFERENCES upload_session (session_id) ON DELETE CASCADE,
	start         INTEGER NOT NULL,
	end_exclusive INTEGER NOT NULL,
	PRIMARY KEY (session_id, start)
) STRICT`}

// UploadSession is one file being uploaded, as declared by the browser and carried by the
// panel on every chunk (files.proto, UploadSession).
type UploadSession struct {
	// Minted by the browser, so it survives a page reload. That is what makes "resume"
	// mean anything to a customer who came back an hour later.
	SessionID string
	// Which FileRoot from the NodeSpec, and where in it the finished file goes.
	RootID string
	Path   string
	// Where the parts are being written, relative to the node's state directory. Stored
	// rather than derived so the sweeper and a restarted daemon can find and delete the
	// bytes of a session whose naming scheme has since changed.
	StagingPath string
	TotalBytes  int64
	ChunkSize   int64
	// Hex SHA-256 of the whole file, checked when the upload is completed. A resumed
	// upload that picked up a different version of the file fails there rather than
	// producing a plausible-looking corrupt result.
	ContentSHA256 string
	Overwrite     bool
	CreatedAt     time.Time
	UpdatedAt     time.Time
	// When an unfinished session may be swept
	// (workload.proto, RetentionPolicy.orphan_upload_ttl_seconds).
	ExpiresAt time.Time
	// Zero while the upload is in flight.
	CompletedAt time.Time
}

// Complete reports whether the file has been assembled and moved into place.
func (u UploadSession) Complete() bool {
	return !u.CompletedAt.IsZero()
}

// BeginUpload opens a session, or returns the one that is already open under that id.
//
// Idempotent on purpose. Every chunk repeats the session description, because the node may
// have restarted between two of them and has to be able to rebuild the session from what
// is on disk plus the message in hand. So the second call is the normal case, not a
// mistake, and it must not discard the ranges already recorded.
//
// A session reopened with a different destination, size, chunk size or checksum is
// ErrUploadConflict: either the browser reused an id or two uploads collided, and
// continuing would interleave two files into one staging file.
func (s *Store) BeginUpload(ctx context.Context, session UploadSession) (UploadSession, error) {
	if err := session.validate(); err != nil {
		return UploadSession{}, err
	}

	var stored UploadSession
	err := s.transact(ctx, func(tx *sql.Tx) error {
		existing, err := readUpload(ctx, tx, session.SessionID)
		switch {
		case errors.Is(err, ErrNotFound):
			if _, err := tx.ExecContext(ctx, `
INSERT INTO upload_session (
	session_id, root_id, path, staging_path, total_bytes, chunk_size,
	content_sha256, overwrite, created_at, updated_at, expires_at, completed_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)`,
				session.SessionID, session.RootID, session.Path, session.StagingPath,
				session.TotalBytes, session.ChunkSize, session.ContentSHA256, boolean(session.Overwrite),
				epochMillis(session.CreatedAt), epochMillis(session.UpdatedAt), epochMillis(session.ExpiresAt),
			); err != nil {
				return fmt.Errorf("state: open upload session %s: %w", session.SessionID, err)
			}
			stored = session
			return nil

		case err != nil:
			return err
		}

		if !existing.describesSameFileAs(session) {
			return fmt.Errorf("state: upload session %s was opened for %s in root %s and is now claimed for %s in root %s: %w",
				session.SessionID, existing.Path, existing.RootID, session.Path, session.RootID, ErrUploadConflict)
		}

		// Reopening extends the life of the session: a customer who is still uploading is
		// not an orphan, however long the file is taking on a slow connection.
		if _, err := tx.ExecContext(ctx,
			`UPDATE upload_session SET updated_at = ?, expires_at = ? WHERE session_id = ?`,
			epochMillis(session.UpdatedAt), epochMillis(session.ExpiresAt), session.SessionID,
		); err != nil {
			return fmt.Errorf("state: refresh upload session %s: %w", session.SessionID, err)
		}
		existing.UpdatedAt = session.UpdatedAt
		existing.ExpiresAt = session.ExpiresAt
		stored = existing
		return nil
	})
	if err != nil {
		return UploadSession{}, err
	}
	return stored, nil
}

// Upload returns one session, or ErrNotFound when the sweeper has already taken it or it
// was never opened here. The browser's answer to that is UploadState{known: false} and
// starting again from zero.
func (s *Store) Upload(ctx context.Context, sessionID string) (UploadSession, error) {
	return readUpload(ctx, s.db, sessionID)
}

// CompleteUpload marks the file assembled and in place.
//
// It refuses a session with a hole in it. Completion is the point at which the staging
// file is checksummed and moved, and a caller that reached here with bytes missing has a
// bug the customer would otherwise discover as a truncated file weeks later.
func (s *Store) CompleteUpload(ctx context.Context, sessionID string, at time.Time) error {
	return s.transact(ctx, func(tx *sql.Tx) error {
		session, err := readUpload(ctx, tx, sessionID)
		if err != nil {
			return err
		}
		ranges, err := readRanges(ctx, tx, sessionID)
		if err != nil {
			return err
		}
		if !Covers(ranges, session.TotalBytes) {
			return fmt.Errorf("state: upload session %s cannot be completed: %d of %d bytes received, first gap at %d",
				sessionID, ReceivedBytes(ranges), session.TotalBytes, FirstGap(ranges, session.TotalBytes))
		}
		if _, err := tx.ExecContext(ctx,
			`UPDATE upload_session SET completed_at = ?, updated_at = ? WHERE session_id = ?`,
			epochMillis(at), epochMillis(at), sessionID,
		); err != nil {
			return fmt.Errorf("state: complete upload session %s: %w", sessionID, err)
		}
		return nil
	})
}

// ForgetUpload removes a session and its ranges, and returns what it was so the caller can
// delete the staging file it names.
//
// The bytes are not this package's to remove - it owns the record, the files package owns
// the filesystem - but a caller that has just dropped the record and no longer knows where
// the parts were would leak them forever, so the session comes back rather than being
// discarded. This is what answers AbortUpload, and what the sweeper calls on each expired
// session.
func (s *Store) ForgetUpload(ctx context.Context, sessionID string) (UploadSession, error) {
	var forgotten UploadSession
	err := s.transact(ctx, func(tx *sql.Tx) error {
		session, err := readUpload(ctx, tx, sessionID)
		if err != nil {
			return err
		}
		// The ranges go with it through the foreign key declared above.
		if _, err := tx.ExecContext(ctx, `DELETE FROM upload_session WHERE session_id = ?`, sessionID); err != nil {
			return fmt.Errorf("state: forget upload session %s: %w", sessionID, err)
		}
		forgotten = session
		return nil
	})
	if err != nil {
		return UploadSession{}, err
	}
	return forgotten, nil
}

// ExpiredUploads lists the unfinished sessions whose time is up.
//
// Only unfinished ones. A completed session is still referenced - a build reads the
// archive its staging file holds - and it is removed by whoever asked for it, with
// ForgetUpload, rather than swept out from underneath them.
func (s *Store) ExpiredUploads(ctx context.Context, now time.Time) ([]UploadSession, error) {
	rows, err := s.db.QueryContext(ctx, uploadColumns+`
FROM upload_session
WHERE completed_at IS NULL AND expires_at <= ?
ORDER BY expires_at`, epochMillis(now))
	if err != nil {
		return nil, fmt.Errorf("state: list expired upload sessions: %w", err)
	}
	defer rows.Close()

	var out []UploadSession
	for rows.Next() {
		session, err := scanUpload(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, session)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("state: list expired upload sessions: %w", err)
	}
	return out, nil
}

func (u UploadSession) validate() error {
	switch {
	case u.SessionID == "":
		return errors.New("state: an upload session needs an id")
	case u.RootID == "":
		return fmt.Errorf("state: upload session %s names no file root", u.SessionID)
	case u.Path == "":
		return fmt.Errorf("state: upload session %s names no destination", u.SessionID)
	case u.StagingPath == "":
		return fmt.Errorf("state: upload session %s names no staging file", u.SessionID)
	case u.TotalBytes < 0:
		return fmt.Errorf("state: upload session %s declares a negative size", u.SessionID)
	case u.ChunkSize <= 0:
		return fmt.Errorf("state: upload session %s declares a chunk size of %d", u.SessionID, u.ChunkSize)
	}
	return nil
}

// describesSameFileAs is the identity of an upload: the same bytes going to the same
// place, cut the same way. Timestamps are excluded because they move with every chunk.
func (u UploadSession) describesSameFileAs(other UploadSession) bool {
	return u.RootID == other.RootID &&
		u.Path == other.Path &&
		u.TotalBytes == other.TotalBytes &&
		u.ChunkSize == other.ChunkSize &&
		u.ContentSHA256 == other.ContentSHA256
}

const uploadColumns = `
SELECT session_id, root_id, path, staging_path, total_bytes, chunk_size,
       content_sha256, overwrite, created_at, updated_at, expires_at, completed_at`

func readUpload(ctx context.Context, q queryer, sessionID string) (UploadSession, error) {
	row := q.QueryRowContext(ctx, uploadColumns+` FROM upload_session WHERE session_id = ?`, sessionID)
	session, err := scanUpload(row)
	if errors.Is(err, sql.ErrNoRows) {
		return UploadSession{}, fmt.Errorf("state: upload session %s: %w", sessionID, ErrNotFound)
	}
	return session, err
}

// scanner is *sql.Row and *sql.Rows, so one column list is read one way.
type scanner interface {
	Scan(destination ...any) error
}

func scanUpload(row scanner) (UploadSession, error) {
	var (
		session     UploadSession
		overwrite   int64
		createdAt   int64
		updatedAt   int64
		expiresAt   int64
		completedAt sql.NullInt64
	)
	err := row.Scan(&session.SessionID, &session.RootID, &session.Path, &session.StagingPath,
		&session.TotalBytes, &session.ChunkSize, &session.ContentSHA256, &overwrite,
		&createdAt, &updatedAt, &expiresAt, &completedAt)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return UploadSession{}, err
		}
		return UploadSession{}, fmt.Errorf("state: read an upload session: %w", err)
	}
	session.Overwrite = overwrite != 0
	session.CreatedAt = instant(createdAt)
	session.UpdatedAt = instant(updatedAt)
	session.ExpiresAt = instant(expiresAt)
	session.CompletedAt = optionalInstant(completedAt)
	return session, nil
}
