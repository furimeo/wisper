package state

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
	"google.golang.org/protobuf/proto"
)

// The test this package exists to pass.
//
// sasayaki is crash-only: it does no cleanup on the way out, and being killed has to be
// indistinguishable from a clean stop (AGENTS.md section 4.4). Closing a *Store and
// reopening it proves nothing about that, because Close checkpoints the write-ahead log
// and runs every path a SIGKILL skips. So the writer here is a real second process that
// calls os.Exit in the middle of its work, and the parent then opens the file it left
// behind and reads everything back.

// crashEnv carries the database path to the child, and its presence is what turns the
// helper below from a skipped test into the writer.
const crashEnv = "WISPER_STATE_CRASH_DB"

// crashExitCode is what the child exits with. Distinct from 0 and 1 so a helper that
// failed its own assertions is not mistaken for one that died on cue.
const crashExitCode = 97

func TestNodeStateSurvivesTheProcessBeingKilled(t *testing.T) {
	path := filepath.Join(t.TempDir(), FileName)
	ctx := context.Background()

	child := exec.Command(os.Args[0], "-test.run=^TestWriteEverythingThenDie$", "-test.count=1")
	child.Env = append(os.Environ(), crashEnv+"="+path)
	output, err := child.CombinedOutput()

	var exit *exec.ExitError
	switch {
	case err == nil:
		t.Fatalf("the writer exited cleanly instead of dying:\n%s", output)
	case !errors.As(err, &exit):
		t.Fatalf("run the writer: %v\n%s", err, output)
	case exit.ExitCode() != crashExitCode:
		t.Fatalf("the writer exited %d rather than dying at the arranged point:\n%s", exit.ExitCode(), output)
	}

	// No Close was called, no WAL was checkpointed, and no shutdown hook ran. This is the
	// file a machine that lost power leaves behind.
	store := openStoreAt(t, path)
	if store.RecoveredFrom() != "" {
		t.Fatalf("a killed process left a file the daemon had to quarantine: %s", store.RecoveredFrom())
	}

	// The spec, byte for byte. Everything the reconcile loop does after a cold start begins
	// with this document.
	stored, err := store.LoadSpec(ctx)
	if err != nil {
		t.Fatalf("the spec did not survive: %v", err)
	}
	if !proto.Equal(stored.Spec, sampleSpec(11)) {
		t.Fatalf("the spec came back changed:\n%v", stored.Spec)
	}
	if stored.Generation != 11 {
		t.Fatalf("generation = %d, want 11", stored.Generation)
	}

	converged, err := store.Convergence(ctx)
	if err != nil {
		t.Fatalf("read convergence: %v", err)
	}
	if converged.AppliedGeneration != 11 {
		t.Fatalf("applied generation = %d, want 11", converged.AppliedGeneration)
	}

	// The observed half. Losing this is how "crash-looping for an hour" becomes "just
	// started" on every restart.
	statuses, err := store.WorkloadStatuses(ctx)
	if err != nil {
		t.Fatalf("read the workload statuses: %v", err)
	}
	if len(statuses) != 1 || statuses[0].Status.GetWorkloadId() != "wl-api" {
		t.Fatalf("workload statuses = %+v, want one for wl-api", statuses)
	}
	if statuses[0].Status.GetPhase() != wisperpb.WorkloadPhase_WORKLOAD_PHASE_RUNNING {
		t.Fatalf("phase = %s, want RUNNING", statuses[0].Status.GetPhase())
	}

	// The upload that was in flight when the machine went down. A customer on mobile data
	// must not be told to start again.
	progress, err := store.UploadProgress(ctx, "up-old")
	if err != nil {
		t.Fatalf("the upload session did not survive: %v", err)
	}
	if progress.ReceivedBytes != 1<<20 || progress.NextOffset != 1<<20 {
		t.Fatalf("the upload came back at %d bytes, resuming at %d", progress.ReceivedBytes, progress.NextOffset)
	}
	if progress.Session.StagingPath != sampleSession("up-old").StagingPath {
		t.Fatalf("the staging path was lost: %q", progress.Session.StagingPath)
	}

	certificate, err := store.Certificate(ctx, "api.example.test")
	if err != nil {
		t.Fatalf("the certificate record did not survive: %v", err)
	}
	if certificate != issued("api.example.test", noon) {
		t.Fatalf("the certificate record came back changed: %+v", certificate)
	}

	enrolment, err := store.Enrolment(ctx)
	if err != nil {
		t.Fatalf("the enrolment did not survive: %v", err)
	}
	if enrolment != enrolledAs("node-7", "https://panel.example") {
		t.Fatalf("the enrolment came back changed: %+v", enrolment)
	}

	assertInterruptedWorkIsVisible(t, store)
}

