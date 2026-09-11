package build

import (
	"context"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// Collecting a build's output into a release directory, which is the last thing that can
// go wrong before a deployment becomes something the panel may publish.

func TestCollectReleaseCopiesTheOutputDirectory(t *testing.T) {
	h := newHarness(t)

	output := filepath.Join(t.TempDir(), "dist")
	writeTree(t, output, map[string]string{
		"index.html":       "<h1>hello</h1>",
		"assets/app.js":    "console.log(1)",
		"nested/deep/file": "x",
	})

	size, err := h.Builder.collectRelease(context.Background(), "42", "77", output)
	if err != nil {
		t.Fatalf("collect: %v", err)
	}
	if size == 0 {
		t.Fatal("the release was measured as zero bytes")
	}

	directory, _ := releaseDir(h.StateDir, "42", "77")
	got := readTree(t, directory)
	want := map[string]string{
		"index.html":       "<h1>hello</h1>",
		"assets/app.js":    "console.log(1)",
		"nested/deep/file": "x",
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("the release is not the output directory: %v", got)
	}

	// The workspace keeps its copy, because the next build reuses it as a cache.
	if _, err := os.Stat(filepath.Join(output, "index.html")); err != nil {
		t.Fatalf("collecting the release took the build output with it: %v", err)
	}
}

// A checkout published as-is is what BUILD_PRESET_STATIC with no output directory means,
// and serving .git over HTTP hands out the whole history including whatever was committed
// and reverted.
func TestCollectReleaseLeavesTheGitDirectoryBehind(t *testing.T) {
	h := newHarness(t)

	output := filepath.Join(t.TempDir(), "checkout")
	writeTree(t, output, map[string]string{
		"index.html":         "<h1>hello</h1>",
		".git/config":        "[remote \"origin\"]",
		".git/objects/ab/cd": "binary",
		"docs/.git/HEAD":     "ref: refs/heads/main",
	})

	if _, err := h.Builder.collectRelease(context.Background(), "42", "77", output); err != nil {
		t.Fatalf("collect: %v", err)
	}

	directory, _ := releaseDir(h.StateDir, "42", "77")
	got := readTree(t, directory)
	for name := range got {
		if strings.Contains(name, ".git/") {
			t.Fatalf("the release would serve the repository: %v", got)
		}
	}
	if got["index.html"] != "<h1>hello</h1>" {
		t.Fatalf("the site itself did not survive: %v", got)
	}
}

func TestCollectReleaseRefusesAnEmptyOutputDirectory(t *testing.T) {
	h := newHarness(t)

	output := filepath.Join(t.TempDir(), "dist")
	if err := os.MkdirAll(output, 0o755); err != nil {
		t.Fatalf("create the output: %v", err)
	}

	// Almost always a plan whose output directory does not match what the generator writes.
	// Publishing it would replace a working site with an empty one.
	if _, err := h.Builder.collectRelease(context.Background(), "42", "77", output); err == nil {
		t.Fatal("an empty output directory was accepted")
	}
	if directory, _ := releaseDir(h.StateDir, "42", "77"); directoryExists(directory) {
		t.Fatal("a release directory was created for an empty output")
	}
}

func TestCollectReleaseRefusesAnOutputDirectoryThatIsNotThere(t *testing.T) {
	h := newHarness(t)

	missing := filepath.Join(t.TempDir(), "dist")
	_, err := h.Builder.collectRelease(context.Background(), "42", "77", missing)
	if err == nil {
		t.Fatal("a missing output directory was accepted")
	}
	if !strings.Contains(err.Error(), "output directory") {
		t.Fatalf("the message does not tell the customer what to fix: %v", err)
	}
}

func TestCollectReleaseRefusesALinkOutOfTheTree(t *testing.T) {
	requireSymlinkSwap(t)
	h := newHarness(t)

	output := filepath.Join(t.TempDir(), "dist")
	writeTree(t, output, map[string]string{"index.html": "hello"})
	if err := os.Symlink(filepath.FromSlash("../../../etc"), filepath.Join(output, "escape")); err != nil {
		t.Fatalf("create the link: %v", err)
	}

	if _, err := h.Builder.collectRelease(context.Background(), "42", "77", output); err == nil {
		t.Fatal("a release with a link out of the tree was accepted; the edge would serve it")
	}
	if directory, _ := releaseDir(h.StateDir, "42", "77"); directoryExists(directory) {
		t.Fatal("the refused release was left on disk")
	}
}

func TestCollectReleaseKeepsALinkInsideTheTree(t *testing.T) {
	requireSymlinkSwap(t)
	h := newHarness(t)

	output := filepath.Join(t.TempDir(), "dist")
	writeTree(t, output, map[string]string{"real/page.html": "hello"})
	if err := os.Symlink(filepath.FromSlash("real/page.html"), filepath.Join(output, "alias.html")); err != nil {
		t.Fatalf("create the link: %v", err)
	}

	if _, err := h.Builder.collectRelease(context.Background(), "42", "77", output); err != nil {
		t.Fatalf("a link inside the tree must be kept: %v", err)
	}
	directory, _ := releaseDir(h.StateDir, "42", "77")
	if _, err := os.Readlink(filepath.Join(directory, "alias.html")); err != nil {
		t.Fatalf("the link did not survive: %v", err)
	}
}

func TestCollectReleaseRefusesToReplaceTheLiveRelease(t *testing.T) {
	requireSymlinkSwap(t)
	h := newHarness(t)
	ctx := context.Background()

	live := map[string]string{"index.html": "the one people are looking at"}
	h.seedRelease(t, "42", "77", live)
	if err := h.Releases.Publish(ctx, "42", "77"); err != nil {
		t.Fatalf("publish: %v", err)
	}

	output := filepath.Join(t.TempDir(), "dist")
	writeTree(t, output, map[string]string{"index.html": "a rebuild"})

	if _, err := h.Builder.collectRelease(ctx, "42", "77", output); err == nil {
		t.Fatal("a rebuild replaced the release that is live")
	}
	link, _ := currentLink(h.StateDir, "42")
	if served := readTree(t, link); !reflect.DeepEqual(served, live) {
		t.Fatalf("the live site changed: %v", served)
	}
}

func TestCollectReleaseReplacesALeftoverThatIsNotLive(t *testing.T) {
	h := newHarness(t)

	h.seedRelease(t, "42", "77", map[string]string{"index.html": "from a run whose row was pruned"})
	output := filepath.Join(t.TempDir(), "dist")
	writeTree(t, output, map[string]string{"index.html": "the new one"})

	if _, err := h.Builder.collectRelease(context.Background(), "42", "77", output); err != nil {
		t.Fatalf("collect: %v", err)
	}
	directory, _ := releaseDir(h.StateDir, "42", "77")
	if got := readTree(t, directory); got["index.html"] != "the new one" {
		t.Fatalf("the leftover was not replaced: %v", got)
	}
}

func TestDiscardReleaseRemovesTheStagingDirectoryToo(t *testing.T) {
	h := newHarness(t)

	root, _ := releaseRoot(h.StateDir, "42")
	staging := filepath.Join(root, stagingPrefix+"77")
	if err := os.MkdirAll(staging, 0o755); err != nil {
		t.Fatalf("create the staging directory: %v", err)
	}
	h.seedRelease(t, "42", "77", map[string]string{"index.html": "half"})

	if err := discardRelease(h.StateDir, "42", "77"); err != nil {
		t.Fatalf("discard: %v", err)
	}
	if directoryExists(staging) {
		t.Fatal("the staging directory survived")
	}
	if directory, _ := releaseDir(h.StateDir, "42", "77"); directoryExists(directory) {
		t.Fatal("the release directory survived")
	}
}

func TestDirectorySizeCountsTheFilesAndNotTheLinkTargetTwice(t *testing.T) {
	root := t.TempDir()
	writeTree(t, root, map[string]string{"a.txt": "12345", "b/c.txt": "678"})

	size, err := directorySize(root)
	if err != nil {
		t.Fatalf("measure: %v", err)
	}
	if size != 8 {
		t.Fatalf("expected 8 bytes, got %d", size)
	}
}
