package state

import (
	"context"
	"database/sql"
	"fmt"
	"time"
)

// The received half of an upload: which byte ranges of a session are already on disk.
//
// Ranges rather than a count of chunks, because chunks are uploaded in parallel and a
// dropped connection leaves holes rather than a clean prefix (files.proto, UploadState).
// The table is kept coalesced at all times, so the answer to "what do you have" is a read
// rather than a computation, and so a session cannot accumulate one row per retry.

// UploadProgress is everything the panel needs to answer a ResumeUpload, and everything a
// browser needs to draw a progress bar and decide what to send next.
type UploadProgress struct {
	Session  UploadSession
	Received []ByteRange
	// Sum of Received, so nothing downstream has to add them up.
	ReceivedBytes int64
	// Where the client should carry on: the start of the first hole, or the end of the
	// file when there are none.
	NextOffset int64
	// Every byte is on disk and the session can be completed.
	Covered bool
}

// RecordChunk marks a run of bytes as received and returns where the upload now stands.
//
// Idempotent: a chunk that is sent twice - because the acknowledgement was lost, or
// because the browser retried after a timeout - merges into the range that already covers
// it and changes nothing. That is what makes a retry safe on a connection that drops
// often enough for retries to be routine.
//
// The range is checked against the session's declared size. Trusting a client-supplied
// offset is how a resumed upload gets talked into writing outside the file it declared.
func (s *Store) RecordChunk(ctx context.Context, sessionID string, received ByteRange, at time.Time) (UploadProgress, error) {
	if !received.Valid() {
		return UploadProgress{}, fmt.Errorf("state: upload session %s was given the range [%d,%d), which covers no bytes",
			sessionID, received.Start, received.EndExclusive)
	}

	var progress UploadProgress
	err := s.transact(ctx, func(tx *sql.Tx) error {
		session, err := readUpload(ctx, tx, sessionID)
		if err != nil {
			return err
		}
		if session.Complete() {
			return fmt.Errorf("state: upload session %s was completed at %s and takes no more chunks",
				sessionID, session.CompletedAt.Format(time.RFC3339))
		}
		if received.EndExclusive > session.TotalBytes {
			return fmt.Errorf("state: upload session %s declared %d bytes and was given the range [%d,%d)",
				sessionID, session.TotalBytes, received.Start, received.EndExclusive)
		}

		if err := absorbRange(ctx, tx, sessionID, received); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx,
			`UPDATE upload_session SET updated_at = ? WHERE session_id = ?`, epochMillis(at), sessionID,
		); err != nil {
			return fmt.Errorf("state: touch upload session %s: %w", sessionID, err)
		}
		session.UpdatedAt = at

		ranges, err := readRanges(ctx, tx, sessionID)
		if err != nil {
			return err
		}
		progress = progressOf(session, ranges)
		return nil
	})
	if err != nil {
		return UploadProgress{}, err
	}
	return progress, nil
}

// UploadProgress is what a resumed upload asks for before it sends anything.
func (s *Store) UploadProgress(ctx context.Context, sessionID string) (UploadProgress, error) {
	session, err := s.Upload(ctx, sessionID)
	if err != nil {
		return UploadProgress{}, err
	}
	ranges, err := readRanges(ctx, s.db, sessionID)
	if err != nil {
		return UploadProgress{}, err
	}
	return progressOf(session, ranges), nil
}

func progressOf(session UploadSession, ranges []ByteRange) UploadProgress {
	return UploadProgress{
		Session:       session,
		Received:      ranges,
		ReceivedBytes: ReceivedBytes(ranges),
		NextOffset:    FirstGap(ranges, session.TotalBytes),
		Covered:       Covers(ranges, session.TotalBytes),
	}
}

