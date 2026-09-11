package build

import (
	"context"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Retention: keeping the last N releases and never, under any count, deleting the one the
// site is serving.

func TestPruneKeepsTheMostRecentN(t *testing.T) {
	h := newHarness(t)

	for _, id := range []string{"1", "2", "3", "4", "5"} {
		h.seedRelease(t, "42", id, map[string]string{"index.html": id})
	}

	removed, err := pruneReleases(h.StateDir, "42", 3)
	if err != nil {
		t.Fatalf("prune: %v", err)
	}
	sort.Strings(removed)
	if !reflect.DeepEqual(removed, []string{"1", "2"}) {
		t.Fatalf("expected the two oldest to go, got %v", removed)
	}

	left, err := h.Releases.Releases("42")
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if !reflect.DeepEqual(left, []string{"3", "4", "5"}) {
		t.Fatalf("expected releases 3, 4 and 5 to remain, got %v", left)
	}
}

// The ids are the panel's deployment ids, and sorting those as strings would put 10 before
// 9 and delete the newest release on a site that has been deployed ten times.
func TestPruneOrdersByWhenAReleaseLandedNotByName(t *testing.T) {
	h := newHarness(t)

	for _, id := range []string{"8", "9", "10", "11"} {
		h.seedRelease(t, "42", id, map[string]string{"index.html": id})
	}

	removed, err := pruneReleases(h.StateDir, "42", 2)
	if err != nil {
		t.Fatalf("prune: %v", err)
	}
	sort.Strings(removed)
	if !reflect.DeepEqual(removed, []string{"8", "9"}) {
		t.Fatalf("expected 8 and 9 to go, got %v", removed)
	}
	left, _ := h.Releases.Releases("42")
	sort.Strings(left)
	if !reflect.DeepEqual(left, []string{"10", "11"}) {
		t.Fatalf("expected 10 and 11 to remain, got %v", left)
	}
}

func TestPruneNeverRemovesTheReleaseBeingServed(t *testing.T) {
	requireSymlinkSwap(t)
	h := newHarness(t)
	ctx := context.Background()

	for _, id := range []string{"1", "2", "3", "4", "5"} {
		h.seedRelease(t, "42", id, map[string]string{"index.html": id})
	}
	// The customer has rolled back to the oldest release and left it there. Honouring
	// keep_releases literally would take their site off the internet.
	if err := h.Releases.Publish(ctx, "42", "1"); err != nil {
		t.Fatalf("publish: %v", err)
	}

	removed, err := pruneReleases(h.StateDir, "42", 2, "1")
	if err != nil {
		t.Fatalf("prune: %v", err)
	}
	for _, name := range removed {
		if name == "1" {
			t.Fatal("the live release was pruned")
		}
	}

	link, _ := currentLink(h.StateDir, "42")
	if served := readTree(t, link); served["index.html"] != "1" {
		t.Fatalf("the site stopped serving release 1: %v", served)
	}
	left, _ := h.Releases.Releases("42")
	sort.Strings(left)
	if !reflect.DeepEqual(left, []string{"1", "4", "5"}) {
		t.Fatalf("expected the live release plus the newest one under the quota, got %v", left)
	}
}

func TestPruneSweepsAbandonedStagingDirectoriesWithoutReportingThem(t *testing.T) {
	h := newHarness(t)

	h.seedRelease(t, "42", "1", map[string]string{"index.html": "one"})
	root, _ := releaseRoot(h.StateDir, "42")
	abandoned := filepath.Join(root, stagingPrefix+"2")
	if err := os.MkdirAll(abandoned, 0o755); err != nil {
		t.Fatalf("create the staging directory: %v", err)
	}

	removed, err := pruneReleases(h.StateDir, "42", 5)
	if err != nil {
		t.Fatalf("prune: %v", err)
	}
	if len(removed) != 0 {
		t.Fatalf("a staging directory is not a release and must not be reported as pruned: %v", removed)
	}
	if _, err := os.Stat(abandoned); !os.IsNotExist(err) {
		t.Fatalf("the abandoned staging directory is still there: %v", err)
	}
}

func TestPruneKeepsAtLeastOneRelease(t *testing.T) {
	h := newHarness(t)

	h.seedRelease(t, "42", "1", map[string]string{"index.html": "one"})
	h.seedRelease(t, "42", "2", map[string]string{"index.html": "two"})

	// A policy of zero would mean deleting the release that was just built, which is not a
	// number anybody can have meant.
	if _, err := pruneReleases(h.StateDir, "42", 0); err != nil {
		t.Fatalf("prune: %v", err)
	}
	left, _ := h.Releases.Releases("42")
	if !reflect.DeepEqual(left, []string{"2"}) {
		t.Fatalf("expected the newest release to survive a zero policy, got %v", left)
	}
}

func TestPruneOnASiteWithNoReleasesIsANoOp(t *testing.T) {
	h := newHarness(t)

	removed, err := pruneReleases(h.StateDir, "42", 3)
	if err != nil {
		t.Fatalf("pruning a site with nothing in it must not fail: %v", err)
	}
	if len(removed) != 0 {
		t.Fatalf("expected nothing to be pruned, got %v", removed)
	}
}

func TestPruneWorkspacesKeepsTheCurrentOneAndTheLastFew(t *testing.T) {
	h := newHarness(t)

	for _, id := range []string{"1", "2", "3", "4"} {
		space, err := h.Builder.prepareWorkspace("42", id, false)
		if err != nil {
			t.Fatalf("prepare workspace %s: %v", id, err)
		}
		writeTree(t, space.Checkout, map[string]string{"file": id})
	}

	pruneWorkspaces(h.StateDir, "42", 2, "4", testLogger())

	root, _ := workspaceRoot(h.StateDir, "42")
	entries, err := os.ReadDir(root)
	if err != nil {
		t.Fatalf("list workspaces: %v", err)
	}
	names := make([]string, 0, len(entries))
	for _, entry := range entries {
		names = append(names, entry.Name())
	}
	sort.Strings(names)
	if !reflect.DeepEqual(names, []string{"3", "4"}) {
		t.Fatalf("expected the current workspace and one before it, got %v", names)
	}
}

func TestRetentionUsesTheFloorWhenThePanelHasNotSpoken(t *testing.T) {
	h := newHarness(t)

	releases, workspaces := h.Builder.retention(context.Background())
	if releases != keepReleasesFloor || workspaces != keepWorkspacesFloor {
		t.Fatalf("expected the built-in floor (%d, %d), got (%d, %d)",
			keepReleasesFloor, keepWorkspacesFloor, releases, workspaces)
	}
}

func TestRetentionTakesTheLargerOfThePolicyAndTheFloor(t *testing.T) {
	h := newHarness(t)
	h.Store.Retention = &wisperpb.RetentionPolicy{KeepReleases: 10, KeepBuildWorkspaces: 5}

	releases, workspaces := h.Builder.retention(context.Background())
	if releases != 10 || workspaces != 5 {
		t.Fatalf("expected the panel's numbers, got (%d, %d)", releases, workspaces)
	}

	// A policy smaller than the floor does not shrink it: deleting a release a customer was
	// promised is worse than holding a directory.
	h.Store.Retention = &wisperpb.RetentionPolicy{KeepReleases: 1, KeepBuildWorkspaces: 0}
	releases, workspaces = h.Builder.retention(context.Background())
	if releases != keepReleasesFloor || workspaces != keepWorkspacesFloor {
		t.Fatalf("expected the floor to hold, got (%d, %d)", releases, workspaces)
	}
}
