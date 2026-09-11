package build

import (
	"archive/tar"
	"context"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"testing"

	"github.com/moby/moby/api/types/build"
	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Building an image from the repository's own Dockerfile: the context that is sent, the
// ceilings that are applied, and the failure the engine reports inside a 200 response.

func dockerfileBuild() *wisperpb.StartBuild {
	return &wisperpb.StartBuild{
		BuildId:    "77",
		WorkloadId: "42",
		Source: &wisperpb.BuildSource{Source: &wisperpb.BuildSource_Archive{
			Archive: &wisperpb.ArchiveSource{UploadSessionId: "session-1"},
		}},
		Plan: &wisperpb.BuildPlan{
			Preset: wisperpb.BuildPreset_BUILD_PRESET_DOCKERFILE,
		},
		Output:         wisperpb.BuildOutputKind_BUILD_OUTPUT_KIND_CONTAINER_IMAGE,
		TimeoutSeconds: 120,
		LogStreamId:    "stream-1",
		Limits: &wisperpb.ResourceLimits{
			NanoCpus: 2_000_000_000, MemoryBytes: 1 << 30, NofileLimit: 8192,
		},
		BuildEnv: []*wisperpb.EnvVar{{Name: "VERSION", Value: "1.2.3"}},
	}
}

func TestDockerfileBuildTagsTheImageWithTheReleaseAndReportsItsDigest(t *testing.T) {
	h := newHarness(t)
	path, digest := zipOnDisk(t, map[string]string{
		"Dockerfile": "FROM scratch\nCOPY . /app\n",
		"main.go":    "package main",
	})
	h.Uploads.files["session-1"] = path

	var sent []string
	h.Engine.BuildScript = func(_ client.ImageBuildOptions, source io.Reader) (string, string) {
		sent = tarNames(t, source)
		return "Step 1/2 : FROM scratch\nSuccessfully built deadbeef\n", ""
	}

	request := dockerfileBuild()
	request.Source.GetArchive().Sha256 = digest

	completed, err := h.Builder.Build(context.Background(), request)
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	if !completed.GetSuccess() {
		t.Fatalf("expected success, got %q", completed.GetDetail())
	}
	if completed.GetImageRef() != "wisper/42:77" {
		t.Fatalf("the image was tagged %q", completed.GetImageRef())
	}
	if completed.GetImageDigest() == "" {
		t.Fatal("no digest was reported, so the panel cannot pin what runs to what was built")
	}
	if completed.GetReleaseId() != "77" {
		t.Fatalf("expected release 77, got %q", completed.GetReleaseId())
	}

	sort.Strings(sent)
	if !reflect.DeepEqual(sent, []string{"Dockerfile", "main.go"}) {
		t.Fatalf("the build context is not the checkout: %v", sent)
	}

	options := h.Engine.Builds[0]
	if options.Memory != 1<<30 {
		t.Fatalf("the memory ceiling did not reach the builder: %d", options.Memory)
	}
	if options.CPUQuota != 200_000 || options.CPUPeriod != 100_000 {
		t.Fatalf("two nano-CPUs became quota %d over period %d", options.CPUQuota, options.CPUPeriod)
	}
	if options.Version != build.BuilderV1 {
		t.Fatalf("the classic builder was not selected: %q", options.Version)
	}
	if !options.Remove || !options.ForceRemove {
		t.Fatal("intermediate containers were left on the node")
	}
	if arg, present := options.BuildArgs["VERSION"]; !present || arg == nil || *arg != "1.2.3" {
		t.Fatalf("the build arguments did not reach the builder: %v", options.BuildArgs)
	}
	if !strings.Contains(h.Sink.text(), "Step 1/2") {
		t.Fatalf("the customer did not see the build output: %q", h.Sink.text())
	}
}

// The engine reports a broken RUN step inside the stream, with an HTTP 200 around the whole
// response. A reader that only checked the status code would call every broken Dockerfile
// a success.
func TestDockerfileBuildFailsOnAnErrorInsideTheStream(t *testing.T) {
	h := newHarness(t)
	path, digest := zipOnDisk(t, map[string]string{"Dockerfile": "FROM scratch\nRUN false\n"})
	h.Uploads.files["session-1"] = path

	h.Engine.BuildScript = func(_ client.ImageBuildOptions, source io.Reader) (string, string) {
		_, _ = io.Copy(io.Discard, source)
		return "Step 2/2 : RUN false\n", "The command '/bin/sh -c false' returned a non-zero code: 1"
	}

	request := dockerfileBuild()
	request.Source.GetArchive().Sha256 = digest

	completed, err := h.Builder.Build(context.Background(), request)
	if err != nil {
		t.Fatalf("a Dockerfile that fails is a result, not an error: %v", err)
	}
	if completed.GetSuccess() {
		t.Fatal("a build whose RUN step failed was reported as a success")
	}
	if completed.GetFailedStage() != wisperpb.BuildStage_BUILD_STAGE_BUILD {
		t.Fatalf("expected the failure at the build stage, got %s", completed.GetFailedStage())
	}
	if !strings.Contains(completed.GetDetail(), "non-zero code") {
		t.Fatalf("the failure does not say what the engine said: %q", completed.GetDetail())
	}
	if completed.GetImageRef() != "" {
		t.Fatalf("a failed build named an image: %q", completed.GetImageRef())
	}
}

func TestDockerfileBuildHonoursTheDockerignore(t *testing.T) {
	h := newHarness(t)
	path, digest := zipOnDisk(t, map[string]string{
		"Dockerfile":                 "FROM scratch\n",
		".dockerignore":              "node_modules\n*.log\n!keep.log\ndocs/**/*.tmp\n",
		"main.go":                    "package main",
		"node_modules/left-pad/x.js": "huge",
		"debug.log":                  "noise",
		"keep.log":                   "wanted",
		"docs/a/b.tmp":               "scratch",
		"docs/a/b.md":                "readme",
	})
	h.Uploads.files["session-1"] = path

	var sent []string
	h.Engine.BuildScript = func(_ client.ImageBuildOptions, source io.Reader) (string, string) {
		sent = tarNames(t, source)
		return "built\n", ""
	}

	request := dockerfileBuild()
	request.Source.GetArchive().Sha256 = digest
	if _, err := h.Builder.Build(context.Background(), request); err != nil {
		t.Fatalf("build: %v", err)
	}

	sort.Strings(sent)
	want := []string{".dockerignore", "Dockerfile", "docs/a/b.md", "keep.log", "main.go"}
	if !reflect.DeepEqual(sent, want) {
		t.Fatalf("the context is not what .dockerignore asked for:\n got %v\nwant %v", sent, want)
	}
}

func TestDockerfileNameStaysInsideTheContext(t *testing.T) {
	root := t.TempDir()

	name, err := dockerfileName(root, "")
	if err != nil || name != "Dockerfile" {
		t.Fatalf("the default is not Dockerfile: %q %v", name, err)
	}
	name, err = dockerfileName(root, "docker/prod.Dockerfile")
	if err != nil || name != "docker/prod.Dockerfile" {
		t.Fatalf("a nested dockerfile was mangled: %q %v", name, err)
	}
	for _, candidate := range []string{"../Dockerfile", "/etc/Dockerfile", "a/../../Dockerfile"} {
		if _, err := dockerfileName(root, candidate); err == nil {
			t.Fatalf("%q was accepted; the engine would read something it was not sent", candidate)
		}
	}
}

func TestTarStreamRefusesALinkOutOfTheContext(t *testing.T) {
	requireSymlinkSwap(t)
	root := t.TempDir()
	writeTree(t, root, map[string]string{"Dockerfile": "FROM scratch\n"})
	if err := os.Symlink(filepath.FromSlash("../../etc"), filepath.Join(root, "escape")); err != nil {
		t.Fatalf("create the link: %v", err)
	}

	stream := tarDirectory(context.Background(), root, &ignoreList{})
	defer stream.Close()

	reader := tar.NewReader(stream)
	var failure error
	for {
		_, err := reader.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			failure = err
			break
		}
	}
	if failure == nil {
		t.Fatal("a link out of the context was streamed to the engine")
	}
}

