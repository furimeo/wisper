package rpc

import (
	"context"
	"math"
	"math/rand/v2"
	"time"
)

// Backoff is how long to wait before dialling the panel again.
//
// Three properties matter, and all three come from the same fact: the panel is behind a
// tunnel, so a dropped stream is routine rather than exceptional (design section 5.2).
//
//  1. It never gives up. There is no attempt limit anywhere in this package. A node
//     that stopped trying would keep serving traffic and become invisible, which is the
//     one failure mode nobody notices until a customer reports it.
//  2. It is jittered. Fifty nodes lose a tunnel at the same instant and, without
//     jitter, come back at the same instant - and knock the panel over as it starts.
//  3. It has a floor. A panel that accepts a connection and closes it immediately must
//     not turn into a tight loop; every delay is at least half its window.
type Backoff struct {
	// Base is the first window, before any doubling.
	Base time.Duration

	// Max is the ceiling. Reached in about six failures with the defaults, which is a
	// minute of outage - long enough not to hammer, short enough that a panel coming
	// back is noticed quickly.
	Max time.Duration

	// Factor multiplies the window after each failure.
	Factor float64

	// Jitter picks the delay inside a window. Nil means equal jitter: half the window
	// plus a random part of the other half. Tests replace it to make a schedule
	// predictable; nothing in production does.
	Jitter func(window time.Duration) time.Duration
}

// DefaultBackoff is what the daemon uses.
func DefaultBackoff() Backoff {
	return Backoff{Base: time.Second, Max: time.Minute, Factor: 2}
}

func (b Backoff) withDefaults() Backoff {
	if b.Base <= 0 {
		b.Base = time.Second
	}
	if b.Max <= 0 {
		b.Max = time.Minute
	}
	if b.Max < b.Base {
		b.Max = b.Base
	}
	if b.Factor < 1 {
		b.Factor = 2
	}
	if b.Jitter == nil {
		b.Jitter = equalJitter
	}
	return b
}

// equalJitter returns a delay in [window/2, window). Half the window is fixed so the
// delay can never collapse towards zero, and half is random so a fleet reconnecting
// together spreads out.
func equalJitter(window time.Duration) time.Duration {
	half := window / 2
	if half <= 0 {
		return window
	}
	return half + time.Duration(rand.Int64N(int64(half)))
}

// retryClock is one caller's place in the schedule. Separate from the policy because
// four supervised streams share one policy and each keeps its own count.
type retryClock struct {
	policy   Backoff
	attempts int
	// atCeiling short-circuits the doubling for a failure that will not fix itself in a
	// second: a protocol version the panel refuses, a credential it does not recognise.
	// Retrying is still right - the panel may be mid-upgrade or restoring a backup - but
	// starting that retry schedule at one second is just noise in somebody's log.
	atCeiling bool
}

func newRetryClock(policy Backoff) *retryClock {
	return &retryClock{policy: policy.withDefaults()}
}

// reset is called after a session that actually worked, so the next outage starts from
// the bottom of the schedule again.
func (r *retryClock) reset() {
	r.attempts = 0
	r.atCeiling = false
}

// penalise moves straight to the ceiling.
func (r *retryClock) penalise() {
	r.atCeiling = true
}

// next advances the schedule and returns how long to wait.
func (r *retryClock) next() time.Duration {
	window := r.policy.Max
	if !r.atCeiling {
		window = r.window()
		r.attempts++
	}
	return r.policy.Jitter(window)
}

func (r *retryClock) window() time.Duration {
	grown := float64(r.policy.Base) * math.Pow(r.policy.Factor, float64(r.attempts))
	if grown >= float64(r.policy.Max) || math.IsInf(grown, 1) {
		return r.policy.Max
	}
	return time.Duration(grown)
}

// wait sleeps unless the daemon is shutting down, in which case it reports that
// immediately instead of holding the process open for another minute.
func wait(ctx context.Context, delay time.Duration) error {
	if delay <= 0 {
		return ctx.Err()
	}
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}
