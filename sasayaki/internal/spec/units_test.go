package spec

import (
	"testing"
	"time"

	"google.golang.org/protobuf/types/known/timestamppb"
)

// The whole point of this file is that a duration is never a thousand or a billion times
// too long, and that failure shows up far away from the conversion that caused it.
func TestSecondsAreSeconds(t *testing.T) {
	cases := map[int64]time.Duration{
		0:     0,
		15:    15 * time.Second,
		900:   15 * time.Minute,
		86400: 24 * time.Hour,
		-1:    -time.Second,
	}
	for wire, want := range cases {
		if got := seconds(wire); got != want {
			t.Errorf("seconds(%d) = %s, want %s", wire, got, want)
		}
	}
}

func TestInstantReadsATimestamp(t *testing.T) {
	want := time.Date(2026, 9, 11, 10, 15, 30, 123456000, time.UTC)

	if got := instant(timestamppb.New(want)); !got.Equal(want) {
		t.Errorf("instant() = %s, want %s", got, want)
	}
}

// Nil and the epoch both mean "unset". The panel writes Instant.EPOCH where it has nothing
// to send, and reading that as a real moment would have the clock-skew check report
// fifty-six years of drift on a healthy node.
func TestUnsetInstantsAreTheZeroTime(t *testing.T) {
	if got := instant(nil); !got.IsZero() {
		t.Errorf("instant(nil) = %s, want the zero time", got)
	}
	if got := instant(&timestamppb.Timestamp{}); !got.IsZero() {
		t.Errorf("instant(epoch) = %s, want the zero time", got)
	}
}

// timestamppb.New(time.Time{}) is the year 1, and the panel would store, display and
// compare that date. Nil is the only honest encoding of "there is no such moment".
func TestWireInstantWritesNilForTheZeroTime(t *testing.T) {
	if got := wireInstant(time.Time{}); got != nil {
		t.Errorf("wireInstant(zero) = %v, want nil", got)
	}
}

func TestWireInstantRoundTrips(t *testing.T) {
	want := time.Date(2026, 12, 1, 0, 0, 0, 0, time.UTC)

	if got := instant(wireInstant(want)); !got.Equal(want) {
		t.Errorf("%s round-tripped to %s", want, got)
	}
}
