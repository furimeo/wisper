package build

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/moby/moby/api/types/container"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The workspace: what carries over between builds, what does not, and what a killed daemon
// leaves behind.

func TestPrepareWorkspaceStartsEmptyWithoutTheCache(t *testing.T) {
	h := newHarness(t)

	first, err := h.Builder.prepareWorkspace("42", "1", false)
	if err != nil {
		t.Fatalf("prepare: %v", err)
	}
	writeTree(t, first.Checkout, map[string]string{"node_modules/left-pad/x.js": "cached"})

	second, err := h.Builder.prepareWorkspace("42", "2", false)
	if err != nil {
		t.Fatalf("prepare: %v", err)
	}
	if second.Reused != "" {
		t.Fatalf("a build that did not ask for the cache adopted %q", second.Reused)
	}
	if entries, _ := os.ReadDir(second.Checkout); len(entries) != 0 {
		t.Fatalf("the fresh workspace is not empty: %v", entries)
	}
}

func TestPrepareWorkspaceAdoptsTheNewestOneWithTheCache(t *testing.T) {
	h := newHarness(t)

	first, err := h.Builder.prepareWorkspace("42", "1", false)
	if err != nil {
		t.Fatalf("prepare: %v", err)
	}
	writeTree(t, first.Checkout, map[string]string{"node_modules/left-pad/x.js": "cached"})

	second, err := h.Builder.prepareWorkspace("42", "2", true)
	if err != nil {
		t.Fatalf("prepare: %v", err)
	}
	if second.Reused != "1" {
		t.Fatalf("expected the previous workspace to be adopted, got %q", second.Reused)
	}
	if got := readTree(t, second.Checkout); got["node_modules/left-pad/x.js"] != "cached" {
		t.Fatalf("the cache did not carry over: %v", got)
	}
	// A rename, not a copy: the old name is gone rather than duplicated on the disk the
	// customers share.
	if directoryExists(filepath.Join(filepath.Dir(second.Root), "1")) {
		t.Fatal("the previous workspace was copied rather than moved")
	}
}

func TestPrepareWorkspaceClearsAnAttemptTheDaemonDiedInsideOf(t *testing.T) {
	h := newHarness(t)

	first, err := h.Builder.prepareWorkspace("42", "1", false)
	if err != nil {
		t.Fatalf("prepare: %v", err)
	}
	writeTree(t, first.Checkout, map[string]string{"half-written.txt": "from the attempt that died"})

	again, err := h.Builder.prepareWorkspace("42", "1", false)
	if err != nil {
		t.Fatalf("prepare again: %v", err)
	}
	if entries, _ := os.ReadDir(again.Checkout); len(entries) != 0 {
		t.Fatal("a build was resumed on top of half a previous checkout, which is how the " +
			"wrong files get shipped")
	}
}

func TestAnArchiveBuildNeverAdoptsAWorkspace(t *testing.T) {
	h := newHarness(t)

	first, err := h.Builder.prepareWorkspace("42", "1", false)
	if err != nil {
		t.Fatalf("prepare: %v", err)
	}
	writeTree(t, first.Checkout, map[string]string{"stale.html": "deleted last week"})

	path, digest := zipOnDisk(t, map[string]string{"dist/index.html": "new"})
	h.Uploads.files["session-1"] = path

	request := archiveBuild("session-1", digest)
	request.BuildId = "2"
	request.UseCache = true

	if _, err := h.Builder.Build(context.Background(), request); err != nil {
		t.Fatalf("build: %v", err)
	}
	directory, _ := releaseDir(h.StateDir, "42", "2")
	got := readTree(t, directory)
	if _, present := got["stale.html"]; present {
		t.Fatalf("a previous build's file was published from an archive deployment: %v", got)
	}
}

