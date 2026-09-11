package backup

import (
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

// DESTINATION_KIND_LOCAL: a directory on the node itself.
//
// Worth nothing when the machine is gone, and worth a great deal ten minutes before a risky
// deployment - which is exactly what backup.proto offers it for. It is fast to write, fast to
// restore, and it is the destination the daemon can be tested against end to end with no
// network at all.
//
// Everything lands under <state>/backups/, and `local_prefix` is resolved underneath that and
// never above it. localObjectPath checks every segment of the key, so a prefix of "../../etc"
// is refused rather than escaped from.

type localDestination struct {
	stateDir string
}

func newLocalDestination(stateDir string) *localDestination {
	return &localDestination{stateDir: stateDir}
}

// Upload copies the archive into place, through a partial file and a rename.
//
// The journal is deliberately unused. A resumed local copy would save one pass over a disk
// the node already owns, and it would do so by trusting that the bytes already written are
// the right ones - which for a copy interrupted by a power cut is exactly the assumption that
// is wrong. Copying it again costs seconds and removes the question.
func (l *localDestination) Upload(ctx context.Context, key string, body io.ReaderAt, size int64, _ *uploadJournal) error {
	target, err := localObjectPath(l.stateDir, key)
	if err != nil {
		return err
	}
	if err := makeDirectory(filepath.Dir(target)); err != nil {
		return err
	}

	temporary := target + partialSuffix
	file, err := os.OpenFile(temporary, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, fileMode)
	if err != nil {
		return fmt.Errorf("backup: open %s: %w", temporary, err)
	}
	written, err := io.Copy(file, contextReader(ctx, io.NewSectionReader(body, 0, size)))
	if err != nil {
		closeAndRemove(file, temporary)
		return fmt.Errorf("backup: copy the archive to %s: %w", temporary, err)
	}
	if written != size {
		closeAndRemove(file, temporary)
		return fmt.Errorf("backup: copied %d bytes to %s but the archive is %d", written, temporary, size)
	}
	if err := file.Sync(); err != nil {
		closeAndRemove(file, temporary)
		return fmt.Errorf("backup: flush %s: %w", temporary, err)
	}
	if err := file.Close(); err != nil {
		os.Remove(temporary)
		return fmt.Errorf("backup: close %s: %w", temporary, err)
	}
	if err := os.Rename(temporary, target); err != nil {
		os.Remove(temporary)
		return fmt.Errorf("backup: publish %s: %w", target, err)
	}
	return nil
}

// Abandon removes the partial copy a failed upload left behind. There is no server-side
// state to release here, but there is a half-written file, and a work directory full of them
// fills the same disk the customer's volumes are on.
func (l *localDestination) Abandon(_ context.Context, key string, _ *uploadJournal) {
	target, err := localObjectPath(l.stateDir, key)
	if err != nil {
		return
	}
	os.Remove(target + partialSuffix)
}

func (l *localDestination) Put(_ context.Context, key string, body []byte) error {
	target, err := localObjectPath(l.stateDir, key)
	if err != nil {
		return err
	}
	if err := makeDirectory(filepath.Dir(target)); err != nil {
		return err
	}
	if err := os.WriteFile(target, body, fileMode); err != nil {
		return fmt.Errorf("backup: write %s: %w", target, err)
	}
	return nil
}

func (l *localDestination) Get(_ context.Context, key string) (io.ReadCloser, error) {
	target, err := localObjectPath(l.stateDir, key)
	if err != nil {
		return nil, err
	}
	file, err := os.Open(target)
	if errors.Is(err, os.ErrNotExist) {
		return nil, fmt.Errorf("backup: %s is not at this node's backup directory: %w", key, os.ErrNotExist)
	}
	if err != nil {
		return nil, fmt.Errorf("backup: open %s: %w", target, err)
	}
	return file, nil
}

// List walks the directory under a prefix and reports keys the way an object store would:
// slash-separated and relative to the backup root, so retention's arithmetic is the same
// whichever destination produced the listing.
func (l *localDestination) List(_ context.Context, prefix string) ([]storedObject, error) {
	root := localRoot(l.stateDir)
	start := root
	// A trailing slash is how a caller says "everything under this", which is a prefix at an
	// object store and a directory here. Trimmed rather than refused, because the alternative
	// is that the one call retention makes fails validation and a prune silently never runs.
	if trimmed := strings.TrimSuffix(prefix, "/"); trimmed != "" {
		resolved, err := localObjectPath(l.stateDir, trimmed)
		if err != nil {
			return nil, err
		}
		start = resolved
	}

	var found []storedObject
	err := filepath.WalkDir(start, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			if errors.Is(err, os.ErrNotExist) {
				// Nothing has been written under this prefix yet, which is the normal state
				// of a subject's first backup and not something to fail a prune over.
				return nil
			}
			return err
		}
		if !entry.Type().IsRegular() {
			return nil
		}
		relative, err := filepath.Rel(root, path)
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
		name := filepath.ToSlash(relative)
		if strings.HasSuffix(name, partialSuffix) {
			// A copy something interrupted. Not an object, and never a generation.
			return nil
		}
		found = append(found, storedObject{Key: name, Size: info.Size()})
		return nil
	})
	if err != nil {
		return nil, fmt.Errorf("backup: list %s: %w", start, err)
	}
	return found, nil
}

func (l *localDestination) Delete(_ context.Context, key string) error {
	target, err := localObjectPath(l.stateDir, key)
	if err != nil {
		return err
	}
	if err := os.Remove(target); err != nil && !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("backup: remove %s: %w", target, err)
	}
	return nil
}
