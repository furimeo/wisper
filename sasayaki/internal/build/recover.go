package build

import (
	"context"
	"errors"
	"fmt"
	"log/slog"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// What to do about a build that was in flight when the machine went down.
//
// Nothing else can answer for it. The panel is holding a command with no result and no way
// to ask, because it cannot reach a node; the customer is looking at a deployment that says
// "building" and will say it forever. The node is the only side that knows the build is not
// still happening, and the only moment it can say so is the next time it starts.
//
// Marked failed rather than restarted. A build the daemon did not see finish may have got
// as far as writing a release directory, and silently running it again would produce a
// second one for the same deployment id - while telling the customer it failed lets them
// deploy again and see it happen. The release the site is serving is untouched either way:
// nothing here moves a symlink.

// FailAbandonedBuilds closes out every build that did not finish, and reports how many it
// found. Called once by the daemon at startup, before the reconcile loop begins.
func (b *Builder) FailAbandonedBuilds(ctx context.Context) (int, error) {
	unfinished, err := b.store.UnfinishedBuilds(ctx)
	if err != nil {
		return 0, fmt.Errorf("build: look for builds that did not finish: %w", err)
	}
	if len(unfinished) == 0 {
		return 0, nil
	}

	at := b.now()
	var failures []error
	for _, run := range unfinished {
		completed := &wisperpb.BuildCompleted{
			BuildId:     run.BuildID,
			Success:     false,
			Commit:      "",
			StartedAt:   timestamppb.New(run.StartedAt),
			FinishedAt:  timestamppb.New(at),
			FailedStage: run.Stage,
			Detail: fmt.Sprintf("the node restarted during the %s stage, so this build was "+
				"abandoned; deploy again to try it", describeStage(run.Stage)),
		}
		if err := b.store.FinishBuild(ctx, run.BuildID, completed, at); err != nil {
			failures = append(failures, err)
			continue
		}

		// Whatever the build had begun to write, minus anything that is live. A half-filled
		// release directory left behind would otherwise sit there until the next build of
		// that workload swept it, and it is indistinguishable from a finished one to
		// anybody reading the directory listing.
		b.discardAbandoned(ctx, run.WorkloadID, run.ReleaseID)

		b.log.Warn("a build was abandoned when the node restarted",
			slog.String("build", run.BuildID),
			slog.String("workload", run.WorkloadID),
			slog.String("stage", describeStage(run.Stage)))
	}

	if len(failures) > 0 {
		return len(unfinished) - len(failures), fmt.Errorf("build: could not close out %d "+
			"abandoned build(s): %w", len(failures), errors.Join(failures...))
	}
	return len(unfinished), nil
}

// discardAbandoned removes the release directory an abandoned build had started, unless it
// is the one being served.
//
// The guard is not theoretical. A build whose release was collected and published, and
// which then died before its row was finished, has a live directory with an unfinished row
// pointing at it - and removing that would take the customer's site off the internet to
// tidy up a database record.
func (b *Builder) discardAbandoned(ctx context.Context, workloadID, releaseID string) {
	if workloadID == "" || releaseID == "" {
		return
	}
	published, err := b.releases.Published(ctx, workloadID)
	if err != nil {
		b.log.Warn("could not check which release is live, so an abandoned build was left alone",
			slog.String("workload", workloadID), slog.String("error", err.Error()))
		return
	}
	if published == releaseID {
		return
	}
	if err := discardRelease(b.stateDir, workloadID, releaseID); err != nil {
		b.log.Warn("could not remove the release directory of an abandoned build",
			slog.String("workload", workloadID),
			slog.String("release", releaseID),
			slog.String("error", err.Error()))
	}
}
