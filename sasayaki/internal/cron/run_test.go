package cron

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/runtime"
)

// What happens to one firing, which is the half of this package a customer actually sees.
//
// Past three hundred lines and left whole: it is one feature - how a firing ends - and each
// case here is one of the ways it can, which is exactly the list that has to be readable in
// one place to see that none of them is missing.

// waitForRun blocks until an execution has started, so a test never has to sleep and hope.
func (f *fakeContainers) waitForRun(t *testing.T) {
	t.Helper()
	select {
	case <-f.begun:
	case <-time.After(5 * time.Second):
		t.Fatal("no execution started")
	}
}

// A script that exits 1 is information, not a reason to stop scheduling. A cron that gave up
// on the first failure would give up on the night it mattered.
func TestANonZeroExitIsRecordedAndTheScheduleCarriesOn(t *testing.T) {
	r := newRig(t)
	r.containers.respond = func(context.Context, string) (runtime.RunResult, error) {
		return runtime.RunResult{ExitCode: 2, Stderr: []byte("PHP Fatal error: table missing\n")}, nil
	}
	r.publish(t, task("cron-1", "* * * * *"))

	r.tick(t)
	r.tickAt(t, noon.Add(time.Minute))

	run := r.history(t, "cron-1")
	if run.LastExitCode != 2 {
		t.Errorf("exit code = %d, want 2", run.LastExitCode)
	}
	if !strings.Contains(run.LastError, "exited 2: PHP Fatal error: table missing") {
		t.Errorf("detail = %q, want the exit status and the tail of what it printed", run.LastError)
	}
	if run.Running || run.LastSkipped {
		t.Errorf("run = %+v, want finished and not skipped", run)
	}
	if want := noon.Add(2 * time.Minute); !run.NextRunAt.Equal(want) {
		t.Errorf("next run = %s, want %s: a failure must not stop the schedule", run.NextRunAt, want)
	}

	// And it does fire again, which is the half of "the schedule carries on" that a
	// next-run-time assertion does not prove.
	r.tickAt(t, noon.Add(2*time.Minute))
	if ran := r.containers.ran(); len(ran) != 2 {
		t.Fatalf("%d execution(s) after two due minutes, want 2", len(ran))
	}
}

// A run killed for passing its deadline is not the same thing as a script that exited
// non-zero, and the row has to be able to tell them apart.
func TestATimeoutIsRecordedAsATimeout(t *testing.T) {
	r := newRig(t)
	r.containers.respond = func(context.Context, string) (runtime.RunResult, error) {
		// What runtime.Run answers when the deadline passed and it had to kill the process.
		return runtime.RunResult{TimedOut: true, ExitCode: 137, Stdout: []byte("importing…")}, nil
	}
	entry := task("cron-1", "* * * * *")
	entry.TimeoutSeconds = 30
	r.publish(t, entry)

	r.tick(t)
	started := time.Now()
	r.tickAt(t, noon.Add(time.Minute))

	run := r.history(t, "cron-1")
	if run.LastExitCode != exitTimedOut {
		t.Errorf("exit code = %d, want %d: a timeout that reads as an ordinary failure sends "+
			"somebody looking for a bug in a script that is merely slow", run.LastExitCode, exitTimedOut)
	}
	if !strings.Contains(run.LastError, "timed out after 30s") {
		t.Errorf("detail = %q, want it to say the run timed out and after how long", run.LastError)
	}

	// The deadline is the entry's, and it is real: a run with no deadline is the two hundred
	// stuck copies the timeout exists to prevent.
	ran := r.containers.ran()
	if len(ran) != 1 {
		t.Fatalf("%d execution(s), want 1", len(ran))
	}
	if ran[0].deadline.IsZero() {
		t.Fatal("the command was given a context with no deadline")
	}
	if given := ran[0].deadline.Sub(started); given < 29*time.Second || given > 31*time.Second {
		t.Errorf("the command was given %s to finish, want about 30s", given)
	}
}

// Overlap is opt-in because the common case - a backup script, an importer - corrupts itself
// when two copies run at once.
func TestAFiringIsSkippedWhileThePreviousRunIsStillGoing(t *testing.T) {
	r := newRig(t)
	release := make(chan struct{})
	r.containers.respond = func(ctx context.Context, _ string) (runtime.RunResult, error) {
		<-release
		return runtime.RunResult{}, nil
	}
	entry := task("cron-1", "* * * * *")
	entry.AllowOverlap = false
	r.publish(t, entry)

	r.tick(t)
	r.start(t, noon.Add(time.Minute))
	r.containers.waitForRun(t)

	r.start(t, noon.Add(2*time.Minute))
	if ran := r.containers.ran(); len(ran) != 1 {
		t.Fatalf("%d execution(s), want 1: the second firing had to be refused", len(ran))
	}

	skipped := r.history(t, "cron-1")
	if !skipped.LastSkipped {
		t.Error("the skipped firing was not recorded; a schedule that looks like it has " +
			"stopped firing has to be able to say why")
	}
	if !skipped.Running {
		t.Error("the run in flight was forgotten while recording the skip")
	}

	close(release)
	r.settle(t)

	finished := r.history(t, "cron-1")
	if finished.Running || finished.LastExitCode != 0 {
		t.Errorf("after the first run finished: %+v, want a clean finish", finished)
	}
}

