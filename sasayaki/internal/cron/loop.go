package cron

import (
	"context"
	"log/slog"
	"sync"
	"time"
)

// The loop: wake, re-read the spec when it is due, fire what is due, sleep until the next of
// those two things.
//
// It is not a fixed ticker, and that is the one design decision in this file. A cron entry is
// due at a wall-clock minute, so a loop that woke every fifteen seconds would fire the
// customer's three-o'clock job at some point between 03:00:00 and 03:00:15, which is fine
// until the job is the one that has to be finished before the next one starts. Sleeping until
// the earlier of "the next entry is due" and "the spec is due a re-read" costs nothing extra
// and lands on the minute.
//
// Sleeping is also bounded by the refresh interval for a second reason: a timer set for six
// hours is a timer that a suspended laptop, a stepped clock or a container that was paused
// will get wrong. Waking at least every fifteen seconds and comparing against the clock means
// the worst any of those can do is fire an entry up to one interval late - once, not once per
// minute it was asleep for.

// Scheduler fires the cron entries in the node's spec. One per daemon.
//
// Its fields divide in two, and knowing which is which is the whole of its concurrency story.
// The collaborators, the clock and entries belong to the loop goroutine: passes never overlap
// and nothing else touches them, so no lock would make them safer. The in-flight counter is
// the only thing an execution's own goroutine writes, and it is behind the mutex.
type Scheduler struct {
	containers Containers
	store      Store

	log     *slog.Logger
	now     func() time.Time
	refresh time.Duration

	// entries is what the last spec asked for, by cron id. Only the loop goroutine reads or
	// writes it.
	entries map[string]*entry
	// order keeps the entries in spec order, so what the log says happened in one tick is in
	// the order the customer sees them on the screen.
	order []string
	// refreshedAt is when the spec was last read. Zero until the first read.
	refreshedAt time.Time
	// scheduled is the set of cron ids the store was last pruned against, so a spec resent
	// every fifteen seconds does not become a DELETE every fifteen seconds.
	scheduled []string

	mu       sync.Mutex
	inFlight map[string]int
}

// Run fires scheduled commands until the context is cancelled.
//
// It returns only when that happens. There is no failure it gives up on: a spec that will not
// load, an engine that is not answering and a disk that will not take a row are all things
// that get better on their own, and a scheduler that stopped at the first of them would be a
// customer's backup silently not happening from then on.
//
// Executions it has already started are not waited for. They are deliberately detached from
// this context (run.go): a docker exec is a child of the container, not of this process, so
// an upgrade restarting the daemon leaves the customer's script running exactly as a crash
// would, and state.ClearRunningCronRuns turns the record of it into an honest "interrupted"
// on the way back up.
func (s *Scheduler) Run(ctx context.Context) error {
	// Fires immediately. A daemon that has just restarted has entries to read and next-run
	// times to write, and the panel showing "no next run" for the first fifteen seconds after
	// every upgrade would be a lie it is easy to avoid.
	timer := time.NewTimer(0)
	defer timer.Stop()

	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-timer.C:
		}

		s.tick(ctx, s.now())
		timer.Reset(s.sleep(s.now()))
	}
}

// tick is one wake-up: re-read if it is time to, then fire everything that is due.
func (s *Scheduler) tick(ctx context.Context, now time.Time) {
	if s.refreshedAt.IsZero() || !now.Before(s.refreshedAt.Add(s.refresh)) {
		s.refreshedAt = now
		s.reload(ctx, now)
	}

	for _, id := range s.order {
		e := s.entries[id]
		// A zero next-run time is an entry that never fires: an expression this node could
		// not read, or one that matches no date that will ever happen. Both were said out
		// loud when they were read; neither is worth a line per tick.
		if e.next.IsZero() || e.next.After(now) {
			continue
		}
		s.dispatch(ctx, e, now)
	}
}

// begin claims a slot for one execution.
//
// The counter is the only state two goroutines share here. The loop is the only caller of
// begin and running, and an execution's own goroutine is the only caller of end, so the lock
// is held for a map operation and nothing longer.
func (s *Scheduler) begin(cronID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.inFlight[cronID]++
}

func (s *Scheduler) end(cronID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.inFlight[cronID] <= 1 {
		delete(s.inFlight, cronID)
		return
	}
	s.inFlight[cronID]--
}

// running is whether an execution of this entry is still going. A count rather than a flag,
// because an entry that allows overlap can have several.
func (s *Scheduler) running(cronID string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.inFlight[cronID] > 0
}

// sleep is how long until the loop next has something to do.
//
// The earlier of the next firing and the next re-read, floored so that arithmetic can never
// turn into a spin.
func (s *Scheduler) sleep(now time.Time) time.Duration {
	wake := s.refreshedAt.Add(s.refresh)
	for _, e := range s.entries {
		if !e.next.IsZero() && e.next.Before(wake) {
			wake = e.next
		}
	}
	if delay := wake.Sub(now); delay > minSleep {
		return delay
	}
	return minSleep
}
