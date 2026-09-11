package cron

import (
	"strings"
	"testing"
)

// The grammar has one job: agree with the panel's CronSchedule exactly.
//
// Both directions of disagreement are failures, and only one of them is loud. An expression
// this refuses and the panel accepts is a customer's job that never fires and nobody notices
// for a month; an expression this accepts and the panel refuses is one that could never have
// reached a node in the first place. So the two tables below are written from
// CronSchedule.java rather than from what a cron parser usually does.

func setOf(values ...int) set {
	var s set
	for _, value := range values {
		s.add(value)
	}
	return s
}

func spanOf(from, to, step int) set {
	var s set
	for value := from; value <= to; value += step {
		s.add(value)
	}
	return s
}

func TestParseAcceptsExactlyWhatThePanelAccepts(t *testing.T) {
	every := struct {
		minute, hour, day, month, weekday set
	}{
		spanOf(0, 59, 1), spanOf(0, 23, 1), spanOf(1, 31, 1), spanOf(1, 12, 1), spanOf(0, 6, 1),
	}

	tests := []struct {
		name       string
		expression string
		minutes    set
		hours      set
		days       set
		months     set
		weekdays   set
	}{
		{
			name: "every minute", expression: "* * * * *",
			minutes: every.minute, hours: every.hour, days: every.day,
			months: every.month, weekdays: every.weekday,
		},
		{
			name: "a step over a star", expression: "*/15 * * * *",
			minutes: setOf(0, 15, 30, 45), hours: every.hour, days: every.day,
			months: every.month, weekdays: every.weekday,
		},
		{
			name: "a list, a range and a plain number", expression: "0,30 9-17 * * 1-5",
			minutes: setOf(0, 30), hours: spanOf(9, 17, 1), days: every.day,
			months: every.month, weekdays: setOf(1, 2, 3, 4, 5),
		},
		{
			// A bare number with a step runs from that number to the end of the field, which
			// is what crontab does and what the panel copied.
			name: "a number with a step", expression: "10/15 * * * *",
			minutes: setOf(10, 25, 40, 55), hours: every.hour, days: every.day,
			months: every.month, weekdays: every.weekday,
		},
		{
			name: "a stepped range", expression: "0 0-12/6 * * *",
			minutes: setOf(0), hours: setOf(0, 6, 12), days: every.day,
			months: every.month, weekdays: every.weekday,
		},
		{
			// People paste these out of a crontab, so the panel takes them and so does this.
			name: "names, in either case", expression: "0 3 * jan-MAR mon,Fri",
			minutes: setOf(0), hours: setOf(3), days: every.day,
			months: setOf(1, 2, 3), weekdays: setOf(1, 5),
		},
		{
			// Sunday is 0 and 7 in every cron anybody has used; the parser folds them.
			name: "sunday as seven", expression: "0 0 * * 7",
			minutes: setOf(0), hours: setOf(0), days: every.day,
			months: every.month, weekdays: setOf(0),
		},
		{
			name: "a range that ends on sunday-as-seven", expression: "0 0 * * 5-7",
			minutes: setOf(0), hours: setOf(0), days: every.day,
			months: every.month, weekdays: setOf(0, 5, 6),
		},
		{
			name: "the day of the month", expression: "0 4 1 * *",
			minutes: setOf(0), hours: setOf(4), days: setOf(1),
			months: every.month, weekdays: every.weekday,
		},
		{
			// The panel normalises runs of whitespace before it stores the expression; a node
			// that choked on one that slipped through would be a job that never fires.
			name: "extra whitespace", expression: "  0   3 * * *  ",
			minutes: setOf(0), hours: setOf(3), days: every.day,
			months: every.month, weekdays: every.weekday,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			parsed, err := parse(test.expression)
			if err != nil {
				t.Fatalf("parse(%q): %v", test.expression, err)
			}
			for _, field := range []struct {
				label     string
				got, want set
			}{
				{"minutes", parsed.minutes, test.minutes},
				{"hours", parsed.hours, test.hours},
				{"days of month", parsed.daysOfMonth, test.days},
				{"months", parsed.months, test.months},
				{"days of week", parsed.daysOfWeek, test.weekdays},
			} {
				if field.got != field.want {
					t.Errorf("%s = %b, want %b", field.label, field.got, field.want)
				}
			}
		})
	}
}