// The customer who asked for overlap gets it. Two copies of the same entry is a strange thing
// to want and a legitimate one - a queue worker being topped up, for instance.
func TestOverlapHappensWhenTheEntryAsksForIt(t *testing.T) {
	r := newRig(t)
	release := make(chan struct{})
	r.containers.respond = func(context.Context, string) (runtime.RunResult, error) {
		<-release
		return runtime.RunResult{}, nil
	}
	entry := task("cron-1", "* * * * *")
	entry.AllowOverlap = true
	r.publish(t, entry)

	r.tick(t)
	r.start(t, noon.Add(time.Minute))
	r.containers.waitForRun(t)
	r.start(t, noon.Add(2*time.Minute))
	r.containers.waitForRun(t)

	if ran := r.containers.ran(); len(ran) != 2 {
		t.Fatalf("%d execution(s), want 2", len(ran))
	}
	if run := r.history(t, "cron-1"); run.LastSkipped {
		t.Error("a run was recorded as skipped by an entry that allows overlap")
	}

	close(release)
	r.settle(t)
}

// Four ways there is nothing to run the command in. None of them is an error, none of them
// takes the loop down, and each one has to say which it was: "your application is stopped"
// and "this node cannot reach Docker" are completely different sentences to be shown.
func TestAFiringWithNowhereToRunIsRecordedWithItsReason(t *testing.T) {
	tests := []struct {
		name    string
		arrange func(t *testing.T, r *rig)
		says    string
	}{
		{
			name: "the container is stopped",
			arrange: func(_ *testing.T, r *rig) {
				r.containers.place(workloadID, false, "Exited (0) 3 minutes ago")
			},
			says: "container is not running",
		},
		{
			name:    "there is no container yet",
			arrange: func(_ *testing.T, r *rig) { r.containers.forget(workloadID) },
			says:    "no container on this node",
		},
		{
			name: "the workload is not in the spec",
			arrange: func(t *testing.T, r *rig) {
				r.publishWith(t, nil, task("cron-1", "* * * * *"))
			},
			says: "not in this node's spec",
		},
		{
			name: "the engine will not answer",
			arrange: func(_ *testing.T, r *rig) {
				r.containers.lookupErr = errors.New("dial unix /var/run/docker.sock: no such file")
			},
			// "Cannot see it" is never reported as "it is not there": a Docker restart must
			// not be shown to a customer as their application having stopped.
			says: "engine could not be asked",
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			r := newRig(t)
			r.publish(t, task("cron-1", "* * * * *"))
			test.arrange(t, r)

			r.tick(t)
			r.tickAt(t, noon.Add(time.Minute))

			if ran := r.containers.ran(); len(ran) != 0 {
				t.Fatalf("%d execution(s) started with nowhere to run", len(ran))
			}
			run := r.history(t, "cron-1")
			if run.LastExitCode != exitNodeFailed {
				t.Errorf("exit code = %d, want %d", run.LastExitCode, exitNodeFailed)
			}
			if !strings.Contains(run.LastError, test.says) {
				t.Errorf("detail = %q, want it to mention %q", run.LastError, test.says)
			}
			if want := noon.Add(2 * time.Minute); !run.NextRunAt.Equal(want) {
				t.Errorf("next run = %s, want %s: the entry keeps its schedule", run.NextRunAt, want)
			}
		})
	}
}

// A panic in one job stays inside that job. The loop keeps its tick, the entry gets a
// recorded failure instead of the node getting a restart, and the row does not stay marked as
// running - which would stop an entry that may not overlap from ever firing again.
func TestAPanicInOneJobDoesNotStopTheLoop(t *testing.T) {
	r := newRig(t)
	attempts := 0
	r.containers.respond = func(context.Context, string) (runtime.RunResult, error) {
		attempts++
		if attempts == 1 {
			panic("the engine client dereferenced something")
		}
		return runtime.RunResult{}, nil
	}
	r.publish(t, task("cron-1", "* * * * *"))

	r.tick(t)
	r.tickAt(t, noon.Add(time.Minute))

	crashed := r.history(t, "cron-1")
	if crashed.Running {
		t.Error("the entry is still marked as running, so an entry that may not overlap would " +
			"never fire again")
	}
	if crashed.LastExitCode != exitNodeFailed ||
		!strings.Contains(crashed.LastError, "this node failed while running the command") {
		t.Errorf("after a panic: %+v, want it recorded against the entry", crashed)
	}

	r.tickAt(t, noon.Add(2*time.Minute))
	if run := r.history(t, "cron-1"); run.LastExitCode != 0 {
		t.Errorf("the firing after the panic: %+v, want a clean run", run)
	}
}

// What travels with the status is the end of the output, because that is where a stack trace,
// a `set -x` transcript and a compiler all put the thing that went wrong.
func TestPrinted(t *testing.T) {
	long := strings.Repeat("a", maxDetail+500) + "the last line"

	tests := []struct {
		name   string
		result runtime.RunResult
		want   string
	}{
		{"nothing printed", runtime.RunResult{}, ""},
		{"standard error", runtime.RunResult{Stderr: []byte(" boom\n")}, ": boom"},
		{
			name:   "standard output when there was no standard error",
			result: runtime.RunResult{Stdout: []byte("it went wrong\n")},
			want:   ": it went wrong",
		},
		{
			name:   "the engine dropped it all",
			result: runtime.RunResult{Truncated: true},
			want:   ": it printed more than this node keeps, and none of the end of it survived",
		},
	}
	for _, test := range tests {
		if got := printed(test.result); got != test.want {
			t.Errorf("%s: printed = %q, want %q", test.name, got, test.want)
		}
	}

	clipped := printed(runtime.RunResult{Stderr: []byte(long)})
	if len(clipped) > maxDetail+64 {
		t.Errorf("a long output produced %d bytes; the status batch carries this every fifteen "+
			"seconds for as long as the failure lasts", len(clipped))
	}
	if !strings.HasSuffix(clipped, "the last line") {
		t.Errorf("the tail was dropped instead of the head: %q", clipped[:64])
	}
	if !strings.Contains(clipped, "[earlier output dropped]") {
		t.Error("output was dropped without saying so")
	}
}
