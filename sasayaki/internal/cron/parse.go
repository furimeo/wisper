package cron

import (
	"fmt"
	"strconv"
	"strings"
)

// The five fields, parsed to exactly the grammar the panel accepts.
//
// Written here rather than taken from a library, and the reason is the same one that put a
// hand-written parser in the panel: five fields is a morning's work with tests, and a
// dependency is a thing to keep matching a Java class that has to agree with it field for
// field. What it has to agree with is panel/src/main/java/lhqm/furimeo/wisper/service/
// CronSchedule.java - a schedule the panel accepted and this refuses is a customer's job
// that never fires, so this is a deliberate copy of that grammar rather than a better one.
//
//	minute  hour  day-of-month  month  day-of-week
//	  0-59  0-23      1-31       1-12    0-7 (0 and 7 are both Sunday)
//
// Each field is `*`, a number, `a-b`, any of those with `/step`, or a comma-separated list
// of them. Months take JAN-DEC and days take SUN-SAT, because that is what people paste out
// of a crontab. When both day-of-month and day-of-week are restricted a day matching either
// one runs, which is the Vixie rule: it surprises somebody once, and disagreeing with every
// other cron on earth would surprise them for ever.

// set is one field's matching values. Sixty possible values in the widest field, so a single
// word holds any of them and matching is one instruction.
type set uint64

func (s *set) add(value int)     { *s |= 1 << uint(value) }
func (s *set) remove(value int)  { *s &^= 1 << uint(value) }
func (s set) has(value int) bool { return s&(1<<uint(value)) != 0 }

// schedule is a parsed expression. Immutable once built, so one is shared by every firing of
// the entry it came from.
type schedule struct {
	// expression is the normalised text, kept for the log line that says what was refused or
	// what is next: a cron id means nothing to the person reading it.
	expression string

	minutes     set
	hours       set
	daysOfMonth set
	months      set
	daysOfWeek  set

	// Whether each of the two day fields is anything other than `*`. The pair is what decides
	// between "and" and "or" below, and it cannot be recovered from the bit sets: `*` and
	// `0-6` select the same days and mean different things.
	dayOfMonthRestricted bool
	dayOfWeekRestricted  bool
}

// field is one of the five positions: what it is called when it is wrong, what it takes, and
// the names it accepts instead of numbers.
type field struct {
	label string
	min   int
	max   int
	names []string
	// base is the value the first name stands for. Months are one-based from January, days
	// are zero-based from Sunday.
	base int
}

var (
	monthNames = []string{"JAN", "FEB", "MAR", "APR", "MAY", "JUN",
		"JUL", "AUG", "SEP", "OCT", "NOV", "DEC"}
	dayNames = []string{"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"}

	fields = [5]field{
		{label: "minute", min: 0, max: 59},
		{label: "hour", min: 0, max: 23},
		{label: "day of month", min: 1, max: 31},
		{label: "month", min: 1, max: 12, names: monthNames, base: 1},
		{label: "day of week", min: 0, max: 7, names: dayNames, base: 0},
	}
)

// parse reads a five-field expression.
//
// The error names the field and says what it takes, in the same words the panel uses. It
// reaches an operator's journal rather than a customer's screen - the panel refused anything
// this can refuse long before it got here - so it has to be readable by somebody who is
// finding out that the two sides have drifted apart.
func parse(expression string) (schedule, error) {
	parts := strings.Fields(expression)
	if len(parts) != 5 {
		return schedule{}, fmt.Errorf("a schedule has five fields - minute, hour, day of "+
			"month, month, day of week - and %q has %d", expression, len(parts))
	}

	var (
		parsed schedule
		err    error
	)
	if parsed.minutes, err = values(parts[0], fields[0]); err != nil {
		return schedule{}, err
	}
	if parsed.hours, err = values(parts[1], fields[1]); err != nil {
		return schedule{}, err
	}
	if parsed.daysOfMonth, err = values(parts[2], fields[2]); err != nil {
		return schedule{}, err
	}
	if parsed.months, err = values(parts[3], fields[3]); err != nil {
		return schedule{}, err
	}
	if parsed.daysOfWeek, err = values(parts[4], fields[4]); err != nil {
		return schedule{}, err
	}

	// Sunday is both 0 and 7 in every cron anybody has used. Folding them here means the
	// matcher only ever has to know about one of them.
	if parsed.daysOfWeek.has(7) {
		parsed.daysOfWeek.add(0)
		parsed.daysOfWeek.remove(7)
	}

	parsed.expression = strings.Join(parts, " ")
	parsed.dayOfMonthRestricted = parts[2] != "*"
	parsed.dayOfWeekRestricted = parts[4] != "*"
	return parsed, nil
}

// values reads one comma-separated field.
func values(text string, f field) (set, error) {
	var matched set
	for _, part := range strings.Split(text, ",") {
		if err := add(&matched, strings.TrimSpace(part), f); err != nil {
			return 0, err
		}
	}
	return matched, nil
}

// add reads one entry of a list: a star, a number, a range, any of them with a step.
func add(matched *set, part string, f field) error {
	if part == "" {
		return fmt.Errorf("the %s field has an empty entry in its list", f.label)
	}

	step := 1
	bounds := part
	stepped := false
	if slash := strings.Index(part, "/"); slash >= 0 {
		// A step is a plain number: `*/JAN` is not a thing, and the panel does not take it
		// either. A second slash lands in here as text and is refused with everything else.
		size, err := integer(part[slash+1:], 1, f.max, f.label+" step")
		if err != nil {
			return err
		}
		bounds, step, stepped = part[:slash], size, true
	}

	var (
		from, to int
		err      error
	)
	switch {
	case bounds == "*":
		from, to = f.min, f.max
	default:
		// A dash at the very start cannot be a range separator, only a negative number, and
		// every field starts at zero or one - so it is refused by the number parse below
		// rather than here.
		if dash := strings.Index(bounds, "-"); dash > 0 {
			if from, err = value(bounds[:dash], f); err != nil {
				return err
			}
			if to, err = value(bounds[dash+1:], f); err != nil {
				return err
			}
			break
		}
		if from, err = value(bounds, f); err != nil {
			return err
		}
		// `10/5` is "every fifth from ten to the end of the field", the same reading as
		// crontab's. Without a step it is the single value ten.
		to = from
		if stepped {
			to = f.max
		}
	}

	if from > to {
		return fmt.Errorf("the %s range %s counts backwards", f.label, bounds)
	}
	for v := from; v <= to; v += step {
		matched.add(v)
	}
	return nil
}

// value reads one number, or one of the names the field accepts instead.
func value(text string, f field) (int, error) {
	token := strings.ToUpper(strings.TrimSpace(text))
	for index, name := range f.names {
		if token == name {
			return index + f.base, nil
		}
	}
	return integer(token, f.min, f.max, f.label)
}

func integer(text string, min, max int, label string) (int, error) {
	token := strings.TrimSpace(text)
	parsed, err := strconv.Atoi(token)
	if err != nil {
		return 0, fmt.Errorf("%q is not something the %s field understands", token, label)
	}
	if parsed < min || parsed > max {
		return 0, fmt.Errorf("the %s field takes %d to %d, not %d", label, min, max, parsed)
	}
	return parsed, nil
}
