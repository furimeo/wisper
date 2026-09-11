package dbengine

import (
	"context"
	"log/slog"
	"time"
)

// The loop that keeps the servers running.
//
// A loop of its own rather than a step inside the workload reconciler, for one reason that is
// worth being precise about: the workload loop is the one systemd's watchdog is watching, and
// it has to finish a pass in far less time than WatchdogSec. Creating a database server can
// take minutes - a cold PostgreSQL image is hundreds of megabytes and initdb is not instant -
// so putting it on that path would have the watchdog restart a daemon that was doing exactly
// what it was asked. The reconcile loop asks this package only for measurements, which are two
// round trips and always fast (reconcile/ports.go, Databases).
//
// Everything else about it is the same by design: a fixed tick whether or not anything
// happened, backoff after a failure, and a pass that reports what went wrong and carries on
// rather than stopping at the first problem. A node whose database server was stopped by hand
// three weeks ago and never noticed is exactly the failure the fifteen-second tick exists to
// prevent.

const (
	// backoffCeiling is how far a repeated failure backs the tick off.
	//
	// A registry that is refusing an image or a disk that is full will not be fixed by being
	// asked four times a minute, and the log line each attempt writes is what fills the disk
	// the next attempt is failing on. It never gives up: the panel may be about to publish a
	// spec that fixes it.
	backoffCeiling = 5 * time.Minute

	// backoffFactor doubles the wait after each consecutive failure.
	backoffFactor = 2
)

// Run converges the database servers until the context ends.
//
// Blocking. The composition root starts it on its own goroutine and cancels the context on
// shutdown; there is nothing to clean up on the way out, because the servers are meant to
// outlive the daemon (AGENTS.md section 4.4).
func (e *Engines) Run(ctx context.Context) error {
	wait := e.tick(ctx)
	failures := 0

	for {
		started := time.Now()
		err := e.Converge(ctx)
		switch {
		case ctx.Err() != nil:
			// The daemon is shutting down. Whatever the pass said about being interrupted is
			// not news.
			return nil
		case err != nil:
			failures++
			wait = backoff(e.tick(ctx), failures)
			e.log.Warn("a database server pass did not fully succeed",
				slog.Int("consecutive_failures", failures),
				slog.Duration("took", time.Since(started)),
				slog.Duration("retry_in", wait),
				slog.String("error", err.Error()))
		default:
			if failures > 0 {
				e.log.Info("the database servers converged again",
					slog.Int("after_failures", failures))
			}
			failures = 0
			wait = e.tick(ctx)
		}

		select {
		case <-ctx.Done():
			return nil
		case <-time.After(wait):
		}
	}
}

// backoff is the interval after a run of failures: the ordinary tick doubled once per
// consecutive failure, up to the ceiling.
func backoff(interval time.Duration, failures int) time.Duration {
	if failures <= 0 {
		return interval
	}
	wait := interval
	for i := 0; i < failures && wait < backoffCeiling; i++ {
		wait *= backoffFactor
	}
	if wait > backoffCeiling {
		return backoffCeiling
	}
	return wait
}
