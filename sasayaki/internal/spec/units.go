package spec

import (
	"time"

	"google.golang.org/protobuf/types/known/timestamppb"
)

// The unit boundary between the wire and Go, and the only place either conversion is
// written.
//
// protobuf counts whole seconds in an int64 and carries instants as a *timestamppb.
// Timestamp that may be nil; Go has time.Duration, which counts nanoseconds, and time.Time,
// which has a zero value. Every mistake this file exists to prevent is the same mistake -
// a duration that is a thousand or a billion times too long, or an unset timestamp read as
// a real moment in 1970 - and every one of them shows up somewhere far away from the
// conversion that caused it.

// seconds turns a wire duration into a Duration.
//
// A negative value is kept rather than clamped: the wire uses -1 for "unlimited" in
// ResourceLimits.memory_swap_bytes and the same convention could reach a duration. Deciding
// what a negative interval means belongs to whoever reads it, not here.
func seconds(value int64) time.Duration {
	return time.Duration(value) * time.Second
}

// There is no Duration-to-seconds counterpart, and that is not an omission: durations
// travel only downwards. The panel sends intervals and timeouts in the spec; the node sends
// instants back in its statuses and never a duration. A helper for a direction nothing uses
// is a helper nobody has checked.

// instant reads a wire timestamp.
//
// Nil is the zero time, and so is the epoch itself: the panel writes Instant.EPOCH where it
// has nothing to send (BuildNodeSpec.timestampOf), and reading that as a real moment would
// have the clock-skew check report fifty-six years of drift on a perfectly healthy node.
// Nothing in this system happens in 1970, so the ambiguity costs nothing.
func instant(value *timestamppb.Timestamp) time.Time {
	if value == nil {
		return time.Time{}
	}
	if value.GetSeconds() == 0 && value.GetNanos() == 0 {
		return time.Time{}
	}
	return value.AsTime()
}

// wireInstant writes a timestamp, or nil for the zero time.
//
// Nil rather than a marshalled zero, because timestamppb.New(time.Time{}) is the year 1 and
// arrives at the panel as a date it will store, display and compare.
func wireInstant(value time.Time) *timestamppb.Timestamp {
	if value.IsZero() {
		return nil
	}
	return timestamppb.New(value)
}
