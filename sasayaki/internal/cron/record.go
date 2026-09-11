package cron

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"strconv"
	"time"
	"unicode/utf8"

	"github.com/furimeo/wisper/sasayaki/internal/runtime"
	"github.com/furimeo/wisper/sasayaki/internal/state"
)

// How a firing is written down, which is the only way anybody ever finds out about it.
//
// The node is the only place a cron's history can live - the schedule is evaluated here, so
// nothing else saw it happen - and internal/state keeps one row per entry rather than one per
// execution. What that row has to answer is the question on the customer's screen: did last
// night's job run, and if it did not, why not.

const (
	// exitTimedOut is what a run killed for passing its deadline is recorded as.
	//
	// A timeout is not a non-zero exit, and the difference matters to whoever is looking: a
	// script that exits 1 is broken, and a script that never finishes is usually waiting on
	// something. 124 is what timeout(1) has returned for exactly this for twenty years, so
	// the number reads correctly to anybody who has met it, and the detail says it in words
	// as well.
	exitTimedOut int32 = 124

	// exitNodeFailed is what a firing with no exit status of its own is recorded as: no
	// container to run in, an engine that would not answer, a schedule this node could not
	// read, a panic in this package. 125 is what `docker run` itself returns when the command
	// never started, which is the same statement.
	//
	// A customer's command can of course exit 124 or 125 by itself. The number is a filter,
	// not a proof; the sentence beside it is what says which of the two happened.
	exitNodeFailed int32 = 125

	// maxDetail is how much of a command's output travels with the status.
	//
	// runtime.Run already caps what it captures at a megabyte, so this is not the bound that
	// protects the node's memory - it is the one that keeps a status batch, which goes out
	// every fifteen seconds for as long as the failure lasts, from carrying a megabyte of a
	// stack trace each time. A kilobyte of the end of the output is what a person needs.
	maxDetail = 1024
)

// outcome is how one execution ended, in the two values the row keeps.
type outcome struct {
	code int32
	// detail is empty for a run that worked. Anything else is a sentence a customer reads on
	// the task's row in the panel, so it is written as one.
	detail string
}

// outcomeOf reads the result of one command.
//
// A non-zero exit is a result and not an error: the customer's script failing is information
// the panel shows, and the schedule carries on to the next firing exactly as it would have.
func outcomeOf(result runtime.RunResult, err error, timeout time.Duration) outcome {
	switch {
	case result.TimedOut:
		return outcome{
			code:   exitTimedOut,
			detail: "timed out after " + timeout.String() + " and was killed" + printed(result),
		}
	case err != nil:
		return outcome{code: exitNodeFailed, detail: "this node could not run the command: " + err.Error()}
	case result.ExitCode != 0:
		return outcome{
			code:   int32(result.ExitCode),
			detail: "exited " + strconv.Itoa(result.ExitCode) + printed(result),
		}
	}
	// A run that worked says nothing. The panel shows last_error verbatim, and "OK" on a row
	// that already says the run succeeded is noise in the one place a real message has to
	// stand out.
	return outcome{}
}

// refuse records a firing that could not be started, and why.
//
// It starts the run and finishes it immediately, rather than calling SkipCronRun, and the
// reason is what the panel does with the two. RecordCronStatus replaces last_error with
// "Skipped: the previous run had not finished." for anything carrying the skipped flag, and
// shows the node's own words only for a run that both started and ended. That flag is honest
// for exactly one case - an overlap this entry did not allow, which dispatch records with it
// - and none of the refusals here is that case. A customer whose nightly job did not run
// needs the sentence saying their application is stopped, not a sentence about a previous run
// that never existed.
func (s *Scheduler) refuse(ctx context.Context, e *entry, at time.Time, reason string) {
	s.log.Warn("a scheduled command could not be started", slog.String("cron", e.cron.ID),
		slog.String("workload", e.cron.WorkloadID), slog.String("reason", reason))

	if err := s.store.StartCronRun(ctx, e.cron.ID, e.cron.WorkloadID, at); err != nil {
		s.log.Warn("could not record a scheduled command that did not start",
			slog.String("cron", e.cron.ID), slog.String("error", err.Error()))
		return
	}
	s.record(ctx, e, outcome{code: exitNodeFailed, detail: reason})
}

// record closes out one firing.
func (s *Scheduler) record(ctx context.Context, e *entry, ended outcome) {
	err := s.store.FinishCronRun(ctx, e.cron.ID, ended.code, s.now(), e.note(ended.detail))
	switch {
	case errors.Is(err, state.ErrNotFound):
		// The customer deleted the entry while its command was running, and the history has
		// already been pruned. Not a failure, and not something to keep a row for: the panel
		// would have nothing to attach it to.
		s.log.Info("a scheduled command finished after its entry had been deleted",
			slog.String("cron", e.cron.ID), slog.Int("exit_code", int(ended.code)))
		return
	case err != nil:
		s.log.Warn("could not record how a scheduled command ended",
			slog.String("cron", e.cron.ID), slog.String("error", err.Error()))
		return
	}

	if ended.code == 0 {
		// Debug, because a job on `*/5` is nearly three hundred of these a day and the node's
		// journal has a disk to share with the customer's data.
		s.log.Debug("a scheduled command ran", slog.String("cron", e.cron.ID),
			slog.String("workload", e.cron.WorkloadID))
		return
	}
	s.log.Warn("a scheduled command did not succeed", slog.String("cron", e.cron.ID),
		slog.String("workload", e.cron.WorkloadID), slog.Int("exit_code", int(ended.code)),
		slog.String("detail", ended.detail))
}

// printed is the tail of what the command wrote, ready to be appended to a sentence.
//
// The tail rather than the head: a stack trace, a `set -x` transcript and a compiler all put
// the thing that went wrong at the end. Standard error first and standard output only when
// there was no standard error, because a script that prints its own failure to stdout is
// common enough to be worth carrying.
func printed(result runtime.RunResult) string {
	output := bytes.TrimSpace(result.Stderr)
	if len(output) == 0 {
		output = bytes.TrimSpace(result.Stdout)
	}
	if len(output) == 0 {
		if result.Truncated {
			return ": it printed more than this node keeps, and none of the end of it survived"
		}
		return ""
	}

	dropped := result.Truncated
	if len(output) > maxDetail {
		output = output[len(output)-maxDetail:]
		// Cutting a fixed number of bytes off the front of UTF-8 lands mid-character about
		// two times in three for a non-ASCII log, and a replacement glyph at the start of
		// every error message is a small permanent ugliness.
		for len(output) > 0 && !utf8.RuneStart(output[0]) {
			output = output[1:]
		}
		dropped = true
	}
	if dropped {
		return ": [earlier output dropped] " + string(output)
	}
	return ": " + string(output)
}
