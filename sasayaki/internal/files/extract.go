package files

import (
	"bytes"
	"context"
	"io"
	"io/fs"
	"os"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Unpacking, which is the operation an attacker looks at first.
//
// Three separate things go wrong with archives, and all three are handled here rather than
// in the format readers, so neither zip nor tar can be the one that forgot:
//
//  1. Entry paths. An archive full of `../../etc/passwd` is the oldest trick there is, and
//     a zip may also carry an absolute path or a symlink whose target climbs out. Every
//     entry name goes through the same cleanPath the wire uses, every parent is checked
//     for symlinks, and every write goes through the root's file descriptor.
//  2. Size. A 42-kilobyte zip that expands to petabytes has been passed around since 2001.
//     The budget below is the smallest of what the node allows, what the quota leaves and
//     what the archive's own size could plausibly produce, and it is enforced while the
//     bytes are being written rather than trusted from the header - a header that lies
//     about being small is the point of the attack.
//  3. Count. A million empty files is not a large archive and is still a node with no
//     inodes left.

// archiveEntry is one item of an archive, in the shape both formats reduce to.
type archiveEntry struct {
	// name is exactly what the archive said, unvalidated. It is cleaned here, once.
	name         string
	mode         fs.FileMode
	directory    bool
	symlink      bool
	linkTarget   string
	declaredSize int64
	// open reads the entry's content. Never called for a directory or a link.
	open func() (io.ReadCloser, error)
}

// entrySource is an archive being read. Two implementations, one extraction loop.
type entrySource interface {
	// each visits every entry in archive order, stopping at the first error the visitor
	// returns.
	each(visit func(archiveEntry) error) error
	Close() error
}

func (h *Host) extractArchive(ctx context.Context, root *openRoot, request *wisperpb.ExtractArchive, out *replies) error {
	archivePath, failed := resolve(root, request.GetArchivePath(), true)
	if failed != nil {
		return failed
	}
	destination, failed := resolve(root, request.GetDestinationDirectory(), true)
	if failed != nil {
		return failed
	}
	if failed := root.requireWritable(destination); failed != nil {
		return failed
	}

	file, err := root.root.Open(systemName(archivePath))
	if err != nil {
		return classify(ctx, err, archivePath, "open the archive")
	}
	defer file.Close()

	info, err := file.Stat()
	if err != nil {
		return classify(ctx, err, archivePath, "look at the archive")
	}
	if info.IsDir() {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_IS_A_DIRECTORY, archivePath,
			"this path is a directory, not an archive")
	}

	budget, failed := h.extractBudget(ctx, root, info.Size(), destination)
	if failed != nil {
		return failed
	}
	source, failed := openArchive(file, info.Size(), archivePath)
	if failed != nil {
		return failed
	}
	defer source.Close()

	if err := root.root.MkdirAll(systemName(destination), newDirectoryMode); err != nil {
		return classify(ctx, err, destination, "create the destination folder")
	}

	unpacked, failed := h.unpack(ctx, root, source, destination, request.GetOverwrite(), budget)
	if failed != nil {
		return failed
	}
	return out.done(unpacked, h.now())
}

// extractBudget is how many bytes this extraction may write before it is refused.
//
// The smallest of three numbers, because each of them is a different thing going wrong:
// the node's own ceiling stops one customer filling a shared machine, the quota stops a
// customer filling their own allocation in a way that leaves their application unable to
// start, and the ratio stops a small archive from being either of those.
func (h *Host) extractBudget(ctx context.Context, root *openRoot, archiveSize int64, destination string) (int64, *failure) {
	budget := h.limits.MaxExtractBytes
	if ratio := archiveSize * h.limits.MaxCompressionRatio; ratio > 0 && ratio < budget {
		budget = ratio
	}

	remaining, limited, failed := h.headroom(ctx, root)
	if failed != nil {
		return 0, failed
	}
	if limited && remaining < budget {
		budget = remaining
	}
	if budget <= 0 {
		return 0, refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_QUOTA_EXCEEDED, destination,
			"%q has no room left for an extraction", root.label)
	}
	return budget, nil
}

