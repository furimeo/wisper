package build

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Keeping the last N of things, which is the only reason a node that deploys on every push
// does not fill its disk in a fortnight.
//
// Two policies, both from the spec's RetentionPolicy and both applied here rather than by
// the panel: the node is the only side that knows how much disk is left, and a panel that
// pruned by row count would delete a directory the node still has open.
//
// The rule that outranks the count: **the release being served is never removed**, however
// old it is. A site that has not been deployed for a year is still a site, and honouring
// keep_releases literally would take it off the internet.

const (
	// keepReleasesFloor is used when the panel has not said. Three is enough for the
	// rollback a customer reaches for, which is almost always the release before last.
	keepReleasesFloor = 3
	// keepWorkspacesFloor is used when the panel has not said. Two: the one a cached build
	// reuses, and the one before it, which is what an operator looks at after a build that
	// failed strangely.
	keepWorkspacesFloor = 2
)

// aged is one directory and when it was last written, which is the order releases and
// workspaces are kept in.
type aged struct {
	name string
	at   time.Time
}

// pruneReleases removes the oldest releases beyond keep, never touching the published one
// or the one just built, and reports what it deleted.
//
// The names come back so BuildCompleted.pruned_releases can carry them: the panel drops the
// rows for releases that can no longer be rolled back to, and a customer offered a rollback
// button that leads to an empty directory is worse than not being offered one.
func pruneReleases(stateDir, workloadID string, keep int, protected ...string) ([]string, error) {
	if keep < 1 {
		keep = 1
	}
	root, err := releaseRoot(stateDir, workloadID)
	if err != nil {
		return nil, err
	}

	entries, err := listAged(root)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("build: list the releases of site %s: %w", workloadID, err)
	}

	spared := make(map[string]struct{}, len(protected))
	for _, name := range protected {
		if name != "" {
			spared[name] = struct{}{}
		}
	}

	var (
		kept    int
		removed []string
	)
	for _, entry := range entries {
		if len(entry.name) > 0 && entry.name[0] == '.' {
			// A staging directory a killed process left behind. Swept, but never counted
			// and never reported: it was not a release, and the panel has no row for it.
			if err := os.RemoveAll(filepath.Join(root, entry.name)); err != nil {
				return removed, fmt.Errorf("build: remove the abandoned release directory %s "+
					"of site %s: %w", entry.name, workloadID, err)
			}
			continue
		}
		if _, safe := spared[entry.name]; safe {
			// Counts against the quota, because a customer promised three releases who is
			// serving the oldest of them should still have three, not four.
			kept++
			continue
		}
		if kept < keep {
			kept++
			continue
		}
		if err := os.RemoveAll(filepath.Join(root, entry.name)); err != nil {
			return removed, fmt.Errorf("build: remove release %s of site %s: %w",
				entry.name, workloadID, err)
		}
		removed = append(removed, entry.name)
	}
	return removed, nil
}

// pruneWorkspaces removes the oldest build workspaces beyond keep.
//
// Failures are reported to the caller's log rather than failing the build: the artifact is
// already on disk and telling a customer their deployment failed because a temporary
// directory would not delete would be a lie about what happened.
func pruneWorkspaces(stateDir, workloadID string, keep int, protected string, log *slog.Logger) {
	if keep < 0 {
		keep = 0
	}
	root, err := workspaceRoot(stateDir, workloadID)
	if err != nil {
		log.Warn("could not work out where the build workspaces of a workload are",
			slog.String("workload", workloadID), slog.String("error", err.Error()))
		return
	}

	entries, err := listAged(root)
	if errors.Is(err, os.ErrNotExist) {
		return
	}
	if err != nil {
		log.Warn("could not list the build workspaces of a workload",
			slog.String("workload", workloadID), slog.String("error", err.Error()))
		return
	}

	kept := 0
	for _, entry := range entries {
		if entry.name == protected {
			kept++
			continue
		}
		if kept < keep {
			kept++
			continue
		}
		if err := os.RemoveAll(filepath.Join(root, entry.name)); err != nil {
			log.Warn("could not remove an old build workspace",
				slog.String("workload", workloadID),
				slog.String("workspace", entry.name),
				slog.String("error", err.Error()))
			continue
		}
		log.Debug("removed an old build workspace",
			slog.String("workload", workloadID), slog.String("workspace", entry.name))
	}
}

