package daemon

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/rpc"
	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The translation this package exists to get right.
//
// state and rpc each have a sentinel for "that generation is older than the one I have",
// and they are deliberately different values because rpc must not import state. Joining
// them is invisible when it works and invisible when it does not - the acknowledgement says
// accepted: false either way - so the only thing that can catch a missing errors.Is here is
// a test that asserts on it.

func TestApplySpecTranslatesTheStorageError(t *testing.T) {
	diskFull := errors.New("state: store spec at generation 48: disk I/O error")

	cases := []struct {
		name string
		// what the store answers
		refusal error
		// what the control stream must be able to recognise
		superseded bool
		nudged     bool
	}{
		{
			name:       "a spec that is stored is acknowledged and wakes the loop",
			refusal:    nil,
			superseded: false,
			nudged:     true,
		},
		{
			name: "a generation below the applied one is the routine resend, not a failure",
			// Wrapped the way the store wraps it, because that is what reaches this code.
			refusal: fmt.Errorf("state: offered generation 47, stored generation 48: %w",
				state.ErrSupersededGeneration),
			superseded: true,
			nudged:     false,
		},
		{
			name:       "a real storage failure stays a real storage failure",
			refusal:    diskFull,
			superseded: false,
			nudged:     false,
		},
	}

	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			store := &fakeSpecWriter{err: test.refusal}
			loop := &fakeNudger{}
			receiver := storedSpec{store: store, loop: loop, now: func() time.Time { return noon }}

			err := receiver.ApplySpec(context.Background(),
				&wisperpb.NodeSpec{Generation: 47}, "reconnect")

			if got := errors.Is(err, rpc.ErrSpecSuperseded); got != test.superseded {
				t.Errorf("errors.Is(err, rpc.ErrSpecSuperseded) = %t, want %t (error was %v)",
					got, test.superseded, err)
			}
			if test.refusal == nil && err != nil {
				t.Fatalf("a spec that stored cleanly was refused: %v", err)
			}
			if test.refusal != nil && err == nil {
				t.Fatal("a spec the store refused was acknowledged as stored")
			}
			if got := len(loop.reasons) > 0; got != test.nudged {
				t.Errorf("the loop was nudged = %t, want %t", got, test.nudged)
			}
		})
	}
}

// A superseded spec must not be reported as a disk problem, because the difference is what
// an operator does next. The message is what ends up in SpecApplied.rejected_reason and in
// the panel's UI, so it has to name the generation rather than only the condition.
func TestApplySpecNamesTheSupersededGeneration(t *testing.T) {
	store := &fakeSpecWriter{err: fmt.Errorf("state: offered generation 47, stored generation 48: %w",
		state.ErrSupersededGeneration)}
	receiver := storedSpec{store: store, loop: &fakeNudger{}, now: func() time.Time { return noon }}

	err := receiver.ApplySpec(context.Background(), &wisperpb.NodeSpec{Generation: 47}, "reconnect")
	if err == nil {
		t.Fatal("a superseded spec was acknowledged as stored")
	}
	if !strings.Contains(err.Error(), "47") {
		t.Errorf("the refusal does not say which generation was refused: %q", err)
	}
}

// The nudge carries why, because the loop's log line is where a real change and the resend
// that follows every dropped stream otherwise look identical.
func TestApplySpecNudgesWithAReason(t *testing.T) {
	loop := &fakeNudger{}
	receiver := storedSpec{store: &fakeSpecWriter{}, loop: loop, now: func() time.Time { return noon }}

	if err := receiver.ApplySpec(context.Background(),
		&wisperpb.NodeSpec{Generation: 91}, "deployment 412"); err != nil {
		t.Fatalf("apply the spec: %v", err)
	}

	if len(loop.reasons) != 1 {
		t.Fatalf("the loop was nudged %d times, want once", len(loop.reasons))
	}
	for _, want := range []string{"91", "deployment 412"} {
		if !strings.Contains(loop.reasons[0], want) {
			t.Errorf("the nudge %q does not mention %q", loop.reasons[0], want)
		}
	}
}
