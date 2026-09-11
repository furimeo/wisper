package build

import (
	"archive/zip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// A whole build, from the command to the answer, with a scripted engine.
//
// The case that matters most is the one in the middle: a build that fails must leave the
// site serving exactly what it was serving before, whatever stage it failed at.
//
// Slightly over three hundred lines and kept together: every test here drives the same
// entry point through a different path, and the value of reading them one after another is
// that the paths are visibly the same shape. The recovery tests, which drive a different
// entry point, are in recover_test.go.

// zipOnDisk writes an archive and returns its path and hex digest.
func zipOnDisk(t *testing.T, files map[string]string) (string, string) {
	t.Helper()

	path := filepath.Join(t.TempDir(), "upload.zip")
	file, err := os.Create(path)
	if err != nil {
		t.Fatalf("create the archive: %v", err)
	}
	archive := zip.NewWriter(file)
	for name, contents := range files {
		entry, err := archive.Create(name)
		if err != nil {
			t.Fatalf("add %s: %v", name, err)
		}
		if _, err := entry.Write([]byte(contents)); err != nil {
			t.Fatalf("write %s: %v", name, err)
		}
	}
	if err := archive.Close(); err != nil {
		t.Fatalf("close the archive: %v", err)
	}
	if err := file.Close(); err != nil {
		t.Fatalf("close the archive file: %v", err)
	}

	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read the archive back: %v", err)
	}
	digest := sha256.Sum256(raw)
	return path, hex.EncodeToString(digest[:])
}

// archiveBuild is a StartBuild for a static site from an uploaded zip.
func archiveBuild(session, digest string) *wisperpb.StartBuild {
	return &wisperpb.StartBuild{
		BuildId:    "77",
		WorkloadId: "42",
		Source: &wisperpb.BuildSource{Source: &wisperpb.BuildSource_Archive{
			Archive: &wisperpb.ArchiveSource{UploadSessionId: session, Sha256: digest},
		}},
		Plan: &wisperpb.BuildPlan{
			Preset:          wisperpb.BuildPreset_BUILD_PRESET_STATIC,
			OutputDirectory: "dist",
		},
		Output:         wisperpb.BuildOutputKind_BUILD_OUTPUT_KIND_STATIC_RELEASE,
		TimeoutSeconds: 60,
		LogStreamId:    "stream-1",
	}
}

func TestStaticArchiveBuildProducesAReleaseWithoutPublishingIt(t *testing.T) {
	h := newHarness(t)
	path, digest := zipOnDisk(t, map[string]string{
		"dist/index.html": "<h1>hello</h1>",
		"dist/app.css":    "body{}",
		"readme.md":       "not part of the site",
	})
	h.Uploads.files["session-1"] = path

	completed, err := h.Builder.Build(context.Background(), archiveBuild("session-1", digest))
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	if !completed.GetSuccess() {
		t.Fatalf("expected the build to succeed, got %q", completed.GetDetail())
	}
	if completed.GetReleaseId() != "77" {
		t.Fatalf("expected release 77, got %q", completed.GetReleaseId())
	}

	directory, _ := releaseDir(h.StateDir, "42", "77")
	served := readTree(t, directory)
	want := map[string]string{"index.html": "<h1>hello</h1>", "app.css": "body{}"}
	if !reflect.DeepEqual(served, want) {
		t.Fatalf("the release does not hold the output directory: %v", served)
	}
	if completed.GetArtifactBytes() == 0 {
		t.Fatal("the release was reported as zero bytes")
	}

	// Publishing is the reconcile loop's move, not the build's.
	published, err := h.Releases.Published(context.Background(), "42")
	if err != nil {
		t.Fatalf("read what is published: %v", err)
	}
	if published != "" {
		t.Fatalf("the build published release %q; only a spec naming it may do that", published)
	}
}

