package build

import (
	"context"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// A build that was in flight when the machine went down. Nobody else can answer for it: the
// panel is holding a command with no result and no way to ask, and the customer is looking
// at a deployment that says "building".

func TestAnAbandonedBuildIsClosedOutOnTheNextStart(t *testing.T) {
	h := newHarness(t)
	ctx := context.Background()

	// A build that was in flight when the daemon died: a row with no result, and a release
	// directory it had started to fill.
	if err := h.Store.BeginBuild(ctx, buildRunInFlight()); err != nil {
		t.Fatalf("seed the in-flight build: %v", err)
	}
	h.seedRelease(t, "42", "77", map[string]string{"index.html": "half written"})

	closed, err := h.Builder.FailAbandonedBuilds(ctx)
	if err != nil {
		t.Fatalf("close out abandoned builds: %v", err)
	}
	if closed != 1 {
		t.Fatalf("expected one abandoned build, got %d", closed)
	}

	run, err := h.Store.Build(ctx, "77")
	if err != nil {
		t.Fatalf("read the build back: %v", err)
	}
	if !run.Finished || run.Success {
		t.Fatal("the abandoned build was not recorded as failed, so the panel waits forever")
	}
	if directory, _ := releaseDir(h.StateDir, "42", "77"); directoryExists(directory) {
		t.Fatal("the half-written release of an abandoned build was left where a spec could name it")
	}
}

func TestAnAbandonedBuildThatIsLiveKeepsItsRelease(t *testing.T) {
	requireSymlinkSwap(t)
	h := newHarness(t)
	ctx := context.Background()

	if err := h.Store.BeginBuild(ctx, buildRunInFlight()); err != nil {
		t.Fatalf("seed the in-flight build: %v", err)
	}
	h.seedRelease(t, "42", "77", map[string]string{"index.html": "serving"})
	if err := h.Releases.Publish(ctx, "42", "77"); err != nil {
		t.Fatalf("publish: %v", err)
	}

	if _, err := h.Builder.FailAbandonedBuilds(ctx); err != nil {
		t.Fatalf("close out abandoned builds: %v", err)
	}

	link, _ := currentLink(h.StateDir, "42")
	if served := readTree(t, link); served["index.html"] != "serving" {
		t.Fatalf("tidying up a database record took the site down: %v", served)
	}
}

func buildRunInFlight() state.BuildRun {
	return state.BuildRun{
		BuildID:    "77",
		WorkloadID: "42",
		ReleaseID:  "77",
		Stage:      wisperpb.BuildStage_BUILD_STAGE_BUILD,
		StartedAt:  time.Date(2026, 9, 11, 9, 0, 0, 0, time.UTC),
		Detail:     "build",
	}
}