// listAged is the directories under root, newest first.
//
// By modification time rather than by name, because a release id is the panel's deployment
// id and sorting those as strings puts release 10 before release 9. The time is exact
// rather than approximate: collectRelease stamps a release the moment it lands, so two
// releases produced in the same second still order by which one arrived second.
func listAged(root string) ([]aged, error) {
	entries, err := os.ReadDir(root)
	if err != nil {
		return nil, err
	}

	out := make([]aged, 0, len(entries))
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			if errors.Is(err, os.ErrNotExist) {
				continue
			}
			return nil, err
		}
		out = append(out, aged{name: entry.Name(), at: info.ModTime()})
	}

	sort.Slice(out, func(i, j int) bool {
		if out[i].at.Equal(out[j].at) {
			// A stable tie-break, so a test that creates two directories inside one
			// filesystem timestamp tick still gets a defined answer.
			return out[i].name > out[j].name
		}
		return out[i].at.After(out[j].at)
	})
	return out, nil
}

// prune trims the release directory to what the retention policy allows and reports what
// went, so the panel can drop the rows for releases nobody can roll back to any more.
func (b *Builder) prune(ctx context.Context, request *wisperpb.StartBuild, releaseID string, log *buildLog) []string {
	published, err := b.releases.Published(ctx, request.GetWorkloadId())
	if err != nil {
		// Not fatal: the release is already on disk and the site is unaffected. Keeping
		// everything is the safe direction when the node cannot tell what it is serving.
		b.log.Warn("could not read which release is live, so nothing was pruned",
			slog.String("workload", request.GetWorkloadId()), slog.String("error", err.Error()))
		return nil
	}

	keep, _ := b.retention(ctx)
	removed, err := pruneReleases(b.stateDir, request.GetWorkloadId(), keep, published, releaseID)
	if err != nil {
		b.log.Warn("could not finish pruning old releases",
			slog.String("workload", request.GetWorkloadId()), slog.String("error", err.Error()))
	}
	if len(removed) > 0 {
		log.say("removed %d release(s) beyond the %d this node keeps", len(removed), keep)
	}
	return removed
}

// trimWorkspaces cuts the build workspaces back to the retention policy. Separate from
// prune because the two counts come from different fields and mean different things: one is
// how far a customer can roll back, the other is how much of the last build's cache is
// still around.
func (b *Builder) trimWorkspaces(ctx context.Context, request *wisperpb.StartBuild) {
	_, keep := b.retention(ctx)
	pruneWorkspaces(b.stateDir, request.GetWorkloadId(), keep, request.GetBuildId(), b.log)
}

// retention is what the panel published, with this package's own floor underneath it.
//
// A node that has never received a spec - one enrolled a minute ago, mid-first-deployment -
// still has to decide, and deciding "keep nothing" would leave a customer with no rollback
// on the very first thing they deployed.
func (b *Builder) retention(ctx context.Context) (releases, workspaces int) {
	releases, workspaces = keepReleasesFloor, keepWorkspacesFloor

	stored, err := b.store.LoadSpec(ctx)
	if err != nil {
		if !errors.Is(err, state.ErrNoSpec) {
			b.log.Warn("could not read the retention policy, using this node's own floor",
				slog.String("error", err.Error()))
		}
		return releases, workspaces
	}
	policy := stored.Spec.GetRetention()
	if value := int(policy.GetKeepReleases()); value > releases {
		releases = value
	}
	if value := int(policy.GetKeepBuildWorkspaces()); value > 0 {
		workspaces = value
	}
	return releases, workspaces
}