// openArchive decides what the file is by looking at it.
//
// The magic bytes rather than the extension: an extension is a customer's guess and the
// first four bytes are what the reader is about to believe. A file that is neither is
// refused by name, so "unsupported archive" means "this is not a zip or a tar.gz" rather
// than "I could not open it".
func openArchive(file *os.File, size int64, archivePath string) (entrySource, *failure) {
	header := make([]byte, 4)
	read, err := file.ReadAt(header, 0)
	if err != nil && read < 2 {
		return nil, refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_UNSUPPORTED_ARCHIVE, archivePath,
			"this file is too short to be an archive")
	}

	switch {
	case bytes.HasPrefix(header, []byte("PK")):
		return openZipEntries(file, size, archivePath)
	case bytes.HasPrefix(header, []byte{0x1f, 0x8b}):
		return openTarEntries(file, archivePath)
	default:
		return nil, refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_UNSUPPORTED_ARCHIVE, archivePath,
			"this node extracts zip and tar.gz archives, and this file is neither")
	}
}

// unpack writes every entry, checking each one.
func (h *Host) unpack(
	ctx context.Context,
	root *openRoot,
	source entrySource,
	destination string,
	overwrite bool,
	budget int64,
) (int64, *failure) {
	var (
		count     int64
		written   int64
		refusal   *failure
		traversed = map[string]bool{destination: true}
	)

	err := source.each(func(entry archiveEntry) error {
		if failed := interrupted(ctx, entry.name, "extracting the archive"); failed != nil {
			refusal = failed
			return failed
		}
		count++
		if count > int64(h.limits.MaxArchiveEntries) {
			refusal = refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_TOO_LARGE, entry.name,
				"this archive holds more than %d entries", h.limits.MaxArchiveEntries)
			return refusal
		}

		target, failed := h.placeEntry(ctx, root, destination, entry, traversed)
		if failed != nil {
			refusal = failed
			return failed
		}

		grown, failed := h.writeEntry(ctx, root, target, entry, overwrite, budget-written)
		if failed != nil {
			refusal = failed
			return failed
		}
		written += grown
		return nil
	})

	if refusal != nil {
		return count, refusal
	}
	if err != nil {
		return count, classify(ctx, err, "", "read the archive")
	}
	return count, nil
}

// placeEntry turns an entry's own name into a path inside the destination, and refuses it
// if that is not where it lands.
func (h *Host) placeEntry(
	ctx context.Context,
	root *openRoot,
	destination string,
	entry archiveEntry,
	traversed map[string]bool,
) (string, *failure) {
	relative, failed := cleanPath(entry.name)
	if failed != nil {
		// Re-stated with the entry's own name, because "the path contains .." is only
		// actionable when the customer is told which of nine hundred entries it was.
		return "", refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_PATH_ESCAPES_ROOT, entry.name,
			"the archive entry %q does not name a path inside the destination: %s",
			entry.name, failed.detail)
	}
	if relative == "" {
		return "", refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_PATH_ESCAPES_ROOT, entry.name,
			"the archive has an entry with no name")
	}

	target := childPath(destination, relative)
	parent, _ := parentPath(target)
	if !traversed[parent] {
		// Checked once per directory rather than once per entry: an archive with a
		// hundred thousand files in twenty folders costs twenty lstat walks, not a hundred
		// thousand. What is being checked is whether a symlink appeared in the chain -
		// from an earlier entry of this same archive, or from a previous extraction.
		if failed := ensureTraversable(root.root, parent, true); failed != nil {
			return "", failed
		}
		traversed[parent] = true
	}
	if !entry.directory {
		if err := root.root.MkdirAll(systemName(parent), newDirectoryMode); err != nil {
			return "", classify(ctx, err, parent, "create the folder for an archive entry")
		}
	}
	return target, nil
}
