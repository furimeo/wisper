package build

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The clone: what argv git is given, where the token goes, and which remotes are refused.

func gitBuild(git *wisperpb.GitSource) *wisperpb.StartBuild {
	return &wisperpb.StartBuild{
		BuildId:    "77",
		WorkloadId: "42",
		Source:     &wisperpb.BuildSource{Source: &wisperpb.BuildSource_Git{Git: git}},
		Plan: &wisperpb.BuildPlan{
			Preset:          wisperpb.BuildPreset_BUILD_PRESET_STATIC,
			OutputDirectory: "public",
		},
		Output:         wisperpb.BuildOutputKind_BUILD_OUTPUT_KIND_STATIC_RELEASE,
		TimeoutSeconds: 60,
		LogStreamId:    "stream-1",
	}
}

// scriptGit makes every git container succeed, with the clone writing a site and the
// describe step answering with a commit.
func scriptGit(h *harness) {
	h.Engine.Present[gitImage] = true
	h.Engine.Script = func(options client.ContainerCreateOptions) fakeRun {
		if strings.HasSuffix(options.Name, "-describe") {
			return fakeRun{Stdout: "abc123def456\nfix the header\n"}
		}
		if strings.Contains(options.Name, "git-clone") {
			return fakeRun{
				Stdout: "Cloning into '.'...\n",
				Do: func(hostPath string) error {
					target := filepath.Join(hostPath, checkoutDirectory, "public")
					if err := os.MkdirAll(target, 0o755); err != nil {
						return err
					}
					return os.WriteFile(filepath.Join(target, "index.html"), []byte("cloned"), 0o644)
				},
			}
		}
		return fakeRun{}
	}
}

func TestGitBuildClonesAtTheRefAndReportsTheCommit(t *testing.T) {
	h := newHarness(t)
	scriptGit(h)

	completed, err := h.Builder.Build(context.Background(), gitBuild(&wisperpb.GitSource{
		RepositoryUrl: "https://github.com/example/site.git",
		Ref:           "main",
		Depth:         1,
	}))
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	if !completed.GetSuccess() {
		t.Fatalf("expected success, got %q", completed.GetDetail())
	}
	if completed.GetCommit() != "abc123def456" || completed.GetCommitMessage() != "fix the header" {
		t.Fatalf("the commit was not reported: %q / %q",
			completed.GetCommit(), completed.GetCommitMessage())
	}

	clone := h.Engine.createdNamed(t, "git-clone")
	argv := append(clone.Config.Entrypoint, clone.Config.Cmd...)
	if argv[0] != "git" {
		t.Fatalf("the clone did not run git: %v", argv)
	}
	if !containsSequence(argv, "--branch", "main") {
		t.Fatalf("the ref did not reach the clone: %v", argv)
	}
	if !containsSequence(argv, "--depth", "1") {
		t.Fatalf("the clone was not shallow: %v", argv)
	}
	if clone.Config.Image != gitImage {
		t.Fatalf("the clone ran in %q rather than the pinned git image", clone.Config.Image)
	}
}

func TestGitBuildKeepsTheTokenOutOfArgv(t *testing.T) {
	h := newHarness(t)
	scriptGit(h)

	const token = "ghp_averysecretdeploytoken"
	_, err := h.Builder.Build(context.Background(), gitBuild(&wisperpb.GitSource{
		RepositoryUrl: "https://github.com/example/private.git",
		Ref:           "main",
		AccessToken:   token,
	}))
	if err != nil {
		t.Fatalf("build: %v", err)
	}

	for _, created := range h.Engine.Created {
		for _, argument := range append(created.Config.Entrypoint, created.Config.Cmd...) {
			if strings.Contains(argument, token) {
				t.Fatalf("the token is on argv, where every user on the machine can read it "+
					"through ps: %q", argument)
			}
		}
	}

	clone := h.Engine.createdNamed(t, "git-clone")
	if !contains(clone.Config.Env, gitTokenVariable+"="+token) {
		t.Fatalf("the token did not reach git at all: %v", clone.Config.Env)
	}
	if !contains(clone.Config.Env, "GIT_TERMINAL_PROMPT=0") {
		t.Fatal("git was left able to block on a credential prompt nobody can answer")
	}

	// And it is never written down: the log the customer sees, and the panel's record.
	if strings.Contains(h.Sink.text(), token) {
		t.Fatal("the token was written into the build log")
	}
}

func TestGitBuildChecksOutThePinnedCommit(t *testing.T) {
	h := newHarness(t)
	scriptGit(h)

	_, err := h.Builder.Build(context.Background(), gitBuild(&wisperpb.GitSource{
		RepositoryUrl: "https://github.com/example/site.git",
		Ref:           "main",
		Commit:        "abc123def456",
	}))
	if err != nil {
		t.Fatalf("build: %v", err)
	}

	checkout := h.Engine.createdNamed(t, "git-checkout")
	argv := append(checkout.Config.Entrypoint, checkout.Config.Cmd...)
	if !containsSequence(argv, "checkout", "--detach", "--force", "abc123def456") {
		t.Fatalf("the pinned commit was not checked out: %v", argv)
	}
}

