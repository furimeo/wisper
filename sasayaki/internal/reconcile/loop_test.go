package reconcile

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	systemd "github.com/coreos/go-systemd/v22/daemon"
)

// The scheduling half: that the loop turns on its own, that it can be woken early, that it
// tells the service manager it is alive, and that it slows down when something is broken.
//
// These are the only tests in the package that use a real timer, so they use a very short
// interval and wait on a condition rather than on a duration.

// waitFor polls until condition holds, and fails with what it was waiting for.
func waitFor(t *testing.T, what string, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

func (h *harness) batchCount() int {
	h.panel.mu.Lock()
	defer h.panel.mu.Unlock()
	return len(h.panel.batches)
}

func (h *harness) notifications() []string {
	h.mu.Lock()
	defer h.mu.Unlock()
	return append([]string(nil), h.notified...)
}

// runLoop starts Run and returns a function that stops it and reports what it returned.
func runLoop(t *testing.T, h *harness) func() error {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- h.loop.Run(ctx) }()

	stopped := false
	stop := func() error {
		if stopped {
			return nil
		}
		stopped = true
		cancel()
		select {
		case err := <-done:
			return err
		case <-time.After(5 * time.Second):
			t.Fatal("Run did not return after its context was cancelled")
			return nil
		}
	}
	t.Cleanup(func() { stop() })
	return stop
}

func TestTheLoopConvergesOnItsOwnWithNothingHappening(t *testing.T) {
	// The periodicity is the mechanism, not a fallback for missed events. A container
	// somebody stopped by hand comes back because of this and nothing else.
	h := newHarness(t)
	h.loop.interval = 2 * time.Millisecond
	unscheduled := appSpec(7)
	unscheduled.ReconcileIntervalSeconds = 0 // so the spec does not overwrite the test's interval
	h.publish(t, unscheduled)

	stop := runLoop(t, h)
	waitFor(t, "three passes with no events at all", func() bool { return h.batchCount() >= 3 })

	if err := stop(); !errors.Is(err, context.Canceled) {
		t.Fatalf("Run returned %v, want the cancellation that stopped it", err)
	}
}

func TestReconcileNowWakesTheLoopEarly(t *testing.T) {
	h := newHarness(t)
	h.loop.interval = time.Hour
	unscheduled := appSpec(7)
	unscheduled.ReconcileIntervalSeconds = 0
	h.publish(t, unscheduled)

	runLoop(t, h)
	waitFor(t, "the pass every daemon does at startup", func() bool { return h.batchCount() == 1 })

	h.loop.ReconcileNow("the panel published a new spec")
	waitFor(t, "the early pass the nudge asked for", func() bool { return h.batchCount() >= 2 })
}

func TestASecondNudgeDuringAPassDoesNotQueueASecondPass(t *testing.T) {
	// A nudge is an optimisation. Losing one is harmless; running the same spec five times
	// because the panel reconnected five times is not.
	h := newHarness(t)
	for range 5 {
		h.loop.ReconcileNow("a reconnect")
	}
	if len(h.loop.nudge) != 1 {
		t.Fatalf("%d nudges are queued, want them collapsed into one", len(h.loop.nudge))
	}
}

func TestTheServiceManagerIsToldThisNodeIsAliveAndReady(t *testing.T) {
	h := newHarness(t)
	h.loop.interval = 2 * time.Millisecond
	h.loop.watchdog.interval = 2 * time.Millisecond
	unscheduled := appSpec(7)
	unscheduled.ReconcileIntervalSeconds = 0
	h.publish(t, unscheduled)

	runLoop(t, h)
	waitFor(t, "the watchdog to be fed", func() bool {
		return count(h.notifications(), systemd.SdNotifyWatchdog) >= 2
	})
	waitFor(t, "the node to report itself ready", func() bool {
		return count(h.notifications(), systemd.SdNotifyReady) == 1
	})

	// Ready is said once. A unit that keeps announcing its own start-up is a unit whose
	// start-up nobody can time.
	if got := count(h.notifications(), systemd.SdNotifyReady); got != 1 {
		t.Fatalf("the node announced itself ready %d times", got)
	}
}

func TestAHungPassStopsTheDaemonClaimingToBeHealthy(t *testing.T) {
	// A slow pass and a wedged one look identical from outside. The difference is how long,
	// and past that point being restarted beats being trusted.
	fed := 0
	w := watchdog{
		interval:   time.Second,
		stallAfter: time.Minute,
		notify:     func(string) error { fed++; return nil },
	}

	w.feed(discardLogger(), time.Time{}, noon)
	w.feed(discardLogger(), noon.Add(-30*time.Second), noon)
	if fed != 2 {
		t.Fatalf("the watchdog was fed %d times while the loop was working normally", fed)
	}

	w.feed(discardLogger(), noon.Add(-2*time.Minute), noon)
	if fed != 2 {
		t.Fatal("a pass that has been running for twice the stall limit was still reported as healthy")
	}
}

func TestADisabledWatchdogNotifiesNothing(t *testing.T) {
	fed := 0
	w := watchdog{interval: 0, stallAfter: time.Minute, notify: func(string) error { fed++; return nil }}

	w.feed(discardLogger(), time.Time{}, noon)
	if fed != 0 {
		t.Fatal("a daemon running outside systemd tried to notify a service manager")
	}
	if tick, stop := w.ticker(); tick != nil {
		stop()
		t.Fatal("a disabled watchdog produced a ticker")
	}
}

func TestFailingPassesSlowDownAndSuccessSpeedsBackUp(t *testing.T) {
	h := newHarness(t)
	h.loop.interval = 10 * time.Second

	if delay := h.loop.nextDelay(nil); delay != 10*time.Second {
		t.Fatalf("a successful pass waits %s, want the interval", delay)
	}

	first := h.loop.nextDelay(errEngineDown)
	second := h.loop.nextDelay(errEngineDown)
	if second <= first {
		t.Fatalf("two failures in a row waited %s then %s; the second should be longer", first, second)
	}

	for range 20 {
		if delay := h.loop.nextDelay(errEngineDown); delay > maxBackoff {
			t.Fatalf("the backoff reached %s, past the %s ceiling", delay, maxBackoff)
		}
	}

	if delay := h.loop.nextDelay(nil); delay != 10*time.Second {
		t.Fatalf("a pass that worked still waits %s, want the schedule reset", delay)
	}
}

func TestALoopWithAMissingCollaboratorIsRefusedAtConstruction(t *testing.T) {
	// A command the panel can send and nobody answers is a stub with extra steps; a
	// collaborator the loop calls every fifteen seconds and nobody provides is worse.
	_, err := New(Options{Runtime: newFakeRuntime()})
	if err == nil {
		t.Fatal("a loop was built with five of its six collaborators missing")
	}
	for _, name := range []string{"Sites", "Edge", "Store", "Databases", "Reporter"} {
		if !strings.Contains(err.Error(), name) {
			t.Fatalf("the error %q does not name the missing %s", err, name)
		}
	}
}
