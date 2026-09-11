package build

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
)

// Moving what a build produced into a release directory.
//
// Note what this does not do: it does not touch `current`. A finished build leaves
// releases/<release-id>/ on disk and stops, and the release goes live when the next
// NodeSpec names it and the reconcile loop calls Releases.Publish (build.proto). That
// separation is the reason a build can fail at any point without the site noticing - the
// tree being served was never a party to it.
//
// The copy lands in a staging directory whose name starts with a dot and is renamed into
// place at the end. Two reasons: `current` can never be pointed at a half-filled directory
// because a half-filled one is not named like a release, and a build killed halfway leaves
// something the next build of that workload sweeps rather than something that looks
// finished.

// collectRelease copies the plan's output directory into releases/<release-id>/ and
// reports how big it is.
func (b *Builder) collectRelease(ctx context.Context, workloadID, releaseID, output string) (int64, error) {
	info, err := os.Stat(output)
	if errors.Is(err, os.ErrNotExist) {
		return 0, fmt.Errorf("build: the build produced no %s directory; check the plan's output "+
			"directory against what the build command writes", filepath.Base(output))
	}
	if err != nil {
		return 0, fmt.Errorf("build: look at the build output in %s: %w", output, err)
	}
	if !info.IsDir() {
		return 0, fmt.Errorf("build: the build output %s is a file, and a site is served from a "+
			"directory", output)
	}
	entries, err := os.ReadDir(output)
	if err != nil {
		return 0, fmt.Errorf("build: read the build output in %s: %w", output, err)
	}
	if len(entries) == 0 {
		// Almost always a plan whose output directory does not match what the generator
		// writes. Publishing it would replace a working site with an empty one, which is
		// the failure a customer cannot diagnose from the outside.
		return 0, fmt.Errorf("build: the build output %s is empty, so there is nothing to serve",
			filepath.Base(output))
	}

	target, err := releaseDir(b.stateDir, workloadID, releaseID)
	if err != nil {
		return 0, err
	}
	if err := b.clearCollision(ctx, workloadID, releaseID, target); err != nil {
		return 0, err
	}

	root := filepath.Dir(target)
	if err := os.MkdirAll(root, directoryMode); err != nil {
		return 0, fmt.Errorf("build: create %s: %w", root, err)
	}
	staging := filepath.Join(root, stagingPrefix+releaseID)
	if err := os.RemoveAll(staging); err != nil {
		return 0, fmt.Errorf("build: clear %s: %w", staging, err)
	}

	if err := copyTree(ctx, output, staging); err != nil {
		// Nothing is serving this, and leaving it would take up disk under a name the
		// prune sweep only reaches on the next build.
		_ = os.RemoveAll(staging)
		return 0, err
	}
	if err := os.Rename(staging, target); err != nil {
		_ = os.RemoveAll(staging)
		return 0, fmt.Errorf("build: move release %s into place: %w", releaseID, err)
	}

	// Stamped so that pruning orders releases by when they landed rather than by name -
	// a release id is the panel's deployment id, and sorting those as strings puts 10
	// before 9 (prune.go).
	now := b.now()
	if err := os.Chtimes(target, now, now); err != nil {
		return 0, fmt.Errorf("build: stamp release %s: %w", releaseID, err)
	}
	return directorySize(target)
}

// clearCollision deals with a release directory that is already there.
//
// A deployment id is used once, so this is either a rebuild after the build record was
// pruned or a node that has been restored from a backup. Replacing an unpublished
// directory is right; replacing the one being served is not, and it fails loudly rather
// than taking a site down to satisfy a rerun.
func (b *Builder) clearCollision(ctx context.Context, workloadID, releaseID, target string) error {
	if _, err := os.Stat(target); errors.Is(err, os.ErrNotExist) {
		return nil
	} else if err != nil {
		return fmt.Errorf("build: look at the existing release %s: %w", releaseID, err)
	}

	published, err := b.releases.Published(ctx, workloadID)
	if err != nil {
		return err
	}
	if published == releaseID {
		return fmt.Errorf("build: release %s of site %s already exists and is the one being "+
			"served; a deployment id is used once, so this build would have replaced a live "+
			"site with a rebuild of it", releaseID, workloadID)
	}
	if err := os.RemoveAll(target); err != nil {
		return fmt.Errorf("build: remove the leftover release %s: %w", releaseID, err)
	}
	return nil
}

