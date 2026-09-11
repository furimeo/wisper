package reconcile

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"sync/atomic"
	"time"
)

// DefaultInterval is how often the loop runs when the spec does not say.
//
// The spec carries reconcile_interval_seconds so the figure can be tuned without shipping
// a new binary, but a node that has never been given a spec still has to be converging -
// so the default lives here as well as there.
const DefaultInterval = 15 * time.Second

// maxBackoff is how far apart two passes may drift while something is broken.
//
// A minute rather than the several that an exponential schedule would reach on its own.
// The node is degraded, not idle: it is still expected to say so in a status batch at a
// cadence somebody watching a dashboard would call live, and the thing it is waiting for -
// a Docker daemon coming back - is usually seconds away, not hours.
const maxBackoff = time.Minute

// Loop is the reconciler. One per daemon.
//
// Its fields divide into three groups, and knowing which is which is the whole of its
// concurrency story:
//
//   - The collaborators and the clock, written once at construction and never again.
//   - drained and passStartedAt, which other goroutines touch, and which are therefore
//     atomic.
//   - syncedRoutes and dockerDownSince, which only a pass touches. Passes never overlap
//     and each one is handed to the next through a channel, so no lock is needed and none
//     would make anything safer.
type Loop struct {
	runtime   Runtime
	sites     Sites
	edge      Edge
	store     Store
	databases Databases
	reporter  Reporter

	log      *slog.Logger
	now      func() time.Time
	watchdog watchdog

	// Capacity one, so a burst of ReconcileNow calls collapses into a single early pass.
	// Losing one entirely is harmless by design: the tick would have got there anyway,
	// which is what makes the nudge safe to drop rather than something to queue.
	nudge chan string

	// True once a drain has evacuated what it can. Read by every pass, written by Drain
	// on a gRPC handler's goroutine.
	drained atomic.Bool
	// When the pass in flight began, in Unix nanoseconds; zero between passes. The
	// watchdog reads it to tell a slow pass from a wedged one.
	passStartedAt atomic.Int64

	// The route table the edge last accepted, so a pass that changes nothing does not
	// rebuild the edge's configuration.
	syncedRoutes string
	// When the engine first stopped answering, so the panel is told "unreachable since
	// 12:04" rather than "unreachable", which tells an operator nothing.
	dockerDownSince time.Time

	// Only the Run goroutine touches these.
	interval time.Duration
	failures int
	ready    sync.Once
}

// ReconcileNow wakes the loop early.
//
// It returns immediately - it is a nudge, not the work - and losing it is harmless, which
// is exactly why the panel is allowed to send it as a bare optimisation. The fifteen-second
// tick would have got there anyway.
func (l *Loop) ReconcileNow(reason string) {
	select {
	case l.nudge <- reason:
	default:
		// One is already queued. A second would produce a second pass over the same spec.
	}
}

// passResult is what one pass tells the loop that scheduled it.
type passResult struct {
	err error
	// The interval the spec asked for, or zero when the pass did not get far enough to
	// read one.
	interval time.Duration
}

// Run converges the machine until the context is cancelled.
//
// It returns only when that happens. There is no failure it reports by giving up: a node
// whose panel is unreachable, whose engine is down or whose spec is damaged is still a node
// that has to keep serving customers and keep trying (design section 7.6).
//
// The first pass starts immediately rather than fifteen seconds in. A daemon that has just
// been restarted for an upgrade is the case that matters: whatever it was in the middle of
// is unfinished, and waiting a tick to find that out is a tick of a customer's site being
// down for no reason.
func (l *Loop) Run(ctx context.Context) error {
	watchdogTick, stopWatchdog := l.watchdog.ticker()
	defer stopWatchdog()

	timer := time.NewTimer(0)
	defer timer.Stop()

	finished := make(chan passResult, 1)
	running := false

	for {
		// A nil channel blocks forever, which is how "do not start a pass while one is
		// running" is expressed without a second state machine. A nudge that arrives
		// mid-pass stays in its buffer and is taken the moment the loop is idle again.
		var due <-chan time.Time
		var nudges <-chan string
		if !running {
			due = timer.C
			nudges = l.nudge
		}

		select {
		case <-ctx.Done():
			if running {
				// Not cleanup - the daemon is crash-only and the truth is already on disk
				// - but letting the action in flight finish rather than being cut off
				// halfway through a container being created.
				<-finished
			}
			return ctx.Err()

		case <-watchdogTick:
			l.watchdog.feed(l.log, l.passStarted(), l.now())

		case <-due:
			running = true
			go l.runPass(ctx, "the reconcile interval elapsed", finished)

		case reason := <-nudges:
			stopTimer(timer)
			running = true
			go l.runPass(ctx, reason, finished)

		case result := <-finished:
			running = false
			l.watchdog.feed(l.log, time.Time{}, l.now())
			l.ready.Do(func() { l.watchdog.notifyReady(l.log) })
			if result.interval > 0 {
				l.interval = result.interval
			}
			timer.Reset(l.nextDelay(result.err))
		}
	}
}

// runPass is the goroutine one pass runs on.
//
// On its own goroutine, and not inline in the select above, for one reason: the watchdog
// notification has to keep going out while a legitimately slow pass is working, and it has
// to stop going out when a pass has hung. Both are decisions the select loop makes about a
// pass it can see, which it cannot do while executing one.
func (l *Loop) runPass(ctx context.Context, reason string, finished chan<- passResult) {
	started := l.now()
	l.passStartedAt.Store(started.UnixNano())
	defer l.passStartedAt.Store(0)

	interval, err := l.pass(ctx, reason)
	if err != nil && !errors.Is(err, context.Canceled) {
		l.log.Warn("reconcile pass did not finish",
			slog.String("reason", reason),
			slog.Duration("took", l.now().Sub(started)),
			slog.String("error", err.Error()))
	}
	finished <- passResult{err: err, interval: interval}
}

// nextDelay is how long until the next pass.
//
// Doubling on failure, back to the interval on success. The thing that usually fails here
// is a Docker daemon that is restarting, and hammering its socket every fifteen seconds
// while it comes up neither helps it nor tells anybody anything new.
func (l *Loop) nextDelay(err error) time.Duration {
	if err == nil {
		l.failures = 0
		return l.interval
	}

	l.failures++
	// Never shorter than the interval. A panel that asked for a five-minute cadence must
	// not find that a broken node talks to it more often than a working one does.
	ceiling := max(maxBackoff, l.interval)
	delay := l.interval
	for range l.failures - 1 {
		if delay >= ceiling {
			break
		}
		delay *= 2
	}
	return min(delay, ceiling)
}

// passStarted is the moment the pass in flight began, or the zero time when there is none.
func (l *Loop) passStarted() time.Time {
	nanos := l.passStartedAt.Load()
	if nanos == 0 {
		return time.Time{}
	}
	return time.Unix(0, nanos)
}

// stopTimer stops a timer and drains it, so a fire that happened between the last check and
// now does not start a redundant pass immediately after the one about to begin.
func stopTimer(timer *time.Timer) {
	if timer.Stop() {
		return
	}
	select {
	case <-timer.C:
	default:
	}
}
