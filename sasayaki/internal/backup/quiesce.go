package backup

import (
	"context"
	"fmt"
	"log/slog"
	"time"
)

// Stopping the writes, for as short a time as anything can be stopped for.
//
// The window covers the read of the volume and nothing else. Compressing, uploading and
// verifying all happen afterwards against a file on local disk with the application running
// again, so a backup of a volume that takes twenty minutes pauses the customer for the four
// seconds it took to read their data - and BackupCompleted.quiesce_millis reports the four
// rather than the twenty.
//
// Pausing rather than stopping. The engine's freezer suspends the processes and leaves every
// socket, every connection and every open file exactly where it was; a stop and a start would
// drop them all, and a customer whose application reconnects to its database every night at
// three would eventually notice. It is also why release is deferred on every path including
// the failed one, and why it uses a context that cannot be cancelled: a snapshot that ran out
// of time must not leave the application frozen because the deadline that killed it also
// killed the call that would have thawed it.

// held is a workload that has been paused, and the measurement of how long for.
type held struct {
	runner     *Runner
	workloadID string
	paused     bool
	startedAt  time.Time
	elapsed    time.Duration
	released   bool
}

// hold pauses the workload that owns a volume.
//
// A workload that is not running is not paused and not an error: a customer who has scaled
// their application to zero still gets backups of its data, and nothing is writing to it.
func (r *Runner) hold(ctx context.Context, workloadID string) (*held, error) {
	holding := &held{runner: r, workloadID: workloadID}
	if workloadID == "" {
		return holding, nil
	}

	running, err := r.workloads.Running(ctx, workloadID)
	if err != nil {
		// Not "assume it is stopped". The runtime could not answer, so whether anything is
		// writing to the volume is unknown, and taking a copy on that basis is how a
		// half-written database file becomes somebody's only backup.
		return nil, fmt.Errorf("backup: could not tell whether workload %s is running, so its "+
			"volume was not copied: %w", workloadID, err)
	}
	if !running {
		r.log.Info("the workload that owns this volume is not running, so nothing was paused",
			slog.String("workload", workloadID))
		return holding, nil
	}

	holding.startedAt = r.now()
	if err := r.workloads.Pause(ctx, workloadID); err != nil {
		return nil, fmt.Errorf("backup: pause workload %s before copying its volume: %w", workloadID, err)
	}
	holding.paused = true
	return holding, nil
}

// release lets the workload go again and returns how long it was held.
//
// Safe to call twice, because it is called once on the success path - as early as possible,
// the instant the bytes are read - and once from a deferred function that covers every other
// path.
func (h *held) release(ctx context.Context) time.Duration {
	if h.released {
		return h.elapsed
	}
	h.released = true
	if !h.paused {
		return 0
	}

	// Deliberately not the caller's context. The most important unpause is the one after a
	// snapshot that ran out of time, and issuing it through the deadline that just expired
	// would leave the customer's application frozen until somebody restarted the daemon.
	if err := h.runner.workloads.Unpause(context.WithoutCancel(ctx), h.workloadID); err != nil {
		// Reported at error level and not returned. The backup's own outcome is a separate
		// question, and there is nothing further this code can do about it - but a workload
		// left paused is the worst state this package can leave a node in, so it is said as
		// loudly as a log line can say anything.
		h.runner.log.Error("could not unpause a workload after its volume was copied; it is "+
			"frozen and needs an operator",
			slog.String("workload", h.workloadID), slog.String("error", err.Error()))
	}
	h.elapsed = h.runner.now().Sub(h.startedAt)
	if h.elapsed < 0 {
		h.elapsed = 0
	}
	h.runner.log.Info("volume writes were paused",
		slog.String("workload", h.workloadID),
		slog.Duration("for", h.elapsed))
	return h.elapsed
}
