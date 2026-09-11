package files

import (
	"context"
	"io"
	"io/fs"
	"os"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Compressing, on the node.
//
// Doing it here rather than in the browser is the whole point: a customer on a phone can
// package a directory they could never download first, and then download the one file
// (files.proto, CreateArchive). It is also the operation that turns "I need my data off
// this platform" from a support ticket into a button.
//
// The archive is written under a hidden name beside its destination and renamed when it is
// finished, so a customer who lists the folder halfway through does not find a half-built
// zip that looks ready to download.

// archiveWriter is the shape both formats have. Two implementations, one walk: the
// difference between a zip and a tar.gz is entirely in how an entry is written, and
// duplicating the walk to say that twice is how one of the two ends up without the symlink
// case or without the entry limit.
type archiveWriter interface {
	addDirectory(name string, info fs.FileInfo) error
	addFile(name string, info fs.FileInfo, source io.Reader) error
	addSymlink(name string, info fs.FileInfo, target string) error
	// Close finishes the container: a zip's central directory, a tar's trailer and the
	// gzip footer. An archive whose Close was skipped is unreadable, so it is never
	// deferred and dropped here.
	Close() error
}

func (h *Host) createArchive(ctx context.Context, root *openRoot, request *wisperpb.CreateArchive, out *replies) error {
	destination, failed := resolve(root, request.GetDestination(), false)
	if failed != nil {
		return failed
	}
	if failed := root.requireWritable(destination); failed != nil {
		return failed
	}
	if destination == "" {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_IS_A_DIRECTORY, "",
			"an archive needs a filename, and this one names the file root itself")
	}
	if len(request.GetPaths()) == 0 {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_NOT_FOUND, destination,
			"an archive of nothing is not an archive; name at least one path")
	}

	sources := make([]string, 0, len(request.GetPaths()))
	for _, raw := range request.GetPaths() {
		source, failed := resolve(root, raw, false)
		if failed != nil {
			return failed
		}
		if insideSlashTree(source, destination) {
			// The archive would be inside what it is archiving. Refused rather than
			// skipped, because a customer who asked for it has misunderstood something and
			// a silently incomplete archive is worse than an error.
			return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR, destination,
				"the archive %q would be written inside %q, which it is archiving",
				destination, source)
		}
		sources = append(sources, source)
	}

	parent, name := parentPath(destination)
	directory, err := root.root.OpenRoot(systemName(parent))
	if err != nil {
		return classify(ctx, err, parent, "open the folder the archive goes in")
	}
	defer directory.Close()

	temporary := "." + name + ".wisper-partial"
	file, err := directory.OpenFile(temporary, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, newFileMode)
	if err != nil {
		return classify(ctx, err, destination, "create the archive")
	}

	written, failed := h.fill(ctx, root, request.GetFormat(), file, sources, destination)
	if closeErr := file.Close(); closeErr != nil && failed == nil {
		failed = classify(ctx, closeErr, destination, "finish the archive")
	}
	if failed != nil {
		_ = directory.Remove(temporary)
		return failed
	}
	if err := directory.Rename(temporary, name); err != nil {
		_ = directory.Remove(temporary)
		return classify(ctx, err, destination, "move the archive into place")
	}
	return out.done(written, h.now())
}

// fill opens the right writer and walks every source into it.
func (h *Host) fill(
	ctx context.Context,
	root *openRoot,
	format wisperpb.ArchiveFormat,
	target io.Writer,
	sources []string,
	destination string,
) (int64, *failure) {
	var writer archiveWriter
	switch format {
	case wisperpb.ArchiveFormat_ARCHIVE_FORMAT_ZIP:
		writer = newZipArchive(target)
	case wisperpb.ArchiveFormat_ARCHIVE_FORMAT_TAR_GZ:
		writer = newTarArchive(target)
	default:
		return 0, refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_UNSUPPORTED_ARCHIVE, destination,
			"this node can write zip and tar.gz archives, and was asked for %s", format)
	}

	var written int64
	for _, source := range sources {
		info, err := root.root.Lstat(systemName(source))
		if err != nil {
			_ = writer.Close()
			return written, classify(ctx, err, source, "look at the path")
		}
		// The name each source appears under: its own last element, so archiving `a/b`
		// produces `b/...` the way `zip -r out.zip b` does. A source that is the root
		// itself has no name and its contents go in at the top level.
		_, base := parentPath(source)
		if failed := h.pack(ctx, root, writer, source, base, info, 0, &written); failed != nil {
			_ = writer.Close()
			return written, failed
		}
	}

	if err := writer.Close(); err != nil {
		return written, classify(ctx, err, destination, "finish the archive")
	}
	return written, nil
}

// pack writes one entry, and everything under it when it is a directory.
func (h *Host) pack(
	ctx context.Context,
	root *openRoot,
	writer archiveWriter,
	source, name string,
	info fs.FileInfo,
	depth int,
	written *int64,
) *failure {
	if failed := interrupted(ctx, source, "building the archive"); failed != nil {
		return failed
	}
	if depth > maxTreeDepth {
		return tooDeep(source)
	}
	if *written >= int64(h.limits.MaxArchiveEntries) {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_TOO_LARGE, source,
			"this archive would hold more than %d entries", h.limits.MaxArchiveEntries)
	}

	switch {
	case info.Mode()&fs.ModeSymlink != 0:
		target, err := root.root.Readlink(systemName(source))
		if err != nil {
			return classify(ctx, err, source, "read the symbolic link")
		}
		if err := writer.addSymlink(name, info, target); err != nil {
			return classify(ctx, err, source, "add the link to the archive")
		}
		*written++
		return nil

	case info.IsDir():
		if name != "" {
			if err := writer.addDirectory(name, info); err != nil {
				return classify(ctx, err, source, "add the folder to the archive")
			}
			*written++
		}
		entries, failed := readEntries(ctx, root, source)
		if failed != nil {
			return failed
		}
		for _, entry := range entries {
			child, err := entry.Info()
			if err != nil {
				// Removed while the archive was being built. Not in it, and not a reason
				// to throw away everything already written.
				continue
			}
			if failed := h.pack(ctx, root, writer, childPath(source, entry.Name()),
				childPath(name, entry.Name()), child, depth+1, written); failed != nil {
				return failed
			}
		}
		return nil

	case !info.Mode().IsRegular():
		// A socket, a fifo, a device node. Nothing a customer's archive can carry
		// meaningfully, and re-creating one on extraction is a privilege question nobody
		// asked. Skipped rather than refused: one stray socket must not make a whole
		// directory unarchivable.
		return nil
	}

	file, err := root.root.Open(systemName(source))
	if err != nil {
		return classify(ctx, err, source, "open the file")
	}
	err = writer.addFile(name, info, file)
	closeErr := file.Close()
	if err != nil {
		return classify(ctx, err, source, "add the file to the archive")
	}
	if closeErr != nil {
		return classify(ctx, closeErr, source, "add the file to the archive")
	}
	*written++
	return nil
}