func TestSweepRemovesBuildContainersAKilledDaemonLeft(t *testing.T) {
	h := newHarness(t)
	h.Engine.Existing = []container.Summary{
		{ID: "abandoned-1", Labels: map[string]string{labelBuild: "70"}},
		{ID: "abandoned-2", Labels: map[string]string{labelBuild: "71"}},
	}

	h.Builder.sweepAbandoned(context.Background(), testLogger())

	if len(h.Engine.Removed) != 2 {
		t.Fatalf("expected both abandoned containers to be removed, got %v", h.Engine.Removed)
	}
}

func TestHasRepositoryTellsAReusableCheckoutFromAnEmptyOne(t *testing.T) {
	root := t.TempDir()
	if hasRepository(root) {
		t.Fatal("an empty directory was treated as a repository")
	}
	if err := os.MkdirAll(filepath.Join(root, ".git"), 0o755); err != nil {
		t.Fatalf("create .git: %v", err)
	}
	if !hasRepository(root) {
		t.Fatal("a checkout with a .git directory was not recognised")
	}
}

// A cached git build fetches into the repository the previous build left, so untracked
// files - the whole point of the cache - survive.
func TestACachedGitBuildFetchesInsteadOfCloning(t *testing.T) {
	h := newHarness(t)
	scriptGit(h)

	first := gitBuild(&wisperpb.GitSource{
		RepositoryUrl: "https://github.com/example/site.git",
		Ref:           "main",
	})
	if _, err := h.Builder.Build(context.Background(), first); err != nil {
		t.Fatalf("first build: %v", err)
	}

	// The clone left a repository and a cache behind.
	space, _ := workspaceDir(h.StateDir, "42", "77")
	writeTree(t, filepath.Join(space, checkoutDirectory), map[string]string{
		".git/HEAD":             "ref: refs/heads/main",
		"node_modules/pad/x.js": "cached",
	})

	second := newHarnessSharing(t, h)
	second.Engine.Present[gitImage] = true
	second.Engine.Script = h.Engine.Script

	request := gitBuild(&wisperpb.GitSource{
		RepositoryUrl: "https://github.com/example/site.git",
		Ref:           "main",
	})
	request.BuildId = "78"
	request.UseCache = true
	if _, err := second.Builder.Build(context.Background(), request); err != nil {
		t.Fatalf("second build: %v", err)
	}

	for _, created := range second.Engine.Created {
		if strings.Contains(created.Name, "git-clone") {
			t.Fatal("a cached build cloned from scratch, throwing away the cache it asked to keep")
		}
	}
	fetch := second.Engine.createdNamed(t, "git-fetch")
	argv := append(fetch.Config.Entrypoint, fetch.Config.Cmd...)
	if !containsSequence(argv, "checkout", "--detach", "--force", "FETCH_HEAD") &&
		!containsSequence(argv, "fetch") {
		t.Fatalf("the incremental path did not fetch: %v", argv)
	}
	if got := readTree(t, filepath.Join(second.StateDir, buildsDirectory, "42", "78", checkoutDirectory)); got["node_modules/pad/x.js"] != "cached" {
		t.Fatalf("the cache did not survive the fetch: %v", got)
	}
}

// newHarnessSharing is a second builder over the same state directory, which is what a
// second deployment of the same site is.
func newHarnessSharing(t *testing.T, first *harness) *harness {
	t.Helper()

	second := &harness{
		Engine:   newFakeEngine(),
		Store:    newFakeStore(),
		Uploads:  first.Uploads,
		Sink:     &fakeSink{},
		StateDir: first.StateDir,
		clock:    first.clock,
	}
	releases, err := NewReleases(second.StateDir, testLogger())
	if err != nil {
		t.Fatalf("open the release tree: %v", err)
	}
	second.Releases = releases

	builder, err := New(Options{
		StateDir: second.StateDir,
		Engine:   second.Engine,
		Store:    second.Store,
		Uploads:  second.Uploads,
		Logs:     second.Sink,
		Releases: releases,
		Logger:   testLogger(),
		Now:      second.now,
	})
	if err != nil {
		t.Fatalf("open the builder: %v", err)
	}
	second.Builder = builder
	return second
}
