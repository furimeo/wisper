package backup

import (
	"archive/tar"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

// Reading a volume into a tar stream.
//
// tar rather than zip for the reason every backup format is tar: it stores permissions,
// modification times and symlinks, it streams in one pass with no central directory to seek
// back to, and a customer who has lost their panel can still open the result with a tool
// that has been on every Unix machine for forty years.
//
// What is deliberately not copied: sockets, devices and named pipes. A unix socket in a data
// directory is a running process's doorbell, not data, and recreating one on restore would
// hand an application a door nobody is behind. They are skipped and counted, and the count is
// reported, because a silent omission is how somebody finds out a year later.

// archiveStats is what a walk of a volume found.
type archiveStats struct {
	Files    int
	Symlinks int
	Skipped  int
	Bytes    int64
}

// writeVolumeArchive walks root and writes every entry into out as a tar stream.
//
// The paths inside the archive are relative to root and always use forward slashes, so an
// archive taken on one node restores onto another without a translation step.
func writeVolumeArchive(root string, out io.Writer) (archiveStats, error) {
	var stats archiveStats

	info, err := os.Stat(root)
	if err != nil {
		return stats, fmt.Errorf("backup: look at the volume at %s: %w", root, err)
	}
	if !info.IsDir() {
		return stats, fmt.Errorf("backup: %s is not a directory, so it is not a volume", root)
	}

	archive := tar.NewWriter(out)
	walkErr := filepath.WalkDir(root, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return fmt.Errorf("backup: walk %s: %w", path, err)
		}
		if path == root {
			return nil
		}
		relative, err := filepath.Rel(root, path)
		if err != nil {
			return fmt.Errorf("backup: place %s inside the archive: %w", path, err)
		}
		name := filepath.ToSlash(relative)

		switch {
		case entry.IsDir():
			return writeDirectoryEntry(archive, path, name, entry)
		case entry.Type()&os.ModeSymlink != 0:
			stats.Symlinks++
			return writeSymlinkEntry(archive, path, name, entry)
		case entry.Type().IsRegular():
			written, err := writeFileEntry(archive, path, name, entry)
			if err != nil {
				return err
			}
			stats.Files++
			stats.Bytes += written
			return nil
		default:
			// A socket, a device or a fifo. Counted rather than copied.
			stats.Skipped++
			return nil
		}
	})
	if walkErr != nil {
		archive.Close()
		return stats, walkErr
	}
	if err := archive.Close(); err != nil {
		return stats, fmt.Errorf("backup: finish the archive of %s: %w", root, err)
	}
	return stats, nil
}

func writeDirectoryEntry(archive *tar.Writer, path, name string, entry fs.DirEntry) error {
	info, err := entry.Info()
	if err != nil {
		return fmt.Errorf("backup: look at %s: %w", path, err)
	}
	header, err := tar.FileInfoHeader(info, "")
	if err != nil {
		return fmt.Errorf("backup: describe %s: %w", path, err)
	}
	header.Name = name + "/"
	if err := archive.WriteHeader(header); err != nil {
		return fmt.Errorf("backup: write the header for %s: %w", path, err)
	}
	return nil
}

func writeSymlinkEntry(archive *tar.Writer, path, name string, entry fs.DirEntry) error {
	target, err := os.Readlink(path)
	if err != nil {
		return fmt.Errorf("backup: read the link %s: %w", path, err)
	}
	info, err := entry.Info()
	if err != nil {
		return fmt.Errorf("backup: look at %s: %w", path, err)
	}
	header, err := tar.FileInfoHeader(info, target)
	if err != nil {
		return fmt.Errorf("backup: describe the link %s: %w", path, err)
	}
	header.Name = name
	if err := archive.WriteHeader(header); err != nil {
		return fmt.Errorf("backup: write the header for the link %s: %w", path, err)
	}
	return nil
}

// writeFileEntry copies one regular file.
//
// The length in the header is the length at the moment of the stat, and tar is unforgiving
// about the two disagreeing. That is not a hypothetical even with the workload paused: a
// build container or the file manager can be writing into the same tree, so a file that grew
// is truncated to what was declared and a file that shrank is padded, and either way the
// archive stays readable instead of failing at the last entry.
func writeFileEntry(archive *tar.Writer, path, name string, entry fs.DirEntry) (int64, error) {
	info, err := entry.Info()
	if err != nil {
		return 0, fmt.Errorf("backup: look at %s: %w", path, err)
	}
	header, err := tar.FileInfoHeader(info, "")
	if err != nil {
		return 0, fmt.Errorf("backup: describe %s: %w", path, err)
	}
	header.Name = name

	file, err := os.Open(path)
	if err != nil {
		return 0, fmt.Errorf("backup: open %s: %w", path, err)
	}
	defer file.Close()

	if err := archive.WriteHeader(header); err != nil {
		return 0, fmt.Errorf("backup: write the header for %s: %w", path, err)
	}
	written, err := io.Copy(archive, io.LimitReader(file, header.Size))
	if err != nil {
		return written, fmt.Errorf("backup: copy %s into the archive: %w", path, err)
	}
	if written < header.Size {
		if _, err := io.Copy(archive, &zeroes{remaining: header.Size - written}); err != nil {
			return written, fmt.Errorf("backup: pad %s in the archive: %w", path, err)
		}
	}
	return header.Size, nil
}

// zeroes pads a file that shrank between its stat and its read.
type zeroes struct{ remaining int64 }

func (z *zeroes) Read(p []byte) (int, error) {
	if z.remaining <= 0 {
		return 0, io.EOF
	}
	n := int64(len(p))
	if n > z.remaining {
		n = z.remaining
	}
	for i := range p[:n] {
		p[i] = 0
	}
	z.remaining -= n
	return int(n), nil
}

// entryPath resolves one archive entry's name against the directory being restored into, and
// refuses anything that would land outside it.
//
// This is the check that stops a hostile archive - or one written by an older version of this
// package with a bug in it - from writing /etc/cron.d on the host. Absolute names, names
// containing "..", and names that resolve outside the root after cleaning are all refused
// rather than trimmed: an entry that has to be rewritten to be safe is an entry nobody meant
// to send.
func entryPath(root, name string) (string, error) {
	cleaned := strings.TrimSpace(name)
	if cleaned == "" {
		return "", fmt.Errorf("backup: the archive contains an entry with no name")
	}
	if strings.ContainsRune(cleaned, 0) {
		return "", fmt.Errorf("backup: the archive contains an entry whose name has a null byte in it")
	}
	cleaned = strings.TrimSuffix(cleaned, "/")
	if cleaned == "" {
		return root, nil
	}
	if strings.HasPrefix(cleaned, "/") || strings.HasPrefix(cleaned, "\\") {
		return "", fmt.Errorf("backup: the archive entry %q is an absolute path", name)
	}
	converted := filepath.FromSlash(cleaned)
	if filepath.IsAbs(converted) || filepath.VolumeName(converted) != "" {
		return "", fmt.Errorf("backup: the archive entry %q is an absolute path", name)
	}
	for _, segment := range strings.Split(cleaned, "/") {
		if segment == ".." {
			return "", fmt.Errorf("backup: the archive entry %q climbs out of the volume", name)
		}
	}
	joined := filepath.Join(root, converted)
	if !insideTree(root, joined) {
		return "", fmt.Errorf("backup: the archive entry %q resolves outside the volume", name)
	}
	return joined, nil
}
