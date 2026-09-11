package cron

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The loop: what it fires, when it stops firing it, and what a restart does to all of that.
//
// Past three hundred lines and deliberately not split further: every test below drives the
// same two methods, and cutting them into "scheduling" and "following the spec" files would
// separate assertions that only mean anything next to each other - what a re-read does to an
// entry that is about to fire is the point of half of them.

func TestTheSchedulerRefusesToBeBuiltWithoutItsCollaborators(t *testing.T) {
	if _, err := New(Options{}); err == nil {
		t.Fatal("a scheduler with no engine and no store was built; it would accept every " +
			"entry in the spec and run none of them")
	}
	if _, err := New(Options{Containers: newFakeContainers()}); err == nil {
		t.Fatal("a scheduler with nowhere to record a run was built")
	}
}

func TestRunStopsWhenTheContextDoes(t *testing.T) {
	r := newRig(t)
	ctx, cancel := context.WithCancel(t.Context())

	finished := make(chan error, 1)
	go func() { finished <- r.scheduler.Run(ctx) }()

	cancel()
	select {
	case err := <-finished:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("Run returned %v, want context.Canceled", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Run did not return after its context was cancelled")
	}
}

// The whole path, through the loop's own timer rather than through a test calling one pass:
// Run reads the spec, works out when the entry is due, sleeps, and fires it when the clock
// gets there.
func TestRunFiresWhatIsDue(t *testing.T) {
	r := newRig(t)
	r.publish(t, task("cron-1", "* * * * *"))

	scheduler, err := New(Options{
		Containers: r.containers,
		Store:      r.store,
		Logger:     discardLogs(),
		Now:        r.clock.Now,
		// Fast enough that the test does not wait on a fifteen-second re-read. Nothing else
		// about the loop changes: it still sleeps until the earlier of the two things it has
		// to do next.
		Refresh: 5 * time.Millisecond,
	})
	if err != nil {
		t.Fatalf("build the scheduler: %v", err)
	}

	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	finished := make(chan error, 1)
	go func() { finished <- scheduler.Run(ctx) }()

	due := r.waitForSchedule(t, "cron-1")
	r.clock.set(due)
	r.containers.waitForRun(t)

	cancel()
	select {
	case <-finished:
	case <-time.After(5 * time.Second):
		t.Fatal("Run did not return after its context was cancelled")
	}
}

func TestAnEntryFiresWhenItIsDueAndNotBefore(t *testing.T) {
	r := newRig(t)
	r.publish(t, task("cron-1", "*/15 * * * *"))

	r.tick(t)
	if ran := r.containers.ran(); len(ran) != 0 {
		t.Fatalf("%d execution(s) at %s, want none: nothing is due yet", len(ran), noon)
	}
	quarterPast := noon.Add(15 * time.Minute)
	if run := r.history(t, "cron-1"); !run.NextRunAt.Equal(quarterPast) {
		t.Fatalf("next run = %s, want %s", run.NextRunAt, quarterPast)
	}

	r.tickAt(t, noon.Add(14*time.Minute))
	if ran := r.containers.ran(); len(ran) != 0 {
		t.Fatalf("%d execution(s) a minute early", len(ran))
	}

	r.tickAt(t, quarterPast)
	ran := r.containers.ran()
	if len(ran) != 1 {
		t.Fatalf("%d execution(s) at the due minute, want 1", len(ran))
	}
	if ran[0].containerID != "container-"+workloadID {
		t.Errorf("the command ran in %q", ran[0].containerID)
	}
	if len(ran[0].command) == 0 || ran[0].command[0] != "/usr/bin/php" {
		t.Errorf("the command was %v, want the entry's argv", ran[0].command)
	}

	run := r.history(t, "cron-1")
	if !run.LastRunAt.Equal(quarterPast) || run.Running || run.LastExitCode != 0 {
		t.Errorf("run = %+v, want a finished, successful run at %s", run, quarterPast)
	}
	if want := noon.Add(30 * time.Minute); !run.NextRunAt.Equal(want) {
		t.Errorf("next run = %s, want %s", run.NextRunAt, want)
	}

	// A second wake-up inside the same minute must not fire it again.
	r.tickAt(t, quarterPast.Add(30*time.Second))
	if ran := r.containers.ran(); len(ran) != 1 {
		t.Fatalf("%d execution(s) after a second wake-up in the same minute, want 1", len(ran))
	}
}

// The test this loop exists to pass twice over: a node that was off overnight comes back and
// runs each entry once, at the next time it is due, rather than working through everything it
// missed.
func TestARestartDoesNotReplayTheFiringsItMissed(t *testing.T) {
	r := newRig(t)
	r.publish(t, task("cron-1", "0 3 * * *"))
	r.tick(t)

	firstDue := time.Date(2026, time.March, 5, 3, 0, 0, 0, time.UTC)
	if run := r.history(t, "cron-1"); !run.NextRunAt.Equal(firstDue) {
		t.Fatalf("next run = %s, want %s", run.NextRunAt, firstDue)
	}

	// The machine is off from noon on the fourth to noon on the sixth. Two firings are missed,
	// and the row on disk still says the entry was due at 03:00 on the fifth.
	r.clock.set(noon.Add(48 * time.Hour))
	restarted := r.restart(t)
	restarted.tick(t)

	if ran := restarted.containers.ran(); len(ran) != 0 {
		t.Fatalf("%d execution(s) on the way back up; a node that was merely offline must not "+
			"come back as a node that is overloaded", len(ran))
	}
	nextDue := time.Date(2026, time.March, 7, 3, 0, 0, 0, time.UTC)
	run := restarted.history(t, "cron-1")
	if !run.NextRunAt.Equal(nextDue) {
		t.Fatalf("next run = %s, want %s: the stored time was in the past and had to be "+
			"recomputed from now", run.NextRunAt, nextDue)
	}
	if !run.LastRunAt.IsZero() {
		t.Errorf("last run = %s, want nothing: it has never run", run.LastRunAt)
	}

	restarted.tickAt(t, nextDue)
	if ran := restarted.containers.ran(); len(ran) != 1 {
		t.Fatalf("%d execution(s) at the next due time, want exactly 1", len(ran))
	}
}

// Entries appear, change and disappear as generations arrive. A scheduler holding the first
// spec it ever saw would keep firing a task the customer deleted last week.
func TestEntriesFollowTheSpec(t *testing.T) {
	r := newRig(t)
	r.publish(t, task("cron-1", "0 3 * * *"), task("cron-2", "0 4 * * *"))
	r.tick(t)

	if runs, err := r.store.CronRuns(t.Context()); err != nil || len(runs) != 2 {
		t.Fatalf("the store holds %d row(s) (%v), want 2", len(runs), err)
	}

	// The customer edits the first and deletes the second. The change is picked up by the
	// next re-read rather than by anything pushing it in, which is what keeps the schedule
	// working while the panel is unreachable.
	r.publish(t, task("cron-1", "30 5 * * *"))
	r.tickAt(t, noon.Add(DefaultRefresh))

	run := r.history(t, "cron-1")
	if want := time.Date(2026, time.March, 5, 5, 30, 0, 0, time.UTC); !run.NextRunAt.Equal(want) {
		t.Errorf("next run = %s, want %s: the new schedule takes effect without a restart",
			run.NextRunAt, want)
	}
	runs, err := r.store.CronRuns(t.Context())
	if err != nil {
		t.Fatalf("read the history: %v", err)
	}
	if len(runs) != 1 || runs[0].CronID != "cron-1" {
		t.Errorf("the store holds %+v, want only cron-1: a deleted entry has to stop being "+
			"reported", runs)
	}
}

// An entry whose schedule has not changed keeps the next-run time it already had. The panel
// resends the whole spec on every reconnect, and a node on a flapping tunnel that recomputed
// each time would keep pushing a `*/5` job a few seconds further away and never reach it.
func TestAResentSpecDoesNotMoveAFiring(t *testing.T) {
	r := newRig(t)
	r.publish(t, task("cron-1", "*/15 * * * *"))
	r.tick(t)

	due := r.history(t, "cron-1").NextRunAt

	r.clock.set(noon.Add(5 * time.Minute))
	r.publish(t, task("cron-1", "*/15 * * * *"))
	r.tick(t)

	if again := r.history(t, "cron-1").NextRunAt; !again.Equal(due) {
		t.Errorf("next run moved from %s to %s when the same spec arrived again", due, again)
	}
}

// A node that has never been given a spec has nothing to run, and nothing to forget either.
func TestANodeWithNoSpecSchedulesNothing(t *testing.T) {
	r := newRig(t)

	r.tick(t)

	if runs, err := r.store.CronRuns(t.Context()); err != nil || len(runs) != 0 {
		t.Fatalf("the store holds %d row(s) (%v), want none", len(runs), err)
	}
	if ran := r.containers.ran(); len(ran) != 0 {
		t.Fatalf("%d execution(s) with no spec", len(ran))
	}
}

// The panel refuses every expression this node refuses, so the two only disagree when they
// have drifted apart - a panel upgraded ahead of its nodes. The one thing that must not
// happen then is silence: a job that never runs and nobody is told is the failure this whole
// project exists to remove.
func TestAScheduleTheNodeCannotReadIsReportedRatherThanIgnored(t *testing.T) {
	r := newRig(t)
	r.publish(t, task("cron-1", "0 3 * * 8"))

	r.tick(t)
	r.tickAt(t, noon.Add(24*time.Hour))

	if ran := r.containers.ran(); len(ran) != 0 {
		t.Fatalf("%d execution(s) from a schedule that would not parse", len(ran))
	}
	run := r.history(t, "cron-1")
	if run.LastExitCode != exitNodeFailed {
		t.Errorf("exit code = %d, want %d", run.LastExitCode, exitNodeFailed)
	}
	if !strings.Contains(run.LastError, "could not read the schedule") {
		t.Errorf("detail = %q, want it to say the schedule could not be read", run.LastError)
	}
	if !run.NextRunAt.IsZero() {
		t.Errorf("next run = %s, want nothing: it has no next run", run.NextRunAt)
	}
}

// A zone this node cannot resolve is a job that runs at the wrong hour rather than one that
// does not run - and the customer has to be told, because nothing else on the screen would
// explain why their 3am job happens in the middle of their afternoon.
func TestAnUnknownTimezoneRunsInUtcAndSaysSo(t *testing.T) {
	r := newRig(t)
	entry := task("cron-1", "0 3 * * *")
	entry.Timezone = "Mars/Olympus_Mons"
	r.publish(t, entry)

	r.tick(t)

	due := time.Date(2026, time.March, 5, 3, 0, 0, 0, time.UTC)
	if run := r.history(t, "cron-1"); !run.NextRunAt.Equal(due) {
		t.Fatalf("next run = %s, want %s in UTC", run.NextRunAt, due)
	}

	r.tickAt(t, due)
	run := r.history(t, "cron-1")
	if run.LastExitCode != 0 {
		t.Errorf("exit code = %d, want 0: the run itself was fine", run.LastExitCode)
	}
	if !strings.Contains(run.LastError, "Mars/Olympus_Mons") ||
		!strings.Contains(run.LastError, "UTC") {
		t.Errorf("detail = %q, want it to name the zone and say the schedule ran in UTC",
			run.LastError)
	}
}

// An entry with no id cannot be recorded, and a run whose result nobody can see is worse than
// one that does not happen. It must also not stop the entries around it being scheduled.
func TestAnEntryWithNoIdIsRefusedWithoutTakingTheOthersWithIt(t *testing.T) {
	r := newRig(t)
	nameless := task("", "* * * * *")
	r.publish(t, nameless, task("cron-2", "0 3 * * *"))

	r.tick(t)
	r.tickAt(t, noon.Add(time.Minute))

	if ran := r.containers.ran(); len(ran) != 0 {
		t.Fatalf("%d execution(s), want none: only the entry with no id was due", len(ran))
	}
	runs, err := r.store.CronRuns(t.Context())
	if err != nil {
		t.Fatalf("read the history: %v", err)
	}
	if len(runs) != 1 || runs[0].CronID != "cron-2" {
		t.Errorf("the store holds %+v, want only cron-2", runs)
	}
}

// The loop sleeps until the earlier of the next firing and the next re-read, so a job due on
// the minute lands on the minute rather than up to a reconcile interval late.
func TestTheLoopSleepsUntilTheNextThingItHasToDo(t *testing.T) {
	r := newRig(t)
	r.publish(t, task("cron-1", "* * * * *"))
	r.tick(t)

	// The next firing is under a minute away and the next re-read is fifteen seconds away, so
	// the re-read wins.
	if delay := r.scheduler.sleep(r.clock.Now()); delay != DefaultRefresh {
		t.Errorf("sleep = %s, want %s", delay, DefaultRefresh)
	}

	// Five seconds before the firing, the firing wins.
	fiveBefore := noon.Add(55 * time.Second)
	r.scheduler.refreshedAt = fiveBefore
	if delay := r.scheduler.sleep(fiveBefore); delay != 5*time.Second {
		t.Errorf("sleep = %s, want 5s", delay)
	}

	// And it never returns zero, whatever the arithmetic says.
	if delay := r.scheduler.sleep(noon.Add(time.Hour)); delay != minSleep {
		t.Errorf("sleep = %s, want the floor of %s", delay, minSleep)
	}
}

// A spec with no cron entries at all is a legitimate instruction - a drained node is sent one
// - and it means "forget them", not "keep the last set you saw".
func TestAnEmptySpecClearsTheSchedule(t *testing.T) {
	r := newRig(t)
	r.publish(t, task("cron-1", "* * * * *"))
	r.tick(t)

	r.publishWith(t, []*wisperpb.Workload{{Id: workloadID, Name: "app"}})
	r.tickAt(t, noon.Add(time.Minute))

	if ran := r.containers.ran(); len(ran) != 0 {
		t.Fatalf("%d execution(s) from an entry that is no longer in the spec", len(ran))
	}
	if runs, err := r.store.CronRuns(t.Context()); err != nil || len(runs) != 0 {
		t.Fatalf("the store holds %d row(s) (%v), want none", len(runs), err)
	}
}
