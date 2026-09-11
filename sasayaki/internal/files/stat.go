package files

import (
	"context"
	"io/fs"
	"os"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// One entry, described.
//
// Everything in this package that reports a path reports it through describe, so a
// directory listing and a stat cannot disagree about what a symlink is or which
// permission bits are shown. Lstat rather than Stat, always: a link is reported as a link
// and its target is never opened, which is the rule the whole traversal defence rests on
// (files.proto, FileInfo.symlink).

// statPath answers a StatPath: one entry, or NOT_FOUND.
func (h *Host) statPath(ctx context.Context, root *openRoot, request *wisperpb.StatPath, out *replies) error {
	// followLeaf is false: a stat of a symlink describes the link, which is exactly what
	// the file manager needs to draw it.
	target, failed := resolve(root, request.GetPath(), false)
	if failed != nil {
		return failed
	}

	if target == "" {
		described, err := describeRoot(root)
		if err != nil {
			return classify(ctx, err, "", "look at the file root")
		}
		return out.info(described)
	}

	info, err := root.root.Lstat(systemName(target))
	if err != nil {
		return classify(ctx, err, target, "look at the path")
	}
	_, name := parentPath(target)
	return out.info(describe(root, target, name, info))
}

// describe turns one directory entry into the message the panel shows.
//
// name is passed in rather than taken from the FileInfo because a listing already has it
// and a stat of the root itself has no name of its own to take.
func describe(root *openRoot, relative, name string, info fs.FileInfo) *wisperpb.FileInfo {
	mode := info.Mode()
	described := &wisperpb.FileInfo{
		Name:       name,
		Path:       relative,
		Directory:  mode.IsDir(),
		SizeBytes:  info.Size(),
		Mode:       uint32(mode.Perm()),
		ModifiedAt: timestamppb.New(info.ModTime()),
		Symlink:    mode&fs.ModeSymlink != 0,
	}
	described.Uid, described.Gid = ownerOf(info)

	if described.Symlink {
		// Read for display only. The target is never resolved, never joined onto the root
		// and never opened; a customer seeing where a link points is the whole feature.
		if target, err := root.root.Readlink(systemName(relative)); err == nil {
			described.SymlinkTarget = target
		}
	}
	return described
}

// describeRoot is the entry for the root directory itself, which has no parent to be
// listed from and therefore no name.
func describeRoot(root *openRoot) (*wisperpb.FileInfo, error) {
	info, err := root.root.Stat(".")
	if err != nil {
		return nil, err
	}
	described := describe(root, "", root.label, info)
	// A root is shown by its label rather than by the directory name on the node: the
	// panel never learns where a root lives, and "7" is not a breadcrumb.
	described.Name = root.label
	return described, nil
}

// exists reports whether something is at this path, without following a link.
func exists(root *os.Root, relative string) (bool, error) {
	_, err := root.Lstat(systemName(relative))
	switch {
	case err == nil:
		return true, nil
	case os.IsNotExist(err):
		return false, nil
	default:
		return false, err
	}
}
