package bootstrap

import (
	"context"
	"testing"
)

// The settle window is the difference between "the file was replaced" and "the node came
// back". A daemon that starts, fails to read its credential and exits is `active` for
// about two hundred milliseconds, so a check that asks once reports a broken node as
// healthy.
func TestAUnitThatStartsAndDiesIsNotHealthy(t *testing.T) {
	init := workingInit()
	init.states = []string{unitActive, unitActive, unitFailed}
	clock := newClock()

	err := clock.watch(init.seam()).healthy(context.Background())

	if err == nil {
		t.Fatal("a unit that died inside the settle window was reported healthy")
	}
	mustContain(t, err.Error(), "started and then failed")
}

func TestAUnitThatStaysUpIsHealthy(t *testing.T) {
	init := workingInit()
	clock := newClock()

	if err := clock.watch(init.seam()).healthy(context.Background()); err != nil {
		t.Fatalf("a unit that stayed active was reported unhealthy: %v", err)
	}
}

// Waiting for a unit that is starting slowly is right; waiting forever is not.
func TestAUnitThatNeverComesUpTimesOut(t *testing.T) {
	init := workingInit()
	init.states = []string{unitActivating}
	clock := newClock()

	err := clock.watch(init.seam()).healthy(context.Background())

	if err == nil {
		t.Fatal("a unit stuck in activating was reported healthy")
	}
	mustContain(t, err.Error(), "activating")
}

// systemd giving up is not something to keep waiting on: every second spent here is a
// second before the rollback.
func TestAFailedUnitIsReportedImmediately(t *testing.T) {
	init := workingInit()
	init.states = []string{unitFailed}
	clock := newClock()
	before := clock.now()

	err := clock.watch(init.seam()).healthy(context.Background())

	if err == nil {
		t.Fatal("a failed unit was reported healthy")
	}
	if clock.now() != before {
		t.Fatalf("the watch waited %s before believing a failed unit", clock.now().Sub(before))
	}
}

// An operator pressing Ctrl-C during a ninety-second wait has to be able to stop it.
func TestTheWatchStopsWhenTheContextIsCancelled(t *testing.T) {
	init := workingInit()
	init.states = []string{unitActivating}
	clock := newClock()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	watch := clock.watch(init.seam())
	// The real sleep, so cancellation is what ends this rather than a fake clock.
	watch.sleep = sleepContext

	if err := watch.healthy(ctx); err == nil {
		t.Fatal("a cancelled watch kept waiting")
	}
}

// is-active exits 3 for an inactive unit and 4 for one that does not exist. Treating a
// non-zero exit as an error would turn the answer into a failure.
func TestTheUnitStateIsReadFromTheOutputAndNotTheExitCode(t *testing.T) {
	init := workingInit()
	init.states = []string{unitInactive}

	if state := init.seam().state(context.Background(), unitName); state != unitInactive {
		t.Fatalf("state is %q, want inactive", state)
	}

	// systemctl prints a warning line above the answer when a unit file changed on disk.
	noisy := workingInit()
	noisy.states = []string{"Warning: the unit file changed on disk.\nactive"}
	if state := noisy.seam().state(context.Background(), unitName); state != unitActive {
		t.Fatalf("state is %q, want active", state)
	}
}
