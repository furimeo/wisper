package build

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"time"
)

// The atomic swap, which is the whole of what "deploy" and "rollback" mean for a site.
//
// rename(2) over an existing name replaces it in one step: a reader that resolves the path
// gets either the old target or the new one, never a moment where the name is missing. The
// two-step version - unlink then symlink - has a window between the calls in which the
// site is a 404, and on a busy site somebody lands in it. So the new link is created under
// a temporary name in the same directory and renamed into place.
//
// Same directory matters twice over: rename across filesystems fails outright, and a
// temporary name anywhere else would leave a stray link behind when the process is killed
// between the two calls.

// swapSymlink makes link point at target, atomically, creating the parent if needed.
func swapSymlink(link, target string) error {
	directory := filepath.Dir(link)
	if err := os.MkdirAll(directory, directoryMode); err != nil {
		return fmt.Errorf("create %s: %w", directory, err)
	}

	temporary := filepath.Join(directory,
		"."+filepath.Base(link)+".swap-"+strconv.FormatInt(time.Now().UnixNano(), 36))
	// A leftover from a process killed between the symlink and the rename below. Removing
	// it rather than failing is safe: the name carries a nanosecond stamp, so nothing else
	// is using this one.
	if err := os.Remove(temporary); err != nil && !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("clear %s: %w", temporary, err)
	}
	if err := os.Symlink(target, temporary); err != nil {
		return fmt.Errorf("create %s: %w", temporary, err)
	}

	if err := os.Rename(temporary, link); err != nil {
		// Nothing has changed for a visitor: the old link is still exactly where it was.
		_ = os.Remove(temporary)
		return fmt.Errorf("move %s into place: %w", temporary, err)
	}
	return nil
}

// directorySize is how much a finished release costs on disk, reported to the panel as
// BuildCompleted.artifact_bytes.
//
// Apparent size rather than blocks used: it is the figure a customer recognises as "how
// big is my site", and it is the same number on every filesystem. Symlinks are counted as
// the length of their target, which is what they occupy, and are never followed - a link
// into the release's own tree would otherwise be counted twice, and one pointing outside
// would count somebody else's data.
func directorySize(root string) (int64, error) {
	var total int64
	err := filepath.WalkDir(root, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		info, err := entry.Info()
		if err != nil {
			if errors.Is(err, os.ErrNotExist) {
				return nil
			}
			return err
		}
		if info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 {
			total += info.Size()
		}
		return nil
	})
	if err != nil {
		return 0, fmt.Errorf("build: measure %s: %w", root, err)
	}
	return total, nil
}
