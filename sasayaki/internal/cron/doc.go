// Package cron runs the commands a customer scheduled, inside the customer's own container.
//
// The schedule is evaluated here rather than on the panel, and that is the whole reason the
// package exists: a job that has to run at three in the morning cannot have a tunnel in its
// path (docs/contracts/node-spec.md section 3.9). The panel validates the expression, stores
// it and computes a next_run_at for the screen; none of that fires anything. This does.
//
// One firing, in order:
//
//	the loop wakes, re-reads the spec when a re-read is due, and finds what is due with it
//	an entry whose previous run has not finished and may not overlap is recorded as skipped
//	anything else is claimed, started on its own goroutine, and written down as running
//	the command runs inside the workload's container, under the entry's own timeout
//	how it ended - exit status, timeout, refusal - is written down and the schedule advances
//
// Four properties are worth stating out loud, because each one is a way a scheduler usually
// fails:
//
//   - Nothing is replayed. A node that was off for a day comes back, computes each entry's
//     next due time from now, and fires it once when that arrives. Waking up into a hundred
//     backlogged executions turns a node that was merely offline into one that is overloaded,
//     and the customer never asked for last night's report twice.
//   - A non-zero exit is a result, not a failure of this package. It is recorded with the
//     tail of what the command printed, and the schedule carries on. A cron that stops
//     scheduling because the script exited 1 is a cron that stops the day it is needed.
//   - No single job can stop the loop. A panic inside an execution is caught, recorded
//     against the entry it came from, and the next tick happens as though nothing had.
//   - Output is bounded. runtime.Run captures into a fixed buffer when it is handed no
//     writers, and what reaches the panel is the tail of that buffer. A job printing a
//     progress bar for an hour must not be able to exhaust the node it runs on.
//
// The grammar this package accepts is exactly the grammar the panel's CronSchedule accepts,
// no more and no less (parse.go). A schedule the panel takes and the node cannot read is a
// job that never runs and nobody is told, which is the class of failure wisper exists to
// remove; when it happens anyway - a panel upgraded ahead of its nodes - the node says so
// against the entry rather than staying quiet.
package cron