func TestGitBuildUpdatesSubmodulesOnlyWhenAsked(t *testing.T) {
	h := newHarness(t)
	scriptGit(h)

	if _, err := h.Builder.Build(context.Background(), gitBuild(&wisperpb.GitSource{
		RepositoryUrl: "https://github.com/example/site.git",
		Ref:           "main",
	})); err != nil {
		t.Fatalf("build: %v", err)
	}
	for _, created := range h.Engine.Created {
		if strings.Contains(created.Name, "submodule") {
			t.Fatal("submodules were updated for a build that did not ask")
		}
	}

	second := newHarness(t)
	scriptGit(second)
	if _, err := second.Builder.Build(context.Background(), gitBuild(&wisperpb.GitSource{
		RepositoryUrl: "https://github.com/example/site.git",
		Ref:           "main",
		Submodules:    true,
	})); err != nil {
		t.Fatalf("build with submodules: %v", err)
	}
	argv := second.Engine.createdNamed(t, "git-submodule")
	joined := append(argv.Config.Entrypoint, argv.Config.Cmd...)
	if !containsSequence(joined, "submodule", "update", "--init", "--recursive") {
		t.Fatalf("submodules were not updated: %v", joined)
	}
}

func TestCheckRepositoryURLRefusesEverythingButHTTPS(t *testing.T) {
	refused := []string{
		"",
		"git@github.com:example/site.git",
		"ssh://git@github.com/example/site.git",
		"file:///var/lib/wisper",
		"http://github.com/example/site.git",
		"/etc/passwd",
		"https://user:password@github.com/example/site.git",
		"https:///no-host",
	}
	for _, candidate := range refused {
		if _, err := checkRepositoryURL(candidate); err == nil {
			t.Fatalf("%q was accepted as a repository", candidate)
		}
	}

	accepted, err := checkRepositoryURL("  https://github.com/example/site.git  ")
	if err != nil {
		t.Fatalf("a plain https remote was refused: %v", err)
	}
	if accepted != "https://github.com/example/site.git" {
		t.Fatalf("the URL came back as %q", accepted)
	}
}

func TestGitBuildFailsWhenTheCloneFails(t *testing.T) {
	h := newHarness(t)
	h.Engine.Present[gitImage] = true
	h.Engine.Script = func(client.ContainerCreateOptions) fakeRun {
		return fakeRun{Stderr: "fatal: repository not found\n", Exit: 128}
	}

	completed, err := h.Builder.Build(context.Background(), gitBuild(&wisperpb.GitSource{
		RepositoryUrl: "https://github.com/example/missing.git",
		Ref:           "main",
	}))
	if err != nil {
		t.Fatalf("a clone that fails is a result, not an error: %v", err)
	}
	if completed.GetSuccess() {
		t.Fatal("expected the build to fail")
	}
	if completed.GetFailedStage() != wisperpb.BuildStage_BUILD_STAGE_FETCH {
		t.Fatalf("expected the failure at the fetch stage, got %s", completed.GetFailedStage())
	}
	if !strings.Contains(h.Sink.text(), "repository not found") {
		t.Fatalf("the customer was not shown why: %q", h.Sink.text())
	}
}

func TestGitImageIsPulledWhenItIsNotOnTheNode(t *testing.T) {
	h := newHarness(t)
	h.Engine.Script = func(options client.ContainerCreateOptions) fakeRun {
		if strings.HasSuffix(options.Name, "-describe") {
			return fakeRun{Stdout: "abc123\nfirst commit\n"}
		}
		if strings.Contains(options.Name, "git-clone") {
			return fakeRun{Do: func(hostPath string) error {
				target := filepath.Join(hostPath, checkoutDirectory, "public")
				if err := os.MkdirAll(target, 0o755); err != nil {
					return err
				}
				return os.WriteFile(filepath.Join(target, "index.html"), []byte("x"), 0o644)
			}}
		}
		return fakeRun{}
	}

	if _, err := h.Builder.Build(context.Background(), gitBuild(&wisperpb.GitSource{
		RepositoryUrl: "https://github.com/example/site.git",
		Ref:           "main",
	})); err != nil {
		t.Fatalf("build: %v", err)
	}
	if !contains(h.Engine.Pulled, gitImage) {
		t.Fatalf("the git image was not pulled: %v", h.Engine.Pulled)
	}
}

// containsSequence reports whether want appears consecutively in argv.
func containsSequence(argv []string, want ...string) bool {
	for start := 0; start+len(want) <= len(argv); start++ {
		matched := true
		for offset, value := range want {
			if argv[start+offset] != value {
				matched = false
				break
			}
		}
		if matched {
			return true
		}
	}
	return false
}
