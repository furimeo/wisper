package cron

import (
	"testing"
	"time"
)

// Where the next firing lands, and in particular where it lands on the two nights a year the
// wall clock is not a straight line.
//
// The instants below are written in UTC on purpose. A test that says "03:30" for a schedule
// that asked for 02:30 proves nothing unless it also says which 03:30, and the whole class of
// bug here is a job that ran an hour out.

func zone(t *testing.T, name string) *time.Location {
	t.Helper()
	location, err := time.LoadLocation(name)
	if err != nil {
		// Not a skip. internal/cron links time/tzdata precisely so that this cannot depend on
		// what the machine happens to have in /usr/share/zoneinfo, and a node that silently
		// ran every schedule in UTC is the failure that would hide behind a skip here.
		t.Fatalf("load %s: %v; the embedded zone database should have answered", name, err)
	}
	return location
}

func utc(year int, month time.Month, day, hour, minute int) time.Time {
	return time.Date(year, month, day, hour, minute, 0, 0, time.UTC)
}

func TestNextFiring(t *testing.T) {
	newYork := zone(t, "America/New_York")
	saigon := zone(t, "Asia/Ho_Chi_Minh")

	tests := []struct {
		name       string
		expression string
		location   *time.Location
		after      time.Time
		want       time.Time
	}{
		{
			name: "daily", expression: "0 3 * * *", location: time.UTC,
			after: utc(2026, time.March, 4, 12, 0), want: utc(2026, time.March, 5, 3, 0),
		},
		{
			name: "later the same day", expression: "0 3 * * *", location: time.UTC,
			after: utc(2026, time.March, 4, 1, 15), want: utc(2026, time.March, 4, 3, 0),
		},
		{
			// Strictly after, so the firing that has just happened is not the answer to "what
			// is next" - that is how an entry fires twice in one minute.
			name: "on the minute it fires", expression: "0 3 * * *", location: time.UTC,
			after: utc(2026, time.March, 4, 3, 0), want: utc(2026, time.March, 5, 3, 0),
		},
		{
			name: "every fifteen minutes", expression: "*/15 * * * *", location: time.UTC,
			after: utc(2026, time.March, 4, 12, 0), want: utc(2026, time.March, 4, 12, 15),
		},
		{
			name: "the first of the month", expression: "0 0 1 * *", location: time.UTC,
			after: utc(2026, time.March, 4, 12, 0), want: utc(2026, time.April, 1, 0, 0),
		},
		{
			// 2026-03-04 is a Wednesday, so the next Monday is the ninth.
			name: "mondays", expression: "30 2 * * 1", location: time.UTC,
			after: utc(2026, time.March, 4, 12, 0), want: utc(2026, time.March, 9, 2, 30),
		},
		{
			// Both day fields restricted: the thirteenth or a Friday, not the intersection.
			// 2026-03-06 is a Friday and comes first.
			name: "the vixie or", expression: "0 0 13 * 5", location: time.UTC,
			after: utc(2026, time.March, 4, 12, 0), want: utc(2026, time.March, 6, 0, 0),
		},
		{
			name: "a leap day, two years out", expression: "0 0 29 2 *", location: time.UTC,
			after: utc(2026, time.March, 4, 12, 0), want: utc(2028, time.February, 29, 0, 0),
		},
		{
			// A customer who asked for 3am means their 3am. This one is on the previous day in
			// UTC, which is exactly the mistake a node evaluating in its own zone would make.
			name: "three in the morning, seven hours ahead", expression: "0 3 * * *",
			location: saigon,
			after:    utc(2026, time.March, 4, 12, 0), want: utc(2026, time.March, 4, 20, 0),
		},
		{
			// The clock jumps 02:00 EST to 03:00 EDT, so 02:30 does not happen. The job runs
			// anyway, an hour into the new offset, which is what java.time does and therefore
			// what the panel already told the customer would happen. Note that Go's own
			// time.Date answers 01:30 EST for this, an hour *before* it was asked for.
			name: "a time the spring forward skips", expression: "30 2 * * *", location: newYork,
			after: utc(2024, time.March, 9, 17, 0), want: utc(2024, time.March, 10, 7, 30),
		},
		{
			name: "the day after the spring forward", expression: "30 2 * * *", location: newYork,
			after: utc(2024, time.March, 10, 7, 30), want: utc(2024, time.March, 11, 6, 30),
		},
		{
			// 01:30 happens twice. The first one is the run.
			name: "a time the fall back repeats", expression: "30 1 * * *", location: newYork,
			after: utc(2024, time.November, 2, 16, 0), want: utc(2024, time.November, 3, 5, 30),
		},
		{
			// And the second one is not: a daily backup does not get to run twice because the
			// hour came round again.
			name: "the repeat is not a second firing", expression: "30 1 * * *", location: newYork,
			after: utc(2024, time.November, 3, 5, 30), want: utc(2024, time.November, 4, 6, 30),
		},
		{
			// The same rule seen from an hourly job: 01:00 EDT fires, the hour repeats as
			// 01:00 EST, and the next firing is 02:00 EST.
			name: "hourly across the fall back", expression: "0 * * * *", location: newYork,
			after: utc(2024, time.November, 3, 5, 0), want: utc(2024, time.November, 3, 7, 0),
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			parsed, err := parse(test.expression)
			if err != nil {
				t.Fatalf("parse(%q): %v", test.expression, err)
			}
			got, reachable := parsed.next(test.after, test.location)
			if !reachable {
				t.Fatalf("%q found no next firing after %s", test.expression, test.after)
			}
			if !got.Equal(test.want) {
				t.Errorf("%q after %s = %s, want %s", test.expression, test.after.Format(time.RFC3339),
					got.UTC().Format(time.RFC3339), test.want.Format(time.RFC3339))
			}
		})
	}
}

