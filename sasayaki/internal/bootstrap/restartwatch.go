package bootstrap

import (
	"context"
	"fmt"
	"time"
)

// How long a restarted daemon gets, and how long it then has to stay up.
//
// The settle window is the part that matters. A daemon that starts, opens SQLite, fails to
// parse its credential and exits is `active` for about two hundred milliseconds, and with
// Restart=always it will be `activating` again a moment later - so a check that asks once
// and stops reports a broken node as healthy. Twelve seconds is longer than the two-second
// RestartSec plus a reconcile pass, which means a daemon that is going to crash on startup
// has crashed at least once inside the window.
const (
	restartTimeout     = 90 * time.Second
	restartSettle      = 12 * time.Second
	restartPollInitial = 250 * time.Millisecond
	restartPollSettle  = 1500 * time.Millisecond
)

// restartWatch answers one question: is the unit up and staying up?
//
// It is the difference between an upgrade that reports success because a file was
// replaced and one that reports success because the node came back (design section 7.5).
type restartWatch struct {
	systemd systemd
	unit    string
	timeout time.Duration
	settle  time.Duration

	// now and sleep are the clock. A test drives them so it can assert on a crash loop
	// without waiting out a real settle window.
	now   func() time.Time
	sleep func(ctx context.Context, d time.Duration) error
}

func newRestartWatch(sd systemd, unit string) restartWatch {
	return restartWatch{
		systemd: sd,
		unit:    unit,
		timeout: restartTimeout,
		settle:  restartSettle,
		now:     time.Now,
		sleep:   sleepContext,
	}
}

// healthy waits for the unit to become active and then holds it to that for the settle
// window. It returns the reason it is unhappy, never a bare boolean: the caller puts that
// reason in front of an operator or into an UpgradeResult, and "unhealthy" on its own
// tells nobody anything.
func (w restartWatch) healthy(ctx context.Context) error {
	deadline := w.now().Add(w.timeout)

	for {
		state := w.systemd.state(ctx, w.unit)
		switch state {
		case unitActive:
			return w.holdsUp(ctx)

		case unitFailed:
			// systemd has given up on it. Waiting longer changes nothing, and every second
			// spent here is a second before the rollback.
			return fmt.Errorf("%s failed to start", w.unit)

		case unitAbsent:
			return fmt.Errorf("systemd does not know about %s", w.unit)
		}

		if !w.now().Before(deadline) {
			return fmt.Errorf("%s was still %s after %s", w.unit, state, w.timeout)
		}
		if err := w.sleep(ctx, restartPollInitial); err != nil {
			return err
		}
	}
}

// holdsUp is the settle window: the unit is active now, and has to still be active for
// long enough that a daemon dying on startup has had time to do it.
func (w restartWatch) holdsUp(ctx context.Context) error {
	until := w.now().Add(w.settle)
	for w.now().Before(until) {
		if err := w.sleep(ctx, restartPollSettle); err != nil {
			return err
		}
		switch state := w.systemd.state(ctx, w.unit); state {
		case unitActive:
			continue
		case unitFailed:
			return fmt.Errorf("%s started and then failed", w.unit)
		default:
			return fmt.Errorf("%s started and went to %s within %s of coming up",
				w.unit, state, w.settle)
		}
	}
	return nil
}

// sleepContext waits, or gives up early when the operator pressed Ctrl-C. A bare
// time.Sleep here would make a ninety-second timeout uninterruptible.
func sleepContext(ctx context.Context, d time.Duration) error {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}
