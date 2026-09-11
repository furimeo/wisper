package build

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
)

// The half of a static site that has no process: a directory of releases and one symlink.
//
// Publishing is pointing `current` at a release. Rolling back is pointing it at an older
// one. Both are the panel naming a different release_id in the next generation, which is
// why there is no publish command and no rollback command that could disagree with the
// spec (design section 5.5).
//
// Separate from Builder on purpose. This type needs a state directory and nothing else -
// no Docker, no panel - so a site keeps being published and rolled back on a node whose
// engine has gone away, and the reconcile loop can be tested against it with no build
// machinery anywhere.

// The contract with the reconcile loop, checked by the compiler.
var _ reconcile.Sites = (*Releases)(nil)

// Releases is one node's static-site trees.
//
// Safe for concurrent use: every method is a filesystem call and the one that mutates is a
// rename, which the kernel serialises for us.
type Releases struct {
	stateDir string
	log      *slog.Logger
}

// NewReleases opens the site trees under a node's state root.
func NewReleases(stateDir string, logger *slog.Logger) (*Releases, error) {
	if !filepath.IsAbs(stateDir) {
		return nil, fmt.Errorf("build: the state directory %q is not absolute, and a relative "+
			"one resolves against whatever directory the daemon happens to have been started in",
			stateDir)
	}
	if logger == nil {
		logger = slog.Default()
	}
	return &Releases{stateDir: stateDir, log: logger}, nil
}

// Published is the release `current` resolves to, or "" when nothing is published yet.
//
// Read with Readlink rather than Stat: the answer wanted is which release was chosen, and
// following the link would answer "a directory exists", which is also true of a release
// that has since been pruned out from under it.
func (r *Releases) Published(ctx context.Context, workloadID string) (string, error) {
	if err := ctx.Err(); err != nil {
		return "", err
	}
	link, err := currentLink(r.stateDir, workloadID)
	if err != nil {
		return "", err
	}

	target, err := os.Readlink(link)
	switch {
	case errors.Is(err, os.ErrNotExist):
		// Nothing has ever been published here, which is the state of every site between
		// being placed and its first deployment finishing. Not a failure.
		return "", nil
	case err != nil:
		return "", fmt.Errorf("build: read which release site %s is serving: %w", workloadID, err)
	}

	release := filepath.Base(filepath.Clean(filepath.FromSlash(target)))
	if release == "." || release == string(filepath.Separator) {
		return "", fmt.Errorf("build: the current link of site %s points at %q, which names no "+
			"release", workloadID, target)
	}
	return release, nil
}

// Publish points `current` at releaseID.
//
// The swap is a rename over the existing link, which the kernel performs atomically: a
// visitor arriving mid-swap resolves either the old release or the new one, and never a
// directory that is half there. That is the whole reason a release is built into its own
// directory first and moved into place afterwards, and the reason rollback costs nothing -
// the previous tree was never touched, so pointing back at it restores it exactly.
func (r *Releases) Publish(ctx context.Context, workloadID string, releaseID string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	directory, err := releaseDir(r.stateDir, workloadID, releaseID)
	if err != nil {
		return err
	}
	info, err := os.Stat(directory)
	if err != nil {
		return fmt.Errorf("build: site %s cannot serve release %s: %w", workloadID, releaseID, err)
	}
	if !info.IsDir() {
		return fmt.Errorf("build: release %s of site %s is not a directory", releaseID, workloadID)
	}

	link, err := currentLink(r.stateDir, workloadID)
	if err != nil {
		return err
	}
	if already, err := r.Published(ctx, workloadID); err == nil && already == releaseID {
		// Already serving it. Said as a no-op rather than swapped anyway, so that a
		// reconcile pass every fifteen seconds does not replace the link forever.
		return nil
	}

	// Relative, so the whole state tree can be moved or bind-mounted elsewhere and the
	// link still resolves. filepath.Join is deliberately not used: this string is read by
	// the kernel as a path and must use forward slashes on every platform.
	target := releasesDirectory + "/" + releaseID
	if err := swapSymlink(link, target); err != nil {
		return fmt.Errorf("build: point site %s at release %s: %w", workloadID, releaseID, err)
	}

	r.log.Info("published a release",
		slog.String("workload", workloadID),
		slog.String("release", releaseID))
	return nil
}

// Discard removes a site's whole tree, for a workload that has left the spec.
//
// Called by the reconcile loop and by nothing else. It is the one destructive operation in
// this package, and it is deliberately all-or-nothing: a site that is no longer placed here
// has had its files copied nowhere, so leaving half of them behind would be a node slowly
// filling with the sites it used to run.
func (r *Releases) Discard(ctx context.Context, workloadID string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	site, err := siteRoot(r.stateDir, workloadID)
	if err != nil {
		return err
	}
	if _, err := os.Lstat(site); errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err := os.RemoveAll(site); err != nil {
		return fmt.Errorf("build: remove the release tree of site %s: %w", workloadID, err)
	}
	r.log.Info("removed the release tree of a site that has left the spec",
		slog.String("workload", workloadID))
	return nil
}

// Releases lists the finished releases of one site, newest name last.
//
// Names rather than paths, because that is what the spec and the prune policy both speak
// in. Staging directories are excluded: they are the half-written ones, and a caller
// counting how far a customer can roll back must not count those.
func (r *Releases) Releases(workloadID string) ([]string, error) {
	root, err := releaseRoot(r.stateDir, workloadID)
	if err != nil {
		return nil, err
	}
	entries, err := os.ReadDir(root)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("build: list the releases of site %s: %w", workloadID, err)
	}

	names := make([]string, 0, len(entries))
	for _, entry := range entries {
		if !entry.IsDir() || len(entry.Name()) == 0 || entry.Name()[0] == '.' {
			continue
		}
		names = append(names, entry.Name())
	}
	return names, nil
}
