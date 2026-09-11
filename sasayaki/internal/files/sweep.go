package files

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"path/filepath"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/state"
)

// Reclaiming what abandoned uploads left behind.
//
// A phone that lost signal mid-upload leaves parts on the node and nobody comes back for
// them. Nothing else notices: the session is a row the browser was going to ask about
// again, and the bytes are a file inside no file root, so neither a listing nor a backup
// nor the reconcile loop would ever mention them. Without this the node fills up over
// weeks and the first symptom is a deployment failing for no visible reason
// (workload.proto, RetentionPolicy.orphan_upload_ttl_seconds).
//
// Called by the daemon on the reconcile interval. It is deliberately not a loop of its
// own: the daemon owns the loops, and a package that starts a goroutine in its constructor
// is one nobody can stop.

// SweepReport is what one pass reclaimed, for the daemon's log.
type SweepReport struct {
	// Sessions is how many unfinished uploads passed their expiry and were dropped.
	Sessions int
	// Orphans is how many parts files had no session at all - the remains of a daemon
	// killed between writing the bytes and recording them, which is exactly the window the
	// write order in upload_write.go leaves open on purpose.
	Orphans int
	// StagedArchives is how many finished archives were removed from the node's own upload
	// staging root because no build ever consumed them.
	StagedArchives int
}

// SweepUploads removes what is past its time. It never touches a customer's file root: the
// only directories it walks are the node's own.
func (h *Host) SweepUploads(ctx context.Context, now time.Time) (SweepReport, error) {
	report := SweepReport{}

	expired, err := h.store.ExpiredUploads(ctx, now)
	if err != nil {
		return report, err
	}
	for _, session := range expired {
		if failed := ctx.Err(); failed != nil {
			return report, failed
		}
		release, locked := h.lockSession(ctx, session.SessionID)
		if locked != nil {
			return report, locked
		}
		h.forget(ctx, session.SessionID)
		release()
		report.Sessions++
	}

	orphans, err := h.sweepOrphanParts(ctx)
	if err != nil {
		return report, err
	}
	report.Orphans = orphans

	staged, err := h.sweepStagedArchives(ctx, now)
	if err != nil {
		return report, err
	}
	report.StagedArchives = staged

	if report.Sessions+report.Orphans+report.StagedArchives > 0 {
		h.log.Info("swept abandoned uploads",
			slog.Int("sessions", report.Sessions),
			slog.Int("orphan_parts", report.Orphans),
			slog.Int("staged_archives", report.StagedArchives))
	}
	return report, nil
}

// sweepOrphanParts removes parts files whose session is not in the database.
//
// That happens two ways, and both are normal rather than alarming. The daemon can be
// killed between writing a chunk and recording it, which leaves a file with no row when
// the row was the session's first. And a session can be forgotten while its file is being
// removed. Neither is worth an error; both are worth reclaiming.
func (h *Host) sweepOrphanParts(ctx context.Context) (int, error) {
	directory := partsRoot(h.stateDir)
	entries, err := os.ReadDir(directory)
	if os.IsNotExist(err) {
		// Nothing has ever been uploaded on this node.
		return 0, nil
	}
	if err != nil {
		return 0, err
	}

	removed := 0
	for _, entry := range entries {
		if err := ctx.Err(); err != nil {
			return removed, err
		}
		if entry.IsDir() {
			continue
		}
		sessionID := entry.Name()
		if _, err := h.store.Upload(ctx, sessionID); err == nil {
			continue
		} else if !errors.Is(err, state.ErrNotFound) {
			return removed, err
		}

		release, locked := h.lockSession(ctx, sessionID)
		if locked != nil {
			return removed, locked
		}
		err := h.removePartsFile(sessionID)
		release()
		if err != nil {
			h.log.Warn("an orphaned upload parts file could not be removed",
				slog.String("session_id", sessionID), slog.String("error", err.Error()))
			continue
		}
		removed++
	}
	return removed, nil
}

// sweepStagedArchives removes finished uploads from the node's own staging root.
//
// The staging root is where the panel pushes a zip before it asks for a build; it is not
// shown to customers and nothing on the node reads it after the build has run
// (workload.proto, FILE_ROOT_KIND_UPLOAD_STAGING). An archive still there a day later
// belongs to a build that finished, failed or was never asked for, and in all three cases
// it is dead weight on a node's disk.
//
// The top level only. Every archive the panel pushes is one file at the root of the
// staging tree, and walking deeper would be inventing a layout nobody writes.
func (h *Host) sweepStagedArchives(ctx context.Context, now time.Time) (int, error) {
	ttl := h.retention(ctx).OrphanUploadTTL
	if ttl <= 0 {
		ttl = defaultOrphanTTL
	}

	directory := stagingRoot(h.stateDir)
	entries, err := os.ReadDir(directory)
	if os.IsNotExist(err) {
		return 0, nil
	}
	if err != nil {
		return 0, err
	}

	removed := 0
	for _, entry := range entries {
		if err := ctx.Err(); err != nil {
			return removed, err
		}
		if entry.IsDir() {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			continue
		}
		if now.Sub(info.ModTime()) < ttl {
			continue
		}
		if err := os.Remove(filepath.Join(directory, entry.Name())); err != nil && !os.IsNotExist(err) {
			h.log.Warn("a staged archive could not be removed",
				slog.String("name", entry.Name()), slog.String("error", err.Error()))
			continue
		}
		removed++
	}
	return removed, nil
}
