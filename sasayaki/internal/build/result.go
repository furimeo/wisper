package build

import (
	"fmt"
	"strings"
	"time"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// What the panel is told when a build ends.
//
// One message for both outcomes, because the panel has one place to put it and a failure
// that arrived in a different shape would be a second code path on the receiving side. The
// difference is in the fields: a success carries a release id and the panel may then name
// it in a spec, and a failure carries an empty one, which is precisely what stops a broken
// build from being published (build.proto, BuildCompleted.release_id).

// report accumulates what a build found out as it goes, so that a failure three stages in
// still reports the commit it was building and the release it was building into.
type report struct {
	buildID   string
	releaseID string
	startedAt time.Time
	source    source
	image     builtImage
	bytes     int64
	pruned    []string
}

// succeeded is the message for a build that finished.
func (r report) succeeded(at time.Time, detail string) *wisperpb.BuildCompleted {
	return &wisperpb.BuildCompleted{
		BuildId:        r.buildID,
		Success:        true,
		ReleaseId:      r.releaseID,
		ImageRef:       r.image.Ref,
		ImageDigest:    r.image.Digest,
		Commit:         r.source.Commit,
		CommitMessage:  r.source.Message,
		ArtifactBytes:  r.bytes,
		StartedAt:      timestamppb.New(r.startedAt),
		FinishedAt:     timestamppb.New(at),
		FailedStage:    wisperpb.BuildStage_BUILD_STAGE_PUBLISH,
		Detail:         detail,
		PrunedReleases: r.pruned,
	}
}

// failed is the message for a build that did not.
//
// ReleaseId is deliberately empty however far the build got: the panel decides what to
// publish from this field, and a half-filled release directory that was already removed
// must never be nameable.
func (r report) failed(at time.Time, stage wisperpb.BuildStage, exitCode int32, detail string) *wisperpb.BuildCompleted {
	return &wisperpb.BuildCompleted{
		BuildId:       r.buildID,
		Success:       false,
		Commit:        r.source.Commit,
		CommitMessage: r.source.Message,
		StartedAt:     timestamppb.New(r.startedAt),
		FinishedAt:    timestamppb.New(at),
		FailedStage:   stage,
		ExitCode:      exitCode,
		Detail:        trimDetail(detail),
	}
}

// trimDetail keeps the message short enough to sit in a notification.
//
// The whole output is in the log stream, which is where a customer goes to read it. This
// field is the line shown next to the deployment in a list, and an unbounded one there is
// a table that scrolls sideways for a screen and a half.
func trimDetail(detail string) string {
	cleaned := strings.TrimSpace(detail)
	if cleaned == "" {
		return "the build failed and said nothing about why"
	}
	const limit = 500
	if len(cleaned) <= limit {
		return cleaned
	}
	return cleaned[:limit] + "..."
}

// describeStage is what a stage is called in a log line and in a failure detail. The enum's
// own name - BUILD_STAGE_INSTALL - is not what a customer should be reading.
func describeStage(stage wisperpb.BuildStage) string {
	switch stage {
	case wisperpb.BuildStage_BUILD_STAGE_FETCH:
		return "fetch"
	case wisperpb.BuildStage_BUILD_STAGE_INSTALL:
		return "install"
	case wisperpb.BuildStage_BUILD_STAGE_BUILD:
		return "build"
	case wisperpb.BuildStage_BUILD_STAGE_PUBLISH:
		return "publish"
	default:
		return "start"
	}
}

// timedOutDetail is the message for a build the deadline ended, which needs to say what the
// deadline was: "it timed out" without a number is the one failure a customer cannot act
// on.
func timedOutDetail(stage wisperpb.BuildStage, timeout time.Duration) string {
	return fmt.Sprintf("the %s stage ran past the build's %s timeout and was killed",
		describeStage(stage), timeout)
}