// An expression that parses and matches no date that will ever happen. The panel's screen
// says "no further run could be worked out from this schedule" for it; the node stores no
// next-run time and never fires it, which is the same statement.
func TestNextFiringSaysWhenThereIsNone(t *testing.T) {
	parsed, err := parse("0 0 30 2 *")
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if got, reachable := parsed.next(noon, time.UTC); reachable {
		t.Errorf("the thirtieth of February resolved to %s", got)
	}
}

// A nil location is UTC rather than a panic. spec.CronEntry.Location never returns one, and
// the day it does the job should run at the wrong hour rather than take the daemon down.
func TestNextFiringWithoutALocation(t *testing.T) {
	parsed, err := parse("0 3 * * *")
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	got, reachable := parsed.next(utc(2026, time.March, 4, 12, 0), nil)
	if !reachable || !got.Equal(utc(2026, time.March, 5, 3, 0)) {
		t.Errorf("next = %s (reachable %v), want 2026-03-05T03:00Z", got, reachable)
	}
}

// The wall clock inside a spring-forward gap, resolved on its own.
//
// Kept separate from the table because it is the one piece of arithmetic in this package that
// is not obvious: Go's time.Date moves a skipped local time backwards west of Greenwich and
// forwards east of it, and neither is what the panel computed.
func TestAResolvesASkippedWallClockForwards(t *testing.T) {
	newYork := zone(t, "America/New_York")

	got := at(2024, time.March, 10, 2, 30, newYork)
	if want := utc(2024, time.March, 10, 7, 30); !got.Equal(want) {
		t.Errorf("02:30 on the night it does not exist = %s, want %s (03:30 EDT)",
			got.UTC().Format(time.RFC3339), want.Format(time.RFC3339))
	}
	if hour := got.In(newYork).Hour(); hour != 3 {
		t.Errorf("local hour = %d, want 3", hour)
	}
}

// An ordinary wall clock is left exactly where it is, in the offset in force at the time.
func TestAKeepsAnOrdinaryWallClock(t *testing.T) {
	newYork := zone(t, "America/New_York")

	winter := at(2024, time.January, 15, 2, 30, newYork)
	if want := utc(2024, time.January, 15, 7, 30); !winter.Equal(want) {
		t.Errorf("winter 02:30 = %s, want %s", winter.UTC(), want)
	}
	summer := at(2024, time.July, 15, 2, 30, newYork)
	if want := utc(2024, time.July, 15, 6, 30); !summer.Equal(want) {
		t.Errorf("summer 02:30 = %s, want %s", summer.UTC(), want)
	}
}
