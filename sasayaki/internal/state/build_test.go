package state

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Over three hundred lines and deliberately not split. Every function here is one
// behaviour of build.go, and a test file broken up by size rather than by subject leaves
// the reader hunting for which of two files holds the case they are looking for. The
// subject is one file; so is its test.

func startedBuild(id, workload string, at time.Time) BuildRun {
	return BuildRun{
		BuildID:    id,
		WorkloadID: workload,
		ReleaseID:  id,
		Stage:      wisperpb.BuildStage_BUILD_STAGE_FETCH,
		StartedAt:  at,
		Detail:     "cloning",
	}
}

func TestBeginBuildRecordsAnUnfinishedRun(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	if err := store.BeginBuild(ctx, startedBuild("b-1", "wl-site", noon)); err != nil {
		t.Fatalf("begin the build: %v", err)
	}
	run, err := store.Build(ctx, "b-1")
	if err != nil {
		t.Fatalf("read the build: %v", err)
	}
	if run.Finished || run.Success {
		t.Fatalf("a build that has just started reads as finished=%v success=%v", run.Finished, run.Success)
	}
	if run.Result != nil {
		t.Fatal("a running build already has a result")
	}
	if !run.StartedAt.Equal(noon) || !run.UpdatedAt.Equal(noon) {
		t.Fatalf("timestamps are %s and %s, want %s", run.StartedAt, run.UpdatedAt, noon)
	}
}

func TestBeginBuildTwiceIsRefused(t *testing.T) {
	// The control stream drops and the panel resends StartBuild. A second clone and compile
	// of the same commit is wasted minutes on a machine that has customers on it.
	store := openStore(t)
	ctx := context.Background()

	if err := store.BeginBuild(ctx, startedBuild("b-2", "wl-site", noon)); err != nil {
		t.Fatalf("begin the build: %v", err)
	}
	err := store.BeginBuild(ctx, startedBuild("b-2", "wl-site", noon.Add(time.Minute)))
	if !errors.Is(err, ErrAlreadyExists) {
		t.Fatalf("starting the same build twice returned %v, want ErrAlreadyExists", err)
	}
}

func TestBeginBuildValidatesItsIdentifiers(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	if err := store.BeginBuild(ctx, BuildRun{WorkloadID: "wl-site"}); err == nil {
		t.Fatal("a build with no id was recorded")
	}
	if err := store.BeginBuild(ctx, BuildRun{BuildID: "b-3"}); err == nil {
		t.Fatal("a build that names no workload was recorded")
	}
}

func TestRecordBuildStageMovesARunningBuildOn(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()
	later := noon.Add(20 * time.Second)

	if err := store.BeginBuild(ctx, startedBuild("b-4", "wl-site", noon)); err != nil {
		t.Fatalf("begin the build: %v", err)
	}
	if err := store.RecordBuildStage(ctx, "b-4", wisperpb.BuildStage_BUILD_STAGE_INSTALL, later, "npm ci"); err != nil {
		t.Fatalf("record the stage: %v", err)
	}

	run, err := store.Build(ctx, "b-4")
	if err != nil {
		t.Fatalf("read the build: %v", err)
	}
	if run.Stage != wisperpb.BuildStage_BUILD_STAGE_INSTALL {
		t.Fatalf("stage = %s, want INSTALL", run.Stage)
	}
	if run.Detail != "npm ci" {
		t.Fatalf("detail = %q", run.Detail)
	}
	if !run.UpdatedAt.Equal(later) {
		t.Fatalf("updated at %s, want %s", run.UpdatedAt, later)
	}
}

func TestRecordingAStageAgainstNothingIsAnError(t *testing.T) {
	// Without this the bug surfaces as a build whose progress never moves, a long way from
	// the call that got the id wrong.
	store := openStore(t)
	ctx := context.Background()

	err := store.RecordBuildStage(ctx, "b-ghost", wisperpb.BuildStage_BUILD_STAGE_BUILD, noon, "compiling")
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("a stage against an unknown build returned %v, want ErrNotFound", err)
	}

	if err := store.BeginBuild(ctx, startedBuild("b-5", "wl-site", noon)); err != nil {
		t.Fatalf("begin the build: %v", err)
	}
	if err := store.FinishBuild(ctx, "b-5", &wisperpb.BuildCompleted{BuildId: "b-5", Success: true}, noon); err != nil {
		t.Fatalf("finish the build: %v", err)
	}
	err = store.RecordBuildStage(ctx, "b-5", wisperpb.BuildStage_BUILD_STAGE_BUILD, noon, "compiling")
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("a stage against a finished build returned %v, want ErrNotFound", err)
	}
}