func TestBuildRunsTheInstallAndBuildCommandsInOrder(t *testing.T) {
	h := newHarness(t)
	path, digest := zipOnDisk(t, map[string]string{"package.json": "{}"})
	h.Uploads.files["session-1"] = path

	h.Engine.Present["node:22-alpine"] = true
	h.Engine.Script = func(options client.ContainerCreateOptions) fakeRun {
		if strings.HasSuffix(options.Name, "-build") {
			return fakeRun{
				Stdout: "compiled\n",
				Do: func(hostPath string) error {
					return os.WriteFile(
						filepath.Join(hostPath, checkoutDirectory, "index.html"),
						[]byte("generated"), 0o644)
				},
			}
		}
		return fakeRun{Stdout: "installed\n"}
	}

	request := archiveBuild("session-1", digest)
	request.Plan = &wisperpb.BuildPlan{
		Preset:         wisperpb.BuildPreset_BUILD_PRESET_NODE,
		BuilderImage:   "node:22-alpine",
		InstallCommand: []string{"npm", "ci"},
		BuildCommand:   []string{"npm", "run", "build"},
		// Empty: the scripted build writes into the checkout root.
		OutputDirectory: "",
	}
	request.BuildEnv = []*wisperpb.EnvVar{{Name: "NODE_ENV", Value: "production"}}
	request.Limits = &wisperpb.ResourceLimits{
		NanoCpus: 1_500_000_000, MemoryBytes: 512 << 20, PidsLimit: 512, NofileLimit: 4096,
	}

	completed, err := h.Builder.Build(context.Background(), request)
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	if !completed.GetSuccess() {
		t.Fatalf("expected success, got %q", completed.GetDetail())
	}

	install := h.Engine.createdNamed(t, "-install")
	if !reflect.DeepEqual(install.Config.Entrypoint, []string{"npm"}) ||
		!reflect.DeepEqual(install.Config.Cmd, []string{"ci"}) {
		t.Fatalf("the install command was not passed as argv: %v %v",
			install.Config.Entrypoint, install.Config.Cmd)
	}
	if !contains(install.Config.Env, "NODE_ENV=production") {
		t.Fatalf("the build environment did not reach the container: %v", install.Config.Env)
	}
	if install.HostConfig.NanoCPUs != 1_500_000_000 {
		t.Fatalf("expected a nano-CPU ceiling of 1500000000, got %d", install.HostConfig.NanoCPUs)
	}
	if install.HostConfig.CPUShares != 0 {
		t.Fatal("CPUShares was set; it is a relative weight, not a ceiling, and setting it " +
			"is the mistake the predecessor made")
	}

	directory, _ := releaseDir(h.StateDir, "42", "77")
	if served := readTree(t, directory); served["index.html"] != "generated" {
		t.Fatalf("the release does not hold what the build wrote: %v", served)
	}
	if output := h.Sink.text(); !strings.Contains(output, "installed") || !strings.Contains(output, "compiled") {
		t.Fatalf("the customer did not see the build output: %q", output)
	}
}

func TestAFailedBuildLeavesTheLiveSiteAlone(t *testing.T) {
	requireSymlinkSwap(t)
	h := newHarness(t)
	ctx := context.Background()

	live := map[string]string{"index.html": "<h1>the site people are looking at</h1>"}
	h.seedRelease(t, "42", "70", live)
	if err := h.Releases.Publish(ctx, "42", "70"); err != nil {
		t.Fatalf("publish the release that is live: %v", err)
	}

	path, digest := zipOnDisk(t, map[string]string{"package.json": "{}"})
	h.Uploads.files["session-1"] = path
	h.Engine.Present["node:22-alpine"] = true
	h.Engine.Script = func(client.ContainerCreateOptions) fakeRun {
		return fakeRun{Stderr: "error: cannot find module 'left-pad'\n", Exit: 1}
	}

	request := archiveBuild("session-1", digest)
	request.Plan = &wisperpb.BuildPlan{
		BuilderImage:   "node:22-alpine",
		InstallCommand: []string{"npm", "ci"},
	}

	completed, err := h.Builder.Build(ctx, request)
	if err != nil {
		t.Fatalf("a build that fails is a result, not an error: %v", err)
	}
	if completed.GetSuccess() {
		t.Fatal("expected the build to fail")
	}
	if completed.GetFailedStage() != wisperpb.BuildStage_BUILD_STAGE_INSTALL {
		t.Fatalf("expected the failure at the install stage, got %s", completed.GetFailedStage())
	}
	if completed.GetExitCode() != 1 {
		t.Fatalf("expected exit code 1, got %d", completed.GetExitCode())
	}
	if completed.GetReleaseId() != "" {
		t.Fatalf("a failed build named release %q; the panel could publish it",
			completed.GetReleaseId())
	}

	// The whole point.
	published, err := h.Releases.Published(ctx, "42")
	if err != nil {
		t.Fatalf("read what is published: %v", err)
	}
	if published != "70" {
		t.Fatalf("the live release changed to %q", published)
	}
	link, _ := currentLink(h.StateDir, "42")
	if served := readTree(t, link); !reflect.DeepEqual(served, live) {
		t.Fatalf("what the site serves changed: %v", served)
	}

	// And nothing half-written is left where a spec could name it.
	if directory, _ := releaseDir(h.StateDir, "42", "77"); directoryExists(directory) {
		t.Fatal("the failed build left a release directory behind")
	}
	if output := h.Sink.text(); !strings.Contains(output, "left-pad") {
		t.Fatalf("the customer was not shown why it failed: %q", output)
	}
}

