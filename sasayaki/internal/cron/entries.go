package cron

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"strconv"
	"time"

	// The zone database, compiled into the binary.
	//
	// sasayaki is one static file copied onto a machine, and time.LoadLocation would
	// otherwise answer from /usr/share/zoneinfo - which a minimal host may not have, and
	// which nobody would think to install because nothing else on the node needs it. Without
	// this, every customer who asked for 03:00 Asia/Ho_Chi_Minh would silently get 03:00 UTC:
	// the job runs, at the wrong hour, for ever. The embedded copy is only consulted when the
	// host has no database of its own, so a node with current tzdata keeps using it.
	_ "time/tzdata"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/state"
)

// Following the spec: which entries exist, what they are next due at, and what to forget.
//
// Entries are re-read rather than cached for ever, because the customer edits them: a
// schedule changed at noon has to take effect this afternoon, and a task deleted at noon must
// not fire tonight. What is deliberately *not* re-derived is the next-run time of an entry
// whose schedule has not changed. The panel resends the whole spec on every reconnect, and a
// node on a flapping tunnel that recomputed on every generation would keep pushing the next
// firing of a `*/5` job a few seconds further away and never quite reach it.

// entry is one scheduled command as the loop holds it between firings.
type entry struct {
	cron     spec.CronEntry
	schedule schedule
	location *time.Location

	// zoneNote is set when the entry names a timezone this node cannot resolve. It is
	// attached to everything the entry records, because the run happens in UTC and a customer
	// whose 3am job is running at 10am local needs to be told why - spec.CronEntry.Location
	// falls back rather than refusing precisely so that somebody can say so (spec/cron.go).
	zoneNote string

	// present is whether the spec that carried this entry also carried its workload. An entry
	// naming a workload that is not on this node is reported rather than run.
	present bool

	// next is when this entry fires next, by the node's own clock. Zero means never: an
	// expression this node could not read, or one that matches no date that will happen.
	next time.Time
}

// note prefixes a detail with the timezone warning, when there is one.
func (e *entry) note(detail string) string {
	switch {
	case e.zoneNote == "":
		return detail
	case detail == "":
		return e.zoneNote
	}
	return e.zoneNote + "; " + detail
}

// reload reads the spec and makes the loop's entries match it.
func (s *Scheduler) reload(ctx context.Context, now time.Time) {
	stored, err := s.store.LoadSpec(ctx)
	switch {
	case errors.Is(err, state.ErrNoSpec):
		// A freshly enrolled node. Nothing is scheduled, and nothing is pruned either: there
		// is no spec to be the authority on what should be forgotten.
		return
	case err != nil:
		// Keep firing what the last readable spec asked for. A damaged or unreadable spec is
		// the panel's to resend; dropping the schedule over it would stop a customer's jobs
		// for as long as the tunnel is down, which is the failure this whole design avoids.
		s.log.Warn("could not read the spec, so the schedule is unchanged for now",
			slog.String("error", err.Error()))
		return
	}
	s.apply(ctx, spec.FromProto(stored.Spec), now)
}

// apply reconciles the entries in memory with the ones in the spec.
func (s *Scheduler) apply(ctx context.Context, desired spec.Spec, now time.Time) {
	entries := make(map[string]*entry, len(desired.Cron))
	order := make([]string, 0, len(desired.Cron))

	for _, candidate := range desired.Cron {
		if candidate.ID == "" {
			// Nothing can be recorded against it, and a run whose result nobody can see is
			// worse than one that does not happen.
			s.log.Warn("the spec carries a cron entry with no id, so it cannot be run or "+
				"reported", slog.String("workload", candidate.WorkloadID),
				slog.String("schedule", candidate.Schedule))
			continue
		}
		_, present := desired.Workload(candidate.WorkloadID)

		if existing, known := s.entries[candidate.ID]; known && unchanged(existing.cron, candidate) {
			// Same schedule, possibly a new command or timeout. Keep the next-run time.
			existing.cron = candidate
			existing.present = present
			entries[candidate.ID] = existing
			order = append(order, candidate.ID)
			continue
		}

		entries[candidate.ID] = s.build(ctx, candidate, present, now)
		order = append(order, candidate.ID)
	}

	s.entries, s.order = entries, order
	s.prune(ctx, order)
}