func TestFinishBuildStoresTheResultWhole(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()
	finished := noon.Add(3 * time.Minute)
	completed := &wisperpb.BuildCompleted{
		BuildId:       "b-6",
		Success:       true,
		ReleaseId:     "dep-77",
		ImageRef:      "wisper/site:dep-77",
		ImageDigest:   "sha256:abcd",
		Commit:        "9f1c2b",
		CommitMessage: "fix the footer",
		ArtifactBytes: 4 << 20,
		Detail:        "published",
	}

	if err := store.BeginBuild(ctx, startedBuild("b-6", "wl-site", noon)); err != nil {
		t.Fatalf("begin the build: %v", err)
	}
	if err := store.FinishBuild(ctx, "b-6", completed, finished); err != nil {
		t.Fatalf("finish the build: %v", err)
	}

	run, err := store.Build(ctx, "b-6")
	if err != nil {
		t.Fatalf("read the build: %v", err)
	}
	if !run.Finished || !run.Success {
		t.Fatalf("finished=%v success=%v after a successful build", run.Finished, run.Success)
	}
	// A success ends at PUBLISH whatever failed_stage happens to hold.
	if run.Stage != wisperpb.BuildStage_BUILD_STAGE_PUBLISH {
		t.Fatalf("stage = %s, want PUBLISH", run.Stage)
	}
	if run.ReleaseID != "dep-77" {
		t.Fatalf("release id = %q", run.ReleaseID)
	}
	if run.Result.GetCommitMessage() != "fix the footer" {
		t.Fatalf("the stored result lost the commit message: %v", run.Result)
	}
	if !run.FinishedAt.Equal(finished) {
		t.Fatalf("finished at %s, want %s", run.FinishedAt, finished)
	}
}

func TestAFailedBuildKeepsTheStageItFailedAt(t *testing.T) {
	// Where a build stopped is the first thing a customer needs: a failure at FETCH is their
	// repository, at BUILD it is their code.
	store := openStore(t)
	ctx := context.Background()

	if err := store.BeginBuild(ctx, startedBuild("b-7", "wl-site", noon)); err != nil {
		t.Fatalf("begin the build: %v", err)
	}
	if err := store.FinishBuild(ctx, "b-7", &wisperpb.BuildCompleted{
		BuildId:     "b-7",
		Success:     false,
		FailedStage: wisperpb.BuildStage_BUILD_STAGE_BUILD,
		ExitCode:    1,
		Detail:      "vite: command not found",
	}, noon.Add(time.Minute)); err != nil {
		t.Fatalf("finish the build: %v", err)
	}

	run, err := store.Build(ctx, "b-7")
	if err != nil {
		t.Fatalf("read the build: %v", err)
	}
	if !run.Finished || run.Success {
		t.Fatalf("finished=%v success=%v after a failed build", run.Finished, run.Success)
	}
	if run.Stage != wisperpb.BuildStage_BUILD_STAGE_BUILD {
		t.Fatalf("stage = %s, want BUILD", run.Stage)
	}
	if run.Detail != "vite: command not found" {
		t.Fatalf("detail = %q", run.Detail)
	}
}

func TestFinishingAnUnknownBuildOrWithoutAResult(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	if err := store.FinishBuild(ctx, "b-8", nil, noon); err == nil {
		t.Fatal("a build finished with no result, which the panel would wait on forever")
	}
	err := store.FinishBuild(ctx, "b-ghost", &wisperpb.BuildCompleted{BuildId: "b-ghost"}, noon)
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("finishing an unknown build returned %v, want ErrNotFound", err)
	}
}