func TestABuildThatOutlivesItsDeadlineIsKilledAndReported(t *testing.T) {
	h := newHarness(t)
	path, digest := zipOnDisk(t, map[string]string{"package.json": "{}"})
	h.Uploads.files["session-1"] = path

	h.Engine.Present["node:22-alpine"] = true
	h.Engine.Script = func(client.ContainerCreateOptions) fakeRun {
		return fakeRun{Hang: true}
	}

	request := archiveBuild("session-1", digest)
	request.TimeoutSeconds = 1
	request.Plan = &wisperpb.BuildPlan{
		BuilderImage: "node:22-alpine",
		BuildCommand: []string{"sleep", "forever"},
	}

	completed, err := h.Builder.Build(context.Background(), request)
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	if completed.GetSuccess() {
		t.Fatal("expected the build to fail")
	}
	if !strings.Contains(completed.GetDetail(), "timeout") {
		t.Fatalf("the failure does not say it timed out: %q", completed.GetDetail())
	}
	if completed.GetExitCode() != 137 {
		t.Fatalf("expected 137 for a killed process, got %d", completed.GetExitCode())
	}
	if len(h.Engine.Removed) == 0 {
		t.Fatal("the container that ran past the deadline was not removed")
	}
}

func TestASecondCommandForTheSameBuildReplaysTheFirstAnswer(t *testing.T) {
	h := newHarness(t)
	path, digest := zipOnDisk(t, map[string]string{"dist/index.html": "hello"})
	h.Uploads.files["session-1"] = path

	first, err := h.Builder.Build(context.Background(), archiveBuild("session-1", digest))
	if err != nil {
		t.Fatalf("first build: %v", err)
	}
	created := len(h.Engine.Created)

	// The control stream dropped before the panel got the result, so it asks again.
	second, err := h.Builder.Build(context.Background(), archiveBuild("session-1", digest))
	if err != nil {
		t.Fatalf("second build: %v", err)
	}
	if second.GetReleaseId() != first.GetReleaseId() || !second.GetSuccess() {
		t.Fatalf("the replay is not the original answer: %v", second)
	}
	if len(h.Engine.Created) != created {
		t.Fatal("the resent command rebuilt instead of replaying what was already on disk")
	}
}

func TestBuildRefusesARequestItCannotCarryOut(t *testing.T) {
	h := newHarness(t)
	ctx := context.Background()

	broken := map[string]*wisperpb.StartBuild{
		"no build id":    {WorkloadId: "42", Plan: &wisperpb.BuildPlan{}},
		"no workload":    {BuildId: "77", Plan: &wisperpb.BuildPlan{}},
		"no source":      {BuildId: "77", WorkloadId: "42", Plan: &wisperpb.BuildPlan{}},
		"escaping id":    {BuildId: "../77", WorkloadId: "42", Plan: &wisperpb.BuildPlan{}},
		"nil altogether": nil,
	}
	for name, request := range broken {
		if _, err := h.Builder.Build(ctx, request); err == nil {
			t.Fatalf("%s was accepted", name)
		}
	}
}

func directoryExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
}

func contains(values []string, want string) bool {
	for _, value := range values {
		if value == want {
			return true
		}
	}
	return false
}
