package state

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"
)

// only returns the single cron entry the test wrote, and fails if there is not exactly one.
func only(t *testing.T, store *Store) CronRun {
	t.Helper()
	runs, err := store.CronRuns(context.Background())
	if err != nil {
		t.Fatalf("read the cron history: %v", err)
	}
	if len(runs) != 1 {
		t.Fatalf("read %d cron entries, want 1", len(runs))
	}
	return runs[0]
}

func TestACronRunIsRecordedBeforeItStarts(t *testing.T) {
	// Written before the command is launched, not after it returns: a daemon killed while a
	// job is running has to leave a trace of the run at all.
	store := openStore(t)
	ctx := context.Background()

	if err := store.StartCronRun(ctx, "cron-nightly", "wl-api", noon); err != nil {
		t.Fatalf("start the run: %v", err)
	}

	run := only(t, store)
	if !run.Running {
		t.Fatal("a run that has just started does not report itself running")
	}
	if !run.StartedAt.Equal(noon) || !run.LastRunAt.Equal(noon) {
		t.Fatalf("started at %s, last run at %s, want %s", run.StartedAt, run.LastRunAt, noon)
	}
	if !run.LastFinishedAt.IsZero() {
		t.Fatalf("a running job has already finished, at %s", run.LastFinishedAt)
	}
	if run.WorkloadID != "wl-api" {
		t.Fatalf("workload = %q", run.WorkloadID)
	}
}

func TestFinishingACronRunKeepsWhenItLastRan(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()
	finished := noon.Add(4 * time.Minute)

	if err := store.StartCronRun(ctx, "cron-nightly", "wl-api", noon); err != nil {
		t.Fatalf("start the run: %v", err)
	}
	// A non-zero exit code is a result, not an error: the customer's script failing is
	// information the panel shows.
	if err := store.FinishCronRun(ctx, "cron-nightly", 2, finished, "the report script exited 2"); err != nil {
		t.Fatalf("finish the run: %v", err)
	}

	run := only(t, store)
	if run.Running {
		t.Fatal("a finished run still reports itself running")
	}
	if !run.StartedAt.IsZero() {
		t.Fatalf("a finished run still has a start time of %s", run.StartedAt)
	}
	if !run.LastRunAt.Equal(noon) || !run.LastFinishedAt.Equal(finished) {
		t.Fatalf("last run %s, last finished %s", run.LastRunAt, run.LastFinishedAt)
	}
	if run.LastExitCode != 2 {
		t.Fatalf("exit code = %d, want 2", run.LastExitCode)
	}
	if run.LastError != "the report script exited 2" {
		t.Fatalf("detail = %q", run.LastError)
	}
}

func TestFinishingACronEntryNobodyStartedIsAnError(t *testing.T) {
	err := openStore(t).FinishCronRun(context.Background(), "cron-ghost", 0, noon, "done")
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("finishing an unknown cron entry returned %v, want ErrNotFound", err)
	}
}

func TestASkippedRunSaysWhyItWasSkipped(t *testing.T) {
	// A schedule that appears not to be firing needs to explain itself, or the customer's
	// only evidence is a job that silently stopped.
	store := openStore(t)
	ctx := context.Background()

	if err := store.SkipCronRun(ctx, "cron-nightly", "wl-api", noon); err != nil {
		t.Fatalf("skip the run: %v", err)
	}

	run := only(t, store)
	if !run.LastSkipped {
		t.Fatal("the skipped run is not marked as skipped")
	}
	if !strings.Contains(run.LastError, "had not finished") {
		t.Fatalf("the reason does not explain the skip: %q", run.LastError)
	}
	if run.Running {
		t.Fatal("a skipped run reports itself running")
	}
}

func TestStartingARunClearsThePreviousSkip(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	if err := store.SkipCronRun(ctx, "cron-nightly", "wl-api", noon); err != nil {
		t.Fatalf("skip the run: %v", err)
	}
	if err := store.StartCronRun(ctx, "cron-nightly", "wl-api", noon.Add(24*time.Hour)); err != nil {
		t.Fatalf("start the next run: %v", err)
	}

	if run := only(t, store); run.LastSkipped {
		t.Fatal("a job that has since run still reports its last firing as skipped")
	}
}