func TestReleaseTagRefusesAnIdentifierThatIsNotOne(t *testing.T) {
	if _, err := releaseTag("42", "77"); err != nil {
		t.Fatalf("a plain pair was refused: %v", err)
	}
	for _, attempt := range [][2]string{{"../42", "77"}, {"42", "la:test"}, {"", "77"}, {"42", ""}} {
		if _, err := releaseTag(attempt[0], attempt[1]); err == nil {
			t.Fatalf("workload %q release %q was accepted", attempt[0], attempt[1])
		}
	}
}

func TestCpuQuotaForKeepsTheCeilingMeaningful(t *testing.T) {
	quota, period := cpuQuotaFor(1_000_000_000)
	if quota != 100_000 || period != 100_000 {
		t.Fatalf("one core became quota %d over period %d", quota, period)
	}
	quota, period = cpuQuotaFor(500_000_000)
	if quota != 50_000 || period != 100_000 {
		t.Fatalf("half a core became quota %d over period %d", quota, period)
	}
	if quota, period := cpuQuotaFor(0); quota != 0 || period != 0 {
		t.Fatalf("no ceiling became quota %d over period %d", quota, period)
	}
	// The kernel's floor: below it the engine refuses the value and the build never starts.
	if quota, _ := cpuQuotaFor(1); quota < 1000 {
		t.Fatalf("a tiny ceiling became an unusable quota of %d", quota)
	}
}

// tarNames is every regular file in a tar stream.
func tarNames(t *testing.T, source io.Reader) []string {
	t.Helper()
	var names []string
	reader := tar.NewReader(source)
	for {
		header, err := reader.Next()
		if err == io.EOF {
			return names
		}
		if err != nil {
			t.Fatalf("read the build context: %v", err)
		}
		if header.Typeflag == tar.TypeReg {
			names = append(names, header.Name)
		}
	}
}