func TestParseRefusesWhatThePanelRefuses(t *testing.T) {
	tests := []struct {
		name       string
		expression string
		// says is a fragment the message has to contain, so the operator reading the journal
		// learns which field is wrong rather than that "the schedule" is.
		says string
	}{
		{"nothing at all", "", "five fields"},
		{"four fields", "* * * *", "five fields"},
		{"six fields", "* * * * * *", "five fields"},
		{"a minute past the end", "60 * * * *", "minute field takes 0 to 59"},
		{"an hour past the end", "* 24 * * *", "hour field takes 0 to 23"},
		{"the zeroth of the month", "0 0 0 * *", "day of month field takes 1 to 31"},
		{"a thirteenth month", "0 0 * 13 *", "month field takes 1 to 12"},
		{"an eighth day of the week", "0 0 * * 8", "day of week field takes 0 to 7"},
		{"a range that counts backwards", "5-1 * * * *", "counts backwards"},
		{"a named range that counts backwards", "0 0 * * MON-SUN", "counts backwards"},
		{"a step of zero", "*/0 * * * *", "minute step field takes 1 to 59"},
		{"a step past the end of the field", "*/60 * * * *", "minute step field takes 1 to 59"},
		{"an empty entry in a list", "0,,5 * * * *", "empty entry"},
		{"a trailing comma", "0,5, * * * *", "empty entry"},
		{"a name the field does not know", "0 0 * FOO *", "not something the month field"},
		{"a name where a number belongs", "0 0 * * MON/TUE", "not something the day of week step"},
		{"two steps", "*/2/3 * * * *", "not something the minute step field"},
		{"a negative number", "-5 * * * *", "minute field takes 0 to 59, not -5"},
		{"a step with no bounds", "/5 * * * *", "not something the minute field"},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			parsed, err := parse(test.expression)
			if err == nil {
				t.Fatalf("parse(%q) was accepted as %+v; the panel refuses it, so a node that "+
					"takes it is a node scheduling something nobody agreed to", test.expression, parsed)
			}
			if !strings.Contains(err.Error(), test.says) {
				t.Errorf("parse(%q) said %q, which does not contain %q", test.expression, err, test.says)
			}
		})
	}
}

// The two day fields mean "and" until both are restricted, and then they mean "or". It cannot
// be read back off the bit sets - `*` and `0-6` select the same days - so the flags are what
// carry it.
func TestParseRemembersWhichDayFieldsAreRestricted(t *testing.T) {
	tests := []struct {
		expression      string
		byMonth, byWeek bool
	}{
		{"0 0 * * *", false, false},
		{"0 0 13 * *", true, false},
		{"0 0 * * 5", false, true},
		{"0 0 13 * 5", true, true},
		{"0 0 */2 * *", true, false},
	}

	for _, test := range tests {
		parsed, err := parse(test.expression)
		if err != nil {
			t.Fatalf("parse(%q): %v", test.expression, err)
		}
		if parsed.dayOfMonthRestricted != test.byMonth || parsed.dayOfWeekRestricted != test.byWeek {
			t.Errorf("%q: restricted by month = %v, by week = %v; want %v and %v",
				test.expression, parsed.dayOfMonthRestricted, parsed.dayOfWeekRestricted,
				test.byMonth, test.byWeek)
		}
	}
}

// The expression is kept for the log, normalised the way the panel normalises it, so the two
// sides quote the same string back at a person comparing them.
func TestParseKeepsTheNormalisedExpression(t *testing.T) {
	parsed, err := parse("  0   3  *  *  * ")
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if parsed.expression != "0 3 * * *" {
		t.Errorf("expression = %q, want %q", parsed.expression, "0 3 * * *")
	}
}
