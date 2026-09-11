package build

import (
	"strings"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Reading a StartBuild: what is refused before anything is created, and what a missing
// timeout becomes.

func TestValidateAcceptsTheTwoShapesTheNodeProduces(t *testing.T) {
	static := &wisperpb.StartBuild{
		BuildId:    "77",
		WorkloadId: "42",
		Source: &wisperpb.BuildSource{Source: &wisperpb.BuildSource_Git{
			Git: &wisperpb.GitSource{RepositoryUrl: "https://example.com/x.git"},
		}},
		// BUILD_PRESET_STATIC: no commands, so no builder image is needed.
		Plan:   &wisperpb.BuildPlan{Preset: wisperpb.BuildPreset_BUILD_PRESET_STATIC},
		Output: wisperpb.BuildOutputKind_BUILD_OUTPUT_KIND_STATIC_RELEASE,
	}
	if err := validate(static); err != nil {
		t.Fatalf("a static build was refused: %v", err)
	}

	dockerfile := &wisperpb.StartBuild{
		BuildId:    "77",
		WorkloadId: "42",
		Source: &wisperpb.BuildSource{Source: &wisperpb.BuildSource_Archive{
			Archive: &wisperpb.ArchiveSource{UploadSessionId: "s"},
		}},
		Plan:   &wisperpb.BuildPlan{Preset: wisperpb.BuildPreset_BUILD_PRESET_DOCKERFILE},
		Output: wisperpb.BuildOutputKind_BUILD_OUTPUT_KIND_CONTAINER_IMAGE,
	}
	if err := validate(dockerfile); err != nil {
		t.Fatalf("a Dockerfile build was refused: %v", err)
	}
}

func TestValidateRefusesCommandsWithNothingToRunThemIn(t *testing.T) {
	request := &wisperpb.StartBuild{
		BuildId:    "77",
		WorkloadId: "42",
		Source: &wisperpb.BuildSource{Source: &wisperpb.BuildSource_Git{
			Git: &wisperpb.GitSource{RepositoryUrl: "https://example.com/x.git"},
		}},
		Plan: &wisperpb.BuildPlan{
			Preset:       wisperpb.BuildPreset_BUILD_PRESET_NODE,
			BuildCommand: []string{"npm", "run", "build"},
		},
		Output: wisperpb.BuildOutputKind_BUILD_OUTPUT_KIND_STATIC_RELEASE,
	}
	err := validate(request)
	if err == nil {
		t.Fatal("a plan with commands and no builder image was accepted")
	}
	if !strings.Contains(err.Error(), "image to run them in") {
		t.Fatalf("the message does not say what is missing: %v", err)
	}
}

func TestValidateRefusesAnOutputKindThisNodeDoesNotProduce(t *testing.T) {
	request := &wisperpb.StartBuild{
		BuildId:    "77",
		WorkloadId: "42",
		Source: &wisperpb.BuildSource{Source: &wisperpb.BuildSource_Archive{
			Archive: &wisperpb.ArchiveSource{UploadSessionId: "s"},
		}},
		Plan:   &wisperpb.BuildPlan{},
		Output: wisperpb.BuildOutputKind_BUILD_OUTPUT_KIND_UNSPECIFIED,
	}
	if err := validate(request); err == nil {
		t.Fatal("an unspecified output kind was accepted")
	}
}

func TestTimeoutForNeverMeansForever(t *testing.T) {
	if got := timeoutFor(&wisperpb.StartBuild{}); got != defaultTimeout {
		t.Fatalf("a request with no timeout became %s", got)
	}
	if got := timeoutFor(&wisperpb.StartBuild{TimeoutSeconds: -1}); got != defaultTimeout {
		t.Fatalf("a negative timeout became %s", got)
	}
	if got := timeoutFor(&wisperpb.StartBuild{TimeoutSeconds: 90}); got != 90*time.Second {
		t.Fatalf("ninety seconds became %s", got)
	}
	if got := timeoutFor(&wisperpb.StartBuild{TimeoutSeconds: 1 << 40}); got != maxTimeout {
		t.Fatalf("a nonsense timeout became %s rather than being capped at %s", got, maxTimeout)
	}
}

func TestSubdirectoryComesFromWhicheverSourceThereIs(t *testing.T) {
	git := &wisperpb.StartBuild{Source: &wisperpb.BuildSource{Source: &wisperpb.BuildSource_Git{
		Git: &wisperpb.GitSource{Subdirectory: "apps/web"},
	}}}
	if got := subdirectoryOf(git); got != "apps/web" {
		t.Fatalf("the git subdirectory is %q", got)
	}

	archive := &wisperpb.StartBuild{Source: &wisperpb.BuildSource{Source: &wisperpb.BuildSource_Archive{
		Archive: &wisperpb.ArchiveSource{Subdirectory: "site"},
	}}}
	if got := subdirectoryOf(archive); got != "site" {
		t.Fatalf("the archive subdirectory is %q", got)
	}

	if got := subdirectoryOf(&wisperpb.StartBuild{}); got != "" {
		t.Fatalf("a request with no source produced the subdirectory %q", got)
	}
}

func TestQuoteCommandIsForReadingOnly(t *testing.T) {
	// The engine gets the slice; this string only ever appears in the build log. It is
	// still quoted, so a customer reading `$ sh -c "rm -rf /"` can see it was one argument.
	got := quoteCommand([]string{"npm", "run", "build --mode production"})
	if got != `npm run "build --mode production"` {
		t.Fatalf("the command rendered as %q", got)
	}
}

func TestTrimDetailKeepsTheMessageShortAndNeverEmpty(t *testing.T) {
	if got := trimDetail("   "); got == "" {
		t.Fatal("an empty detail stayed empty, so the panel shows a failure with no reason")
	}
	long := strings.Repeat("x", 2000)
	if got := trimDetail(long); len(got) > 520 {
		t.Fatalf("a %d-character detail survived", len(got))
	}
}

func TestDescribeStageIsReadable(t *testing.T) {
	stages := map[wisperpb.BuildStage]string{
		wisperpb.BuildStage_BUILD_STAGE_FETCH:   "fetch",
		wisperpb.BuildStage_BUILD_STAGE_INSTALL: "install",
		wisperpb.BuildStage_BUILD_STAGE_BUILD:   "build",
		wisperpb.BuildStage_BUILD_STAGE_PUBLISH: "publish",
	}
	for stage, want := range stages {
		if got := describeStage(stage); got != want {
			t.Fatalf("%s reads as %q", stage, got)
		}
	}
}