func TestTheNextRunSurvivesARestart(t *testing.T) {
	// Stored so a restarted daemon can report the schedule without waiting for the next tick.
	store := openStore(t)
	ctx := context.Background()
	next := noon.Add(15 * time.Hour)

	if err := store.ScheduleCronRun(ctx, "cron-nightly", "wl-api", next); err != nil {
		t.Fatalf("record the next run: %v", err)
	}
	if run := only(t, store); !run.NextRunAt.Equal(next) {
		t.Fatalf("next run at %s, want %s", run.NextRunAt, next)
	}

	// Scheduling must not disturb a run that is in flight or the history behind it.
	if err := store.StartCronRun(ctx, "cron-nightly", "wl-api", noon); err != nil {
		t.Fatalf("start the run: %v", err)
	}
	if err := store.ScheduleCronRun(ctx, "cron-nightly", "wl-api", next.Add(24*time.Hour)); err != nil {
		t.Fatalf("record the following run: %v", err)
	}
	run := only(t, store)
	if !run.Running {
		t.Fatal("scheduling the next run cleared the running flag")
	}
	if !run.NextRunAt.Equal(next.Add(24 * time.Hour)) {
		t.Fatalf("next run at %s", run.NextRunAt)
	}
}

func TestACronEntryNeedsAnIdentifier(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	if err := store.StartCronRun(ctx, "", "wl-api", noon); err == nil {
		t.Fatal("a run with no cron id was recorded")
	}
	if err := store.SkipCronRun(ctx, "", "wl-api", noon); err == nil {
		t.Fatal("a skip with no cron id was recorded")
	}
	if err := store.ScheduleCronRun(ctx, "", "wl-api", noon); err == nil {
		t.Fatal("a schedule with no cron id was recorded")
	}
}

func TestARunInterruptedByARestartIsClearedOnStartup(t *testing.T) {
	// The daemon is crash-only, so a row saying "running" after a restart means the process
	// died with the job. Leaving it would make the entry look permanently busy and, where
	// overlap is not allowed, stop it ever firing again.
	store := openStore(t)
	ctx := context.Background()
	restart := noon.Add(10 * time.Minute)

	if err := store.StartCronRun(ctx, "cron-nightly", "wl-api", noon); err != nil {
		t.Fatalf("start the run: %v", err)
	}
	if err := store.StartCronRun(ctx, "cron-hourly", "wl-api", noon); err != nil {
		t.Fatalf("start the second run: %v", err)
	}
	if err := store.FinishCronRun(ctx, "cron-hourly", 0, noon.Add(time.Minute), ""); err != nil {
		t.Fatalf("finish the second run: %v", err)
	}

	cleared, err := store.ClearRunningCronRuns(ctx, restart)
	if err != nil {
		t.Fatalf("clear the interrupted runs: %v", err)
	}
	if cleared != 1 {
		t.Fatalf("cleared %d runs, want 1", cleared)
	}

	runs, err := store.CronRuns(ctx)
	if err != nil {
		t.Fatalf("read the cron history: %v", err)
	}
	byID := map[string]CronRun{}
	for _, run := range runs {
		byID[run.CronID] = run
	}
	interrupted := byID["cron-nightly"]
	if interrupted.Running {
		t.Fatal("the interrupted run still reports itself running")
	}
	if interrupted.LastExitCode != -1 {
		t.Fatalf("exit code = %d, want -1 for an interrupted run", interrupted.LastExitCode)
	}
	if !strings.Contains(interrupted.LastError, "interrupted") {
		t.Fatalf("the interrupted run does not say so: %q", interrupted.LastError)
	}
	if !interrupted.LastFinishedAt.Equal(restart) {
		t.Fatalf("the interrupted run finished at %s, want the restart at %s", interrupted.LastFinishedAt, restart)
	}
	// The run that finished normally must be left exactly as it was.
	if byID["cron-hourly"].LastExitCode != 0 || byID["cron-hourly"].LastError != "" {
		t.Fatalf("a completed run was rewritten: %+v", byID["cron-hourly"])
	}
}

func TestPruningKeepsOnlyTheCronEntriesInTheSpec(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	for _, id := range []string{"cron-nightly", "cron-hourly", "cron-deleted"} {
		if err := store.StartCronRun(ctx, id, "wl-api", noon); err != nil {
			t.Fatalf("start %s: %v", id, err)
		}
	}

	removed, err := store.PruneCronRuns(ctx, []string{"cron-nightly", "cron-hourly"})
	if err != nil {
		t.Fatalf("prune: %v", err)
	}
	if removed != 1 {
		t.Fatalf("pruned %d entries, want 1", removed)
	}

	runs, err := store.CronRuns(ctx)
	if err != nil {
		t.Fatalf("read the cron history: %v", err)
	}
	if len(runs) != 2 {
		t.Fatalf("%d entries survived, want 2", len(runs))
	}
	// Sorted by cron id, so a status batch does not change shape between two identical passes.
	if runs[0].CronID != "cron-hourly" || runs[1].CronID != "cron-nightly" {
		t.Fatalf("entries came back in the order %s, %s", runs[0].CronID, runs[1].CronID)
	}

	emptied, err := store.PruneCronRuns(ctx, nil)
	if err != nil {
		t.Fatalf("prune everything: %v", err)
	}
	if emptied != 2 {
		t.Fatalf("an empty keep list dropped %d entries, want 2", emptied)
	}
}
