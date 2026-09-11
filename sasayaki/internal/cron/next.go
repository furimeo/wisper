package cron

import "time"

// When a schedule fires next, in the customer's own timezone.
//
// The zone is the whole difficulty. A customer who asked for 3am means their 3am, so the
// expression is matched against wall-clock fields in their zone rather than against an
// instant - and twice a year that wall clock does something instants do not. This file makes
// the same two choices the panel's CronSchedule makes, because the two have to name the same
// moment:
//
//   - A time that the clock skips over still runs. On the night 02:30 does not exist, a job
//     scheduled for 02:30 fires at 03:30 rather than being silently dropped for a year.
//   - A time the clock passes twice runs once, on the first pass. A daily backup does not
//     get to run twice because the hour repeated.
//
// The search is bounded at four years, which is long enough to reach a 29 February and short
// enough to answer "never" for `0 0 30 2 *` - an expression that parses perfectly and matches
// no date that will ever happen.
const searchDays = 366 * 4

// next is the first moment strictly after `after` that this schedule fires, evaluated in loc.
//
// The second return is false for an expression that matches nothing reachable. The caller
// stores a zero next-run time for it and never fires it, which is the truth: there is no such
// date, and a schedule that quietly fired on some nearby day instead would be worse than one
// that says it has no next run.
func (s schedule) next(after time.Time, loc *time.Location) (time.Time, bool) {
	if loc == nil {
		loc = time.UTC
	}

	// The scan starts at the minute after the one `after` is in, so a schedule that has just
	// fired does not immediately match its own firing again. Candidates carry no seconds, so
	// "at or after the next whole minute" and "strictly after `after`" select the same set.
	start := after.In(loc).Add(time.Minute)

	// Days are walked on a UTC anchor rather than in loc: adding a day to a local time is not
	// always adding 24 hours, and this loop only wants the next date on the calendar.
	year, month, day := start.Date()
	cursor := time.Date(year, month, day, 12, 0, 0, 0, time.UTC)

	for offset := range searchDays {
		year, month, day = cursor.Date()
		if s.matches(month, day, cursor.Weekday()) {
			firstHour := 0
			if offset == 0 {
				firstHour = start.Hour()
			}
			for hour := firstHour; hour < 24; hour++ {
				if !s.hours.has(hour) {
					continue
				}
				firstMinute := 0
				if offset == 0 && hour == start.Hour() {
					firstMinute = start.Minute()
				}
				for minute := firstMinute; minute < 60; minute++ {
					if !s.minutes.has(minute) {
						continue
					}
					if candidate := at(year, month, day, hour, minute, loc); candidate.After(after) {
						return candidate, true
					}
				}
			}
		}
		cursor = cursor.AddDate(0, 0, 1)
	}
	return time.Time{}, false
}

// matches is whether the date half of the expression selects this day.
//
// When both day fields are restricted a day matching either one runs - the Vixie rule, which
// is why `0 0 13 * 5` is the thirteenth and every Friday rather than only Friday the
// thirteenth.
func (s schedule) matches(month time.Month, day int, weekday time.Weekday) bool {
	if !s.months.has(int(month)) {
		return false
	}
	// time.Weekday is Sunday=0..Saturday=6, which is cron's own numbering once 7 has been
	// folded onto 0 by the parser.
	byMonth := s.daysOfMonth.has(day)
	byWeek := s.daysOfWeek.has(int(weekday))
	if s.dayOfMonthRestricted && s.dayOfWeekRestricted {
		return byMonth || byWeek
	}
	return byMonth && byWeek
}

// at turns a wall-clock date and time in loc into the instant it happens at.
//
// time.Date is not enough on its own. For a wall clock the zone skips - the hour a spring
// forward removes - it answers with an instant whose local time is not the one it was asked
// for, and which direction it lands in depends on whether the zone is east or west of
// Greenwich. That would silently move a 02:30 job to 01:30 on one continent and 03:30 on
// another, once a year, which is exactly the kind of bug nobody reproduces.
//
// So a skipped wall clock is resolved the way java.time resolves it, and therefore the way
// the panel's own next_run_at was computed: the local time moves forward by the length of the
// gap, which is the same instant as reading the requested wall clock with the offset that was
// in force just before the jump.
func at(year int, month time.Month, day, hour, minute int, loc *time.Location) time.Time {
	moment := time.Date(year, month, day, hour, minute, 0, 0, loc)
	if moment.Day() == day && moment.Hour() == hour && moment.Minute() == minute {
		return moment
	}

	// Every value handed in is a real calendar date and a real time of day, so the only way
	// the round trip can disagree is a zone that has no such local time.
	naive := time.Date(year, month, day, hour, minute, 0, 0, time.UTC)
	// A day either side of a transition is far enough to read the offset that was in force
	// before it, and near enough that no zone on earth has another transition in between.
	_, before := naive.Add(-24 * time.Hour).In(loc).Zone()
	return naive.Add(-time.Duration(before) * time.Second).In(loc)
}
