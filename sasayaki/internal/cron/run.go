package cron

import (
	"context"
	"fmt"
	"log/slog"
	"runtime/debug"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/runtime"
)

// One firing: deciding whether it may start, starting it, and surviving it.
//
// Every execution is on its own goroutine. Not for throughput - a node runs a handful of
// these a minute at most - but because the loop has to keep its tick while a job that takes
// six hours is running, and because a job is the one thing here that can fail in a way the
// loop cannot predict. A panic inside one is caught in the goroutine it happened on, written
// against the entry that caused it, and forgotten; the loop never sees it.

// dispatch starts one firing, or records why it did not.
func (s *Scheduler) dispatch(ctx context.Context, e *entry, now time.Time) {
	// The schedule moves on before the command starts, deliberately. An entry whose next-run
	// time is advanced only by a run that finished would fire on every tick for as long as
	// one execution was stuck, which is precisely the pile-up the timeout exists to prevent.
	s.advance(ctx, e, now)

	if !e.cron.AllowOverlap && s.running(e.cron.ID) {
		// Overlap is opt-in because the common case - a backup script, an importer - corrupts
		// itself when two copies run at once. Recorded rather than dropped: a schedule that
		// looks like it has stopped firing has to be able to say why.
		s.log.Info("a scheduled command was skipped because the previous run has not finished",
			slog.String("cron", e.cron.ID), slog.String("workload", e.cron.WorkloadID))
		if err := s.store.SkipCronRun(ctx, e.cron.ID, e.cron.WorkloadID, now); err != nil {
			s.log.Warn("could not record a skipped run", slog.String("cron", e.cron.ID),
				slog.String("error", err.Error()))
		}
		return
	}

	s.begin(e.cron.ID)
	go func() {
		defer s.end(e.cron.ID)
		defer s.rescue(ctx, e)
		s.execute(ctx, e, now)
	}()
}

// execute runs one command to its end and writes down how it went.
func (s *Scheduler) execute(ctx context.Context, e *entry, at time.Time) {
	timeout := e.cron.Timeout
	if timeout <= 0 {
		timeout = DefaultTimeout
	}

	// The bookkeeping context is detached from the loop's, and the command's is that with a
	// deadline. Two reasons, and they are different ones.
	//
	// Detached, because a docker exec is a child of the container rather than of this
	// process: killing a customer's script because the daemon is being upgraded would do
	// something a crash would not, and state.ClearRunningCronRuns already has an honest answer
	// for a run whose end this node never saw. Separate, because the whole point of recording
	// a timeout is that it gets recorded - writing the row through the context that has just
	// expired would lose the one fact worth keeping.
	book := context.WithoutCancel(ctx)
	runCtx, cancel := context.WithTimeout(book, timeout)
	defer cancel()

	container, refusal := s.target(runCtx, e)
	if refusal != "" {
		s.refuse(book, e, at, refusal)
		return
	}

	if err := s.store.StartCronRun(book, e.cron.ID, e.cron.WorkloadID, at); err != nil {
		// Logged, and the command runs anyway. Not running a customer's job because SQLite
		// would not take a row is the wrong way round: what a failure here costs is the
		// record of the run, and the finish below will fail the same way and say so again.
		s.log.Warn("could not record the start of a scheduled command",
			slog.String("cron", e.cron.ID), slog.String("error", err.Error()))
	}

	// Given no Stdout and no Stderr on purpose: that is what makes runtime capture into its
	// own bounded buffer instead of streaming the output of a job that prints for an hour
	// into this process's memory. The tail of it is what reaches the panel (record.go).
	result, err := s.containers.Run(runCtx, container.ID, runtime.RunOptions{Command: e.cron.Command})
	s.record(book, e, outcomeOf(result, err, timeout))
}

// target finds the container the command runs in, or says why there is not one.
//
// A refusal is a sentence rather than an error, because none of these is a failure of this
// package and all of them are things the customer has to be told in words: their app is
// stopped, their service has not been placed here yet, the engine is not answering.
func (s *Scheduler) target(ctx context.Context, e *entry) (reconcile.Container, string) {
	if !e.present {
		return reconcile.Container{}, fmt.Sprintf("the workload %s is not in this node's "+
			"spec, so there is nothing to run the command in", e.cron.WorkloadID)
	}

	container, found, err := s.containers.ContainerFor(ctx, e.cron.WorkloadID)
	switch {
	case err != nil:
		// Not "the container is gone". The engine could not be asked, which is a fact about
		// this node and not about the customer's workload, and saying the other thing would
		// report every Docker restart as an application that had stopped
		// (AGENTS.md section 4.5).
		return reconcile.Container{}, "the container engine could not be asked where this " +
			"workload is, so the command was not started: " + err.Error()
	case !found:
		return reconcile.Container{}, "this workload has no container on this node, so the " +
			"command was not started"
	case !container.Running:
		return reconcile.Container{}, "this workload's container is not running, so the " +
			"command was not started" + parenthesise(container.Status)
	}
	return container, ""
}

// rescue catches a panic in one execution.
//
// Deferred inside the execution's own goroutine, so the damage stays inside the entry that
// caused it: the loop keeps its tick, every other entry keeps its schedule, and this one gets
// a recorded failure instead of the node getting a stack trace and a restart. Closing the row
// out is the other half of that - a run left marked as in flight by a goroutine that died
// would, for an entry that may not overlap, be an entry that never fires again until the
// daemon is restarted.
func (s *Scheduler) rescue(ctx context.Context, e *entry) {
	reason := recover()
	if reason == nil {
		return
	}
	s.log.Error("the node panicked while running a scheduled command; the schedule carries on",
		slog.String("cron", e.cron.ID), slog.Any("panic", reason),
		slog.String("stack", string(debug.Stack())))
	s.record(context.WithoutCancel(ctx), e, outcome{
		code:   exitNodeFailed,
		detail: fmt.Sprintf("this node failed while running the command: %v", reason),
	})
}

func parenthesise(status string) string {
	if status == "" {
		return ""
	}
	return " (" + status + ")"
}
