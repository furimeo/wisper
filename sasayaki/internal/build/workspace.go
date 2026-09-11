package build

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"

	"github.com/moby/moby/client"
)

// The directory a build happens in, and what carries over from the last one.
//
//	<state>/builds/<workload>/<build>/source/   the checkout
//
// One workspace per build rather than one per workload, so a build that failed strangely
// leaves a tree an operator can look at while the next build proceeds in its own, and so
// that RetentionPolicy.keep_build_workspaces has something to count.
//
// # What "use the cache" costs and buys
//
// The caches worth keeping - node_modules, the package manager's store, a generator's
// incremental output - are untracked files inside the checkout, which is where every tool
// puts them. So reuse is: rename the newest previous workspace onto this build's id, then
// bring the existing git repository up to the new commit instead of cloning a fresh one.
// The rename moves a gigabyte of node_modules in one inode operation, and `git fetch` plus
// a hard checkout leaves untracked files exactly where they were.
//
// Two consequences, both deliberate. The previous workspace stops existing under its old
// name, which is why a failed build's tree survives only until the next cached build of
// the same workload - and "clear cache and deploy" is the escape hatch the proto describes
// for precisely that. And an archive source never reuses anything: a zip has no notion of
// which files it tracks, so extracting one over a previous tree would publish whatever the
// previous build left behind. That is stated in fetcharchive.go and enforced here.

// workspace is one build's scratch space on the host.
type workspace struct {
	// Root is <state>/builds/<workload>/<build>.
	Root string
	// Checkout is Root/source, where the repository or the archive lands.
	Checkout string
	// Reused names the workspace that was carried across, or "" for a fresh one. Worth a
	// line in the build log: an install that took four seconds instead of ninety is
	// otherwise indistinguishable from one that silently did nothing.
	Reused string
}

// prepareWorkspace makes the directory this build works in.
//
// A workspace already sitting under this exact build id - from an attempt the daemon died
// in the middle of - is removed rather than built on top of. Half of a previous checkout
// under a new commit is the state in which a build succeeds and ships the wrong files.
func (b *Builder) prepareWorkspace(workloadID, buildID string, reuse bool) (workspace, error) {
	root, err := workspaceDir(b.stateDir, workloadID, buildID)
	if err != nil {
		return workspace{}, err
	}
	parent := filepath.Dir(root)
	if err := os.MkdirAll(parent, directoryMode); err != nil {
		return workspace{}, fmt.Errorf("build: create %s: %w", parent, err)
	}
	if err := os.RemoveAll(root); err != nil {
		return workspace{}, fmt.Errorf("build: clear the workspace %s: %w", root, err)
	}

	space := workspace{Root: root, Checkout: filepath.Join(root, checkoutDirectory)}
	if reuse {
		adopted, err := adoptNewest(parent, root, buildID)
		if err != nil {
			return workspace{}, err
		}
		space.Reused = adopted
	}

	if err := os.MkdirAll(space.Checkout, directoryMode); err != nil {
		return workspace{}, fmt.Errorf("build: create %s: %w", space.Checkout, err)
	}
	return space, nil
}

// adoptNewest renames the workload's most recent workspace onto this build's id and says
// which one it took, or "" when there was nothing to take.
//
// A rename that fails is answered with "nothing to reuse" rather than with an error: a
// workspace that cannot be moved costs an install, not a deployment, and failing a
// customer's build over a cache is the wrong trade in every direction.
func adoptNewest(parent, root, buildID string) (string, error) {
	entries, err := listAged(parent)
	if errors.Is(err, os.ErrNotExist) {
		return "", nil
	}
	if err != nil {
		return "", fmt.Errorf("build: look for a workspace to reuse in %s: %w", parent, err)
	}

	for _, entry := range entries {
		if entry.name == buildID {
			continue
		}
		if err := os.Rename(filepath.Join(parent, entry.name), root); err != nil {
			return "", nil
		}
		return entry.name, nil
	}
	return "", nil
}

// emptyCheckout replaces the source tree with an empty directory.
//
// Used before a fresh clone, which git refuses to perform into a directory that has
// anything in it, and before extracting an archive, which has to be exactly what the
// customer uploaded and nothing a previous build left.
func emptyCheckout(checkout string) error {
	if err := os.RemoveAll(checkout); err != nil {
		return fmt.Errorf("build: clear the checkout %s: %w", checkout, err)
	}
	if err := os.MkdirAll(checkout, directoryMode); err != nil {
		return fmt.Errorf("build: create %s: %w", checkout, err)
	}
	return nil
}

// hasRepository reports whether a reused checkout still holds the git repository that
// makes an incremental fetch possible.
func hasRepository(checkout string) bool {
	info, err := os.Stat(filepath.Join(checkout, ".git"))
	return err == nil && info.IsDir()
}

// sweepAbandoned removes build containers a killed daemon left behind.
//
// sasayaki is crash-only: there is no cleanup on exit, so the container of a build that
// was in flight when the machine went down is still there when it comes back. It is swept
// at the start of the next build rather than by a shutdown hook, which by definition does
// not run in the case that needs it.
//
// Only containers carrying this package's own build label. A container with a workload
// label belongs to a customer and is not this function's to touch under any circumstances
// (runtime/list.go says the same thing from the other side).
func (b *Builder) sweepAbandoned(ctx context.Context, log *slog.Logger) {
	listed, err := b.engine.ContainerList(ctx, client.ContainerListOptions{
		All:     true,
		Filters: make(client.Filters).Add("label", labelBuild),
	})
	if err != nil {
		log.Debug("could not look for abandoned build containers", slog.String("error", err.Error()))
		return
	}
	for _, summary := range listed.Items {
		b.removeContainer(ctx, summary.ID)
		log.Info("removed a build container left behind by an earlier run",
			slog.String("container", summary.ID))
	}
}