// copyTree duplicates a directory, refusing to carry anything that is not a file, a
// directory or a symlink pointing inside the tree.
//
// A copy rather than a rename, even though both trees are under the state root and a
// rename would be free. Two reasons: the workspace is kept for the next build's cache and
// a rename would take the generator's incremental output with it, and a hard link would
// make a later build writing in place mutate a release that is already live.
//
// `.git` is left behind wherever it appears. A checkout published as-is - which is what
// BUILD_PRESET_STATIC with no output directory means - would otherwise serve the
// repository's whole history over HTTP, including whatever was committed and reverted.
func copyTree(ctx context.Context, from, to string) error {
	return filepath.WalkDir(from, func(source string, entry os.DirEntry, err error) error {
		if err != nil {
			return fmt.Errorf("build: read %s: %w", source, err)
		}
		if err := ctx.Err(); err != nil {
			return err
		}

		relative, err := filepath.Rel(from, source)
		if err != nil {
			return fmt.Errorf("build: place %s in the release: %w", source, err)
		}
		if entry.IsDir() && entry.Name() == ".git" && relative != "." {
			return filepath.SkipDir
		}
		destination := filepath.Join(to, relative)

		switch {
		case entry.IsDir():
			return mkdirLike(source, destination)
		case entry.Type()&os.ModeSymlink != 0:
			return copyLink(from, source, destination)
		case entry.Type().IsRegular():
			return copyFile(source, destination)
		default:
			// A socket a dev server left behind, a fifo. Not servable, and copying one
			// would either block or create something on the release tree that is not
			// content.
			return nil
		}
	})
}

func mkdirLike(source, destination string) error {
	info, err := os.Stat(source)
	if err != nil {
		return fmt.Errorf("build: look at %s: %w", source, err)
	}
	// The source's permissions, with the owner's write bit forced on so a generator that
	// produced a read-only directory does not make the release impossible to prune.
	if err := os.MkdirAll(destination, info.Mode().Perm()|0o700); err != nil {
		return fmt.Errorf("build: create %s: %w", destination, err)
	}
	return nil
}

func copyFile(source, destination string) error {
	in, err := os.Open(source)
	if err != nil {
		return fmt.Errorf("build: read %s: %w", source, err)
	}
	defer in.Close()

	info, err := in.Stat()
	if err != nil {
		return fmt.Errorf("build: look at %s: %w", source, err)
	}
	out, err := os.OpenFile(destination, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, info.Mode().Perm()|0o600)
	if err != nil {
		return fmt.Errorf("build: write %s: %w", destination, err)
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return fmt.Errorf("build: write %s: %w", destination, err)
	}
	if err := out.Close(); err != nil {
		return fmt.Errorf("build: write %s: %w", destination, err)
	}
	return nil
}

// copyLink recreates a symlink, provided it stays inside the tree being copied.
//
// A link out of the release is a link into the node - the workspace next door, /etc - and
// the edge serves what it resolves to. Refused rather than dropped, because a site whose
// links silently disappeared is a site that is subtly broken instead of one that failed to
// deploy.
func copyLink(from, source, destination string) error {
	target, err := os.Readlink(source)
	if err != nil {
		return fmt.Errorf("build: read the link %s: %w", source, err)
	}
	if filepath.IsAbs(target) {
		return fmt.Errorf("build: the build output holds the link %s, which points at the "+
			"absolute path %q and would serve something from outside the release",
			relativeName(from, source), target)
	}
	if !insideTree(from, filepath.Join(filepath.Dir(source), target)) {
		return fmt.Errorf("build: the build output holds the link %s, which points at %q, "+
			"outside the release", relativeName(from, source), target)
	}
	if err := os.MkdirAll(filepath.Dir(destination), directoryMode); err != nil {
		return fmt.Errorf("build: create the directory for %s: %w", destination, err)
	}
	if err := os.Symlink(target, destination); err != nil {
		return fmt.Errorf("build: write the link %s: %w", destination, err)
	}
	return nil
}

// relativeName is what to call a path in a message the customer reads: where it is in
// their output, not where it is on this node's disk.
func relativeName(root, path string) string {
	relative, err := filepath.Rel(root, path)
	if err != nil {
		return path
	}
	return filepath.ToSlash(relative)
}

// discardRelease removes a release directory a failed build had started to fill.
//
// Called on every failure path after the collection stage began, and safe to call when
// there is nothing there. It never touches the published release: the name it is given is
// the one this build created, and clearCollision has already refused to let that be the
// live one.
func discardRelease(stateDir, workloadID, releaseID string) error {
	target, err := releaseDir(stateDir, workloadID, releaseID)
	if err != nil {
		return err
	}
	root := filepath.Dir(target)
	staging := filepath.Join(root, stagingPrefix+releaseID)
	if err := os.RemoveAll(staging); err != nil {
		return fmt.Errorf("build: remove %s: %w", staging, err)
	}
	if err := os.RemoveAll(target); err != nil {
		return fmt.Errorf("build: remove %s: %w", target, err)
	}
	return nil
}
