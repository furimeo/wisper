package backup

import (
	"archive/tar"
	"compress/gzip"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"time"
)

// Putting a tar stream back on disk.
//
// Every entry is resolved through entryPath first, so nothing in the archive can name a place
// outside the directory being restored into. That is the traversal check; the second half is
// here: an entry is never written through a link. A directory that already exists as a
// symlink is refused rather than followed, and a file is created with O_NOFOLLOW where the
// platform has it, because an archive that contains `data -> /etc` followed by `data/passwd`
// would otherwise write to the host through a door it opened itself.
//
// The destination is always a directory this package has just created and nothing else has
// touched, which makes the check cheap and the failure mode obvious.

// extractVolumeArchive unpacks a gzipped tar stream into root, which must already exist and
// should be empty. It reports how many bytes of file content were written.
func extractVolumeArchive(compressed io.Reader, root string) (int64, error) {
	stream, err := gzip.NewReader(compressed)
	if err != nil {
		return 0, fmt.Errorf("backup: the archive is not gzip: %w", err)
	}
	defer stream.Close()

	archive := tar.NewReader(stream)
	var written int64
	// Directory modes and times are applied after everything inside them, so that creating a
	// file inside a read-only directory does not fail and so that writing one does not bump
	// the modification time that was just restored.
	var directories []*tar.Header

	for {
		header, err := archive.Next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return written, fmt.Errorf("backup: read the archive: %w", err)
		}

		target, err := entryPath(root, header.Name)
		if err != nil {
			return written, err
		}
		if target == root {
			continue
		}

		switch header.Typeflag {
		case tar.TypeDir:
			if err := makeEntryDirectory(target, header); err != nil {
				return written, err
			}
			directories = append(directories, header)
		case tar.TypeSymlink:
			if err := restoreSymlink(root, target, header); err != nil {
				return written, err
			}
		case tar.TypeReg:
			bytes, err := restoreFile(target, header, archive)
			written += bytes
			if err != nil {
				return written, err
			}
		case tar.TypeLink:
			if err := restoreHardLink(root, target, header); err != nil {
				return written, err
			}
		default:
			// Character devices, block devices, fifos and the rest. writeVolumeArchive does
			// not produce them; an archive that contains one was not written by this package
			// and is not being trusted to place a device node on the host.
			return written, fmt.Errorf("backup: the archive entry %q is of type %q, which this "+
				"node does not restore", header.Name, string(header.Typeflag))
		}
	}

	for _, header := range directories {
		target, err := entryPath(root, header.Name)
		if err != nil {
			return written, err
		}
		applyMetadata(target, header)
	}
	return written, nil
}

func makeEntryDirectory(target string, header *tar.Header) error {
	if err := refuseExistingLink(target); err != nil {
		return err
	}
	if err := os.MkdirAll(target, privateMode); err != nil {
		return fmt.Errorf("backup: create %s: %w", target, err)
	}
	_ = header
	return nil
}

func restoreFile(target string, header *tar.Header, contents io.Reader) (int64, error) {
	if err := os.MkdirAll(filepath.Dir(target), privateMode); err != nil {
		return 0, fmt.Errorf("backup: create %s: %w", filepath.Dir(target), err)
	}
	if err := refuseExistingLink(target); err != nil {
		return 0, err
	}

	file, err := os.OpenFile(target, os.O_CREATE|os.O_TRUNC|os.O_WRONLY|noFollow, os.FileMode(header.Mode).Perm())
	if err != nil {
		return 0, fmt.Errorf("backup: create %s: %w", target, err)
	}
	written, err := io.Copy(file, contents)
	if err != nil {
		file.Close()
		return written, fmt.Errorf("backup: write %s: %w", target, err)
	}
	if err := file.Close(); err != nil {
		return written, fmt.Errorf("backup: close %s: %w", target, err)
	}
	applyMetadata(target, header)
	return written, nil
}

func restoreSymlink(root, target string, header *tar.Header) error {
	// The link's own target is checked the same way an entry name is. A relative link inside
	// the volume is normal - a data directory full of them is normal - and one pointing at
	// /etc/shadow is an archive asking the next process that reads the volume to do something
	// for it.
	if _, err := entryPath(filepath.Dir(target), header.Linkname); err != nil {
		return fmt.Errorf("backup: the archive entry %q links outside the volume: %w", header.Name, err)
	}
	if err := os.MkdirAll(filepath.Dir(target), privateMode); err != nil {
		return fmt.Errorf("backup: create %s: %w", filepath.Dir(target), err)
	}
	if err := os.Remove(target); err != nil && !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("backup: replace %s: %w", target, err)
	}
	if err := os.Symlink(filepath.FromSlash(header.Linkname), target); err != nil {
		return fmt.Errorf("backup: create the link %s: %w", target, err)
	}
	return nil
}

func restoreHardLink(root, target string, header *tar.Header) error {
	source, err := entryPath(root, header.Linkname)
	if err != nil {
		return fmt.Errorf("backup: the archive entry %q links outside the volume: %w", header.Name, err)
	}
	if err := os.MkdirAll(filepath.Dir(target), privateMode); err != nil {
		return fmt.Errorf("backup: create %s: %w", filepath.Dir(target), err)
	}
	if err := os.Link(source, target); err != nil {
		return fmt.Errorf("backup: link %s to %s: %w", target, source, err)
	}
	return nil
}

// refuseExistingLink stops an entry from being written through a symlink that is already
// there. Nothing should be: the tree was created moments ago and is not shared. Something
// that is, is an archive that placed it.
func refuseExistingLink(target string) error {
	info, err := os.Lstat(target)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("backup: look at %s: %w", target, err)
	}
	if info.Mode()&os.ModeSymlink != 0 {
		return fmt.Errorf("backup: %s is already a symlink, and an archive entry is not written "+
			"through one", target)
	}
	return nil
}

// applyMetadata restores the mode and the modification time.
//
// Failures are ignored on purpose. A restored file with the wrong mtime is a cosmetic problem
// and a restore that stopped halfway because a temporary directory would not accept a
// timestamp is a customer's application still down.
func applyMetadata(target string, header *tar.Header) {
	if mode := os.FileMode(header.Mode).Perm(); mode != 0 {
		_ = os.Chmod(target, mode)
	}
	if !header.ModTime.IsZero() {
		_ = os.Chtimes(target, time.Now(), header.ModTime)
	}
}