// absorbRange folds one run into the table, leaving it coalesced.
//
// Only the neighbours are touched: everything that overlaps the new run or sits end to end
// with it is replaced by a single row spanning all of them. Rewriting the whole session's
// ranges on every chunk would be simpler and quadratic, which for a two-gigabyte file cut
// into four-megabyte chunks is a hundred and thirty thousand pointless row writes.
func absorbRange(ctx context.Context, tx *sql.Tx, sessionID string, received ByteRange) error {
	neighbours, err := touchingRanges(ctx, tx, sessionID, received)
	if err != nil {
		return err
	}

	merged := received
	for _, neighbour := range neighbours {
		if neighbour.Start < merged.Start {
			merged.Start = neighbour.Start
		}
		if neighbour.EndExclusive > merged.EndExclusive {
			merged.EndExclusive = neighbour.EndExclusive
		}
	}

	// One of the neighbours may already cover the new run entirely, in which case this
	// rewrites the same row with the same values. Cheaper than working out whether it
	// would have, and impossible to get wrong.
	if len(neighbours) > 0 {
		if _, err := tx.ExecContext(ctx,
			`DELETE FROM upload_range WHERE session_id = ? AND end_exclusive >= ? AND start <= ?`,
			sessionID, received.Start, received.EndExclusive,
		); err != nil {
			return fmt.Errorf("state: coalesce the ranges of upload session %s: %w", sessionID, err)
		}
	}
	if _, err := tx.ExecContext(ctx,
		`INSERT INTO upload_range (session_id, start, end_exclusive) VALUES (?, ?, ?)`,
		sessionID, merged.Start, merged.EndExclusive,
	); err != nil {
		return fmt.Errorf("state: record bytes [%d,%d) of upload session %s: %w",
			merged.Start, merged.EndExclusive, sessionID, err)
	}
	return nil
}

// touchingRanges finds the rows that overlap the run or sit against either end of it.
// Half-open ranges make that one comparison in each direction, with no off-by-one to get
// wrong: [0,4) touches [4,8) because 4 >= 4.
func touchingRanges(ctx context.Context, tx *sql.Tx, sessionID string, received ByteRange) ([]ByteRange, error) {
	rows, err := tx.QueryContext(ctx,
		`SELECT start, end_exclusive FROM upload_range WHERE session_id = ? AND end_exclusive >= ? AND start <= ?`,
		sessionID, received.Start, received.EndExclusive)
	if err != nil {
		return nil, fmt.Errorf("state: read the ranges of upload session %s: %w", sessionID, err)
	}
	// Drained and closed before anything is written: a transaction runs one statement at a
	// time, so an insert issued while these rows are open would fail on the same
	// connection.
	defer rows.Close()

	var neighbours []ByteRange
	for rows.Next() {
		var neighbour ByteRange
		if err := rows.Scan(&neighbour.Start, &neighbour.EndExclusive); err != nil {
			return nil, fmt.Errorf("state: read the ranges of upload session %s: %w", sessionID, err)
		}
		neighbours = append(neighbours, neighbour)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("state: read the ranges of upload session %s: %w", sessionID, err)
	}
	return neighbours, rows.Close()
}

// readRanges returns a session's ranges, sorted and coalesced.
//
// MergeRanges runs over what the table returns even though the table is kept coalesced.
// The cost is nothing and the alternative is trusting an invariant that a future bug in
// absorbRange would break silently, in the one place - completion - where being wrong
// means declaring a file whole that has a hole in it.
func readRanges(ctx context.Context, q queryer, sessionID string) ([]ByteRange, error) {
	rows, err := q.QueryContext(ctx,
		`SELECT start, end_exclusive FROM upload_range WHERE session_id = ? ORDER BY start`, sessionID)
	if err != nil {
		return nil, fmt.Errorf("state: read the ranges of upload session %s: %w", sessionID, err)
	}
	defer rows.Close()

	var ranges []ByteRange
	for rows.Next() {
		var candidate ByteRange
		if err := rows.Scan(&candidate.Start, &candidate.EndExclusive); err != nil {
			return nil, fmt.Errorf("state: read the ranges of upload session %s: %w", sessionID, err)
		}
		ranges = append(ranges, candidate)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("state: read the ranges of upload session %s: %w", sessionID, err)
	}
	return MergeRanges(ranges), nil
}