// assertInterruptedWorkIsVisible checks the half of the database that exists so a restarted
// daemon can tell the panel what died with it. A build stuck at "building" forever is the
// state a customer cannot get out of on their own.
func assertInterruptedWorkIsVisible(t *testing.T, store *Store) {
	t.Helper()
	ctx := context.Background()

	builds, err := store.UnfinishedBuilds(ctx)
	if err != nil {
		t.Fatalf("list the unfinished builds: %v", err)
	}
	if len(builds) != 1 || builds[0].BuildID != "b-old" {
		t.Fatalf("unfinished builds = %+v, want b-old", builds)
	}
	if builds[0].Stage != wisperpb.BuildStage_BUILD_STAGE_FETCH {
		t.Fatalf("the interrupted build lost the stage it reached: %s", builds[0].Stage)
	}

	// The one that matters most: a backup killed during QUIESCE may have left the workload
	// paused, and only this row says so.
	backups, err := store.UnfinishedBackups(ctx)
	if err != nil {
		t.Fatalf("list the unfinished backups: %v", err)
	}
	if len(backups) != 1 || backups[0].Stage != wisperpb.BackupStage_BACKUP_STAGE_QUIESCE {
		t.Fatalf("unfinished backups = %+v, want bk-old still quiesced", backups)
	}

	restores, err := store.UnfinishedRestores(ctx)
	if err != nil {
		t.Fatalf("list the unfinished restores: %v", err)
	}
	if len(restores) != 1 || !restores[0].StopWorkload {
		t.Fatalf("unfinished restores = %+v, want rs-old with its stopped workload recorded", restores)
	}

	// The cron entry was written down before its command was launched, so the run leaves a
	// trace even though nothing finished it. Startup turns that into an honest "interrupted".
	runs, err := store.CronRuns(ctx)
	if err != nil {
		t.Fatalf("read the cron history: %v", err)
	}
	if len(runs) != 1 || !runs[0].Running {
		t.Fatalf("cron history = %+v, want cron-nightly still marked running", runs)
	}
	cleared, err := store.ClearRunningCronRuns(ctx, noon.Add(time.Hour))
	if err != nil {
		t.Fatalf("clear the interrupted runs: %v", err)
	}
	if cleared != 1 {
		t.Fatalf("cleared %d interrupted cron runs, want 1", cleared)
	}
}

// TestWriteEverythingThenDie is the child process. It is a test only so that `go test` will
// dispatch to it; without the environment variable it does nothing.
func TestWriteEverythingThenDie(t *testing.T) {
	path := os.Getenv(crashEnv)
	if path == "" {
		t.Skip("this is the writer half of TestNodeStateSurvivesTheProcessBeingKilled")
	}

	store, err := Open(context.Background(), path)
	if err != nil {
		t.Fatalf("open %s: %v", path, err)
	}
	if _, err := store.SaveEnrolment(context.Background(), enrolledAs("node-7", "https://panel.example")); err != nil {
		t.Fatalf("save the enrolment: %v", err)
	}
	fill(t, store)

	// Killed. No Close, no checkpoint, no deferred cleanup - exactly what a SIGKILL, an OOM
	// kill or the machine losing power does to this process.
	os.Exit(crashExitCode)
}