// unchanged is whether an entry still fires at the same moments it did.
//
// Only the two fields the next-run time is derived from. A command or a timeout that changed
// takes effect on the next firing without moving it.
func unchanged(held, offered spec.CronEntry) bool {
	return held.Schedule == offered.Schedule && held.Timezone == offered.Timezone
}

// build turns a spec entry into one the loop can fire, and writes down when that will be.
func (s *Scheduler) build(ctx context.Context, candidate spec.CronEntry, present bool, now time.Time) *entry {
	e := &entry{cron: candidate, present: present}

	location, resolved := candidate.Location()
	e.location = location
	if !resolved {
		e.zoneNote = fmt.Sprintf("the timezone %s is not one this node has, so this schedule "+
			"is being run in UTC", strconv.Quote(candidate.Timezone))
		s.log.Warn("a scheduled command names a timezone this node cannot resolve; it will "+
			"run in UTC", slog.String("cron", candidate.ID),
			slog.String("timezone", candidate.Timezone))
	}

	parsed, err := parse(candidate.Schedule)
	if err != nil {
		// The panel refuses everything this refuses, so arriving here means the two have
		// drifted apart - a panel upgraded ahead of its nodes is the likely way. The entry
		// will not fire, and the one thing that must not happen is silence: it is recorded
		// against the entry so it reaches the screen the customer created it on.
		s.log.Error("a scheduled command has a schedule this node cannot read, so it will "+
			"not run", slog.String("cron", candidate.ID),
			slog.String("schedule", candidate.Schedule), slog.String("error", err.Error()))
		s.reschedule(ctx, e)
		s.refuse(ctx, e, now, "this node could not read the schedule "+
			strconv.Quote(candidate.Schedule)+": "+err.Error())
		return e
	}

	e.schedule = parsed
	s.advance(ctx, e, now)
	return e
}

// advance moves an entry on to its next firing and records it.
//
// From `after` rather than from the firing that has just happened, which is what stops a
// machine that was off for a day coming back to a day of catching up: every missed occurrence
// collapses into the next one that is still ahead, and each entry runs at most once when the
// node returns (design section 7.6).
func (s *Scheduler) advance(ctx context.Context, e *entry, after time.Time) {
	next, reachable := e.schedule.next(after, e.location)
	if !reachable {
		// It parsed and it matches no date that will ever come - the thirtieth of February.
		// Said once, here, rather than discovered from a job that has never run.
		s.log.Warn("a scheduled command has a schedule that matches no date, so it will never "+
			"run", slog.String("cron", e.cron.ID), slog.String("schedule", e.cron.Schedule))
	}
	e.next = next
	s.reschedule(ctx, e)
}

// reschedule writes the next-run time down, so a restarted daemon reports it without waiting
// for the schedule to be worked out again.
func (s *Scheduler) reschedule(ctx context.Context, e *entry) {
	if err := s.store.ScheduleCronRun(ctx, e.cron.ID, e.cron.WorkloadID, e.next); err != nil {
		s.log.Warn("could not record when a scheduled command is next due",
			slog.String("cron", e.cron.ID), slog.String("error", err.Error()))
	}
}

// prune forgets the history of entries that have left the spec.
//
// Only when the set has actually changed. The panel resends the same spec every time a stream
// reconnects, and a DELETE every fifteen seconds for the rest of the node's life is a write
// nobody asked for.
func (s *Scheduler) prune(ctx context.Context, keep []string) {
	if slices.Equal(keep, s.scheduled) {
		return
	}
	removed, err := s.store.PruneCronRuns(ctx, keep)
	if err != nil {
		s.log.Warn("could not forget the history of deleted cron entries",
			slog.String("error", err.Error()))
		return
	}
	s.scheduled = slices.Clone(keep)
	if removed > 0 {
		s.log.Info("forgot the history of cron entries that are no longer in the spec",
			slog.Int64("entries", removed))
	}
}
