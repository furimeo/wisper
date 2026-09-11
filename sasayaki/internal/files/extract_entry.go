package files

import (
	"context"
	"io"
	"io/fs"
	"os"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Writing one entry of an archive onto the disk.
//
// Split from extract.go, which decides what an archive is and how much of it may be
// written, because this half is where the bytes land and it is the half that has to be
// read with a suspicious eye. Everything here takes an entry whose name has already been
// validated and a remaining allowance, and its whole job is to not exceed either.

// writeEntry creates one directory, file or link, and returns how many bytes it wrote.
func (h *Host) writeEntry(
	ctx context.Context,
	root *openRoot,
	target string,
	entry archiveEntry,
	overwrite bool,
	allowance int64,
) (int64, *failure) {
	switch {
	case entry.directory:
		if err := root.root.MkdirAll(systemName(target), modeFor(entryMode(entry.mode, true), true)); err != nil {
			return 0, classify(ctx, err, target, "create the folder")
		}
		return 0, nil

	case entry.symlink:
		return 0, h.linkEntry(ctx, root, target, entry, overwrite)
	}

	if !entry.mode.IsRegular() {
		// A device node or a fifo in an archive from somewhere else. Not created: making
		// one needs privileges the file manager deliberately does not use, and skipping it
		// leaves the rest of the archive usable.
		return 0, nil
	}
	return h.copyEntry(ctx, root, target, entry, overwrite, allowance)
}

// copyEntry writes one file, stopping the moment it exceeds what is left of the budget.
//
// The limit is applied to the bytes as they arrive, not to the size the header declared.
// A zip bomb's headers are honest about the expanded size only when the attacker expects
// nobody to look; the interesting ones lie, and the only number that cannot lie is how
// much has actually come out of the decompressor.
func (h *Host) copyEntry(
	ctx context.Context,
	root *openRoot,
	target string,
	entry archiveEntry,
	overwrite bool,
	allowance int64,
) (int64, *failure) {
	if allowance <= 0 || entry.declaredSize > allowance {
		return 0, refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_TOO_LARGE, entry.name,
			"this archive expands to more than this node will extract in one operation")
	}

	flags := os.O_WRONLY | os.O_CREATE | os.O_EXCL
	if overwrite {
		flags = os.O_WRONLY | os.O_CREATE | os.O_TRUNC
	}
	file, err := root.root.OpenFile(systemName(target), flags, entryMode(entry.mode, false))
	if err != nil {
		return 0, classify(ctx, err, target, "create the file")
	}

	content, err := entry.open()
	if err != nil {
		file.Close()
		return 0, classify(ctx, err, entry.name, "read the archive entry")
	}

	// One byte more than the allowance, so exceeding it is detectable rather than being a
	// copy that happens to stop exactly at the limit.
	written, copyErr := io.Copy(file, io.LimitReader(content, allowance+1))
	closeErr := file.Close()
	content.Close()

	switch {
	case copyErr != nil:
		_ = root.root.Remove(systemName(target))
		return 0, classify(ctx, copyErr, entry.name, "extract the archive entry")
	case closeErr != nil:
		_ = root.root.Remove(systemName(target))
		return 0, classify(ctx, closeErr, target, "write the file")
	case written > allowance:
		_ = root.root.Remove(systemName(target))
		return 0, refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_TOO_LARGE, entry.name,
			"this entry expands past what is left of this extraction's budget, which is what "+
				"a compressed file that lies about its size looks like")
	}
	return written, nil
}

// linkEntry re-creates a symbolic link from an archive, if and only if it points somewhere
// inside the same root.
//
// Worth doing rather than skipping: a node_modules tree is largely links, and an
// extraction that dropped them produces an application that does not start. Worth checking
// rather than trusting: a link is the one archive entry that can still escape after its
// own name has been validated, because the escape is in the target.
func (h *Host) linkEntry(ctx context.Context, root *openRoot, target string, entry archiveEntry, overwrite bool) *failure {
	parent, _ := parentPath(target)
	if !linkStaysInside(parent, entry.linkTarget) {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_PATH_ESCAPES_ROOT, entry.name,
			"the archive entry %q is a link to %q, which is outside this file root",
			entry.name, entry.linkTarget)
	}

	if overwrite {
		if err := root.root.Remove(systemName(target)); err != nil && !os.IsNotExist(err) {
			return classify(ctx, err, target, "replace the existing link")
		}
	}
	if err := root.root.Symlink(entry.linkTarget, systemName(target)); err != nil {
		return classify(ctx, err, target, "create the link")
	}
	return nil
}

// linkStaysInside resolves a link target lexically against the directory the link is in,
// and reports whether it is still inside the root.
//
// Lexical because the target need not exist yet: an archive routinely holds a link before
// the file it points at. `..` is counted rather than refused here, because a relative link
// climbing one level to a sibling directory is the normal shape of the ones in a build
// output.
func linkStaysInside(parent, target string) bool {
	if target == "" {
		return false
	}
	if windowsHost && (len(target) > 1 && target[1] == ':') {
		return false
	}
	if target[0] == '/' || target[0] == '\\' {
		return false
	}

	depth := 0
	for _, element := range strings.Split(parent, "/") {
		if element != "" {
			depth++
		}
	}
	for _, element := range strings.Split(target, "/") {
		switch element {
		case "", ".":
		case "..":
			depth--
			if depth < 0 {
				return false
			}
		default:
			depth++
		}
	}
	return true
}

// entryMode is the permission an extracted entry gets.
//
// An archive with no mode - most zips written on Windows - extracts as a plain file rather
// than as mode zero, which would produce a tree the customer cannot read. Anything above
// the nine permission bits is dropped: a setuid bit that survived a round trip through an
// archive is a privilege nobody granted.
func entryMode(mode fs.FileMode, directory bool) fs.FileMode {
	permission := mode.Perm()
	if permission == 0 {
		if directory {
			return newDirectoryMode
		}
		return newFileMode
	}
	return permission
}
