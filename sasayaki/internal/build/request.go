package build

import (
	"fmt"
	"strings"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Reading a StartBuild, and refusing one that cannot be carried out.
//
// Checked here, once, before anything is created on disk or pulled from a registry. A
// request that is missing its build id fails in one line naming the field, rather than
// three stages later as a directory called "" that the prune sweep then has an opinion
// about.

// maxTimeout is the longest a build may run whatever the panel asks for. Two hours is far
// past any honest build and short enough that a request with a nonsense number in it
// cannot hold a slot on this node until somebody notices.
const maxTimeout = 2 * time.Hour

// validate refuses a request this node cannot act on.
func validate(request *wisperpb.StartBuild) error {
	if request == nil {
		return fmt.Errorf("build: the panel sent an empty build request")
	}
	if err := checkIdentifier("build id", request.GetBuildId()); err != nil {
		return err
	}
	if err := checkIdentifier("workload id", request.GetWorkloadId()); err != nil {
		return err
	}

	source := request.GetSource()
	if source.GetGit() == nil && source.GetArchive() == nil {
		return fmt.Errorf("build: build %s names neither a git repository nor an uploaded "+
			"archive, so there is nothing to build", request.GetBuildId())
	}

	plan := request.GetPlan()
	if plan == nil {
		return fmt.Errorf("build: build %s carries no plan", request.GetBuildId())
	}

	switch request.GetOutput() {
	case wisperpb.BuildOutputKind_BUILD_OUTPUT_KIND_STATIC_RELEASE:
		// A builder image is only needed when there is a command to run in it.
		// BUILD_PRESET_STATIC has neither and needs neither.
		if len(plan.GetInstallCommand())+len(plan.GetBuildCommand()) > 0 &&
			strings.TrimSpace(plan.GetBuilderImage()) == "" {
			return fmt.Errorf("build: build %s has commands to run and no image to run them in",
				request.GetBuildId())
		}
	case wisperpb.BuildOutputKind_BUILD_OUTPUT_KIND_CONTAINER_IMAGE:
		// The repository's Dockerfile decides what it builds from, so builder_image is not
		// consulted; dockerfile_path and build_context are, and both are resolved against
		// the checkout in tarstream.go.
	default:
		return fmt.Errorf("build: build %s asks for output kind %s, and this node produces "+
			"either a static release or a container image", request.GetBuildId(), request.GetOutput())
	}
	return nil
}

// timeoutFor is how long the build may take.
//
// A build with no timeout is a build that holds a slot forever, so a request that names
// none gets this package's default rather than no deadline at all - "the panel forgot" and
// "run until the heat death of the universe" must not be the same instruction.
func timeoutFor(request *wisperpb.StartBuild) time.Duration {
	seconds := request.GetTimeoutSeconds()
	if seconds <= 0 {
		return defaultTimeout
	}
	// Compared before the multiplication, not after. A Duration is nanoseconds in an int64,
	// so a field with a nonsense number in it overflows into a negative deadline - and a
	// negative deadline is a context that is already expired, which would fail every build
	// on the node instantly and look like a broken engine.
	if seconds > int64(maxTimeout/time.Second) {
		return maxTimeout
	}
	return time.Duration(seconds) * time.Second
}

// subdirectoryOf is where in the source tree the build happens, for a monorepo. Empty is
// the root, and it is the same field on both kinds of source.
func subdirectoryOf(request *wisperpb.StartBuild) string {
	if git := request.GetSource().GetGit(); git != nil {
		return git.GetSubdirectory()
	}
	return request.GetSource().GetArchive().GetSubdirectory()
}

// quoteCommand renders argv for the build log.
//
// For reading only. Nothing parses it back, and nothing in this package ever builds a
// command by joining strings - the engine is handed the slice, so an argument with a space
// or a backtick in it is one argument and not two (AGENTS.md section 5).
func quoteCommand(argv []string) string {
	parts := make([]string, 0, len(argv))
	for _, argument := range argv {
		if argument == "" || strings.ContainsAny(argument, " \t\"'\\$`") {
			parts = append(parts, fmt.Sprintf("%q", argument))
			continue
		}
		parts = append(parts, argument)
	}
	return strings.Join(parts, " ")
}