func TestUnfinishedBuildsAreWhatDiedWithTheDaemon(t *testing.T) {
	// A deployment stuck at "building" forever is the state a customer cannot get out of on
	// their own, and the node is the only side that knows it is not still happening.
	store := openStore(t)
	ctx := context.Background()

	if err := store.BeginBuild(ctx, startedBuild("b-done", "wl-site", noon)); err != nil {
		t.Fatalf("begin the finished build: %v", err)
	}
	if err := store.FinishBuild(ctx, "b-done", &wisperpb.BuildCompleted{BuildId: "b-done", Success: true}, noon); err != nil {
		t.Fatalf("finish the build: %v", err)
	}
	if err := store.BeginBuild(ctx, startedBuild("b-inflight", "wl-site", noon.Add(time.Minute))); err != nil {
		t.Fatalf("begin the in-flight build: %v", err)
	}

	unfinished, err := store.UnfinishedBuilds(ctx)
	if err != nil {
		t.Fatalf("list the unfinished builds: %v", err)
	}
	if len(unfinished) != 1 || unfinished[0].BuildID != "b-inflight" {
		t.Fatalf("unfinished builds = %+v, want only b-inflight", unfinished)
	}
}

func TestBuildsAreListedNewestFirstAndFilteredByWorkload(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	for i := 0; i < 3; i++ {
		id := fmt.Sprintf("b-site-%d", i)
		if err := store.BeginBuild(ctx, startedBuild(id, "wl-site", noon.Add(time.Duration(i)*time.Minute))); err != nil {
			t.Fatalf("begin %s: %v", id, err)
		}
	}
	if err := store.BeginBuild(ctx, startedBuild("b-other", "wl-other", noon)); err != nil {
		t.Fatalf("begin the other workload's build: %v", err)
	}

	history, err := store.Builds(ctx, "wl-site", 0)
	if err != nil {
		t.Fatalf("list the history: %v", err)
	}
	if len(history) != 3 {
		t.Fatalf("listed %d builds for wl-site, want 3", len(history))
	}
	if history[0].BuildID != "b-site-2" || history[2].BuildID != "b-site-0" {
		t.Fatalf("history is not newest first: %s .. %s", history[0].BuildID, history[2].BuildID)
	}

	limited, err := store.Builds(ctx, "", 2)
	if err != nil {
		t.Fatalf("list every workload's history: %v", err)
	}
	if len(limited) != 2 {
		t.Fatalf("the limit produced %d rows, want 2", len(limited))
	}
}

func TestPruneBuildsKeepsTheRecentFinishedOnes(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	for i := 0; i < 5; i++ {
		id := fmt.Sprintf("b-%d", i)
		if err := store.BeginBuild(ctx, startedBuild(id, "wl-site", noon.Add(time.Duration(i)*time.Minute))); err != nil {
			t.Fatalf("begin %s: %v", id, err)
		}
		if err := store.FinishBuild(ctx, id, &wisperpb.BuildCompleted{BuildId: id, Success: true},
			noon.Add(time.Duration(i)*time.Minute)); err != nil {
			t.Fatalf("finish %s: %v", id, err)
		}
	}
	// Still running, and therefore something is still waiting on it.
	if err := store.BeginBuild(ctx, startedBuild("b-inflight", "wl-site", noon.Add(time.Hour))); err != nil {
		t.Fatalf("begin the in-flight build: %v", err)
	}

	removed, err := store.PruneBuilds(ctx, 2)
	if err != nil {
		t.Fatalf("prune: %v", err)
	}
	if removed != 3 {
		t.Fatalf("pruned %d builds, want 3", removed)
	}

	remaining, err := store.Builds(ctx, "wl-site", 0)
	if err != nil {
		t.Fatalf("list the history: %v", err)
	}
	if len(remaining) != 3 {
		t.Fatalf("%d builds survived, want the two most recent plus the running one", len(remaining))
	}
	if _, err := store.Build(ctx, "b-inflight"); err != nil {
		t.Fatalf("the running build was pruned: %v", err)
	}
	if _, err := store.Build(ctx, "b-0"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("the oldest finished build survived: %v", err)
	}
	if _, err := store.PruneBuilds(ctx, -1); err == nil {
		t.Fatal("a negative retention was accepted")
	}
}
