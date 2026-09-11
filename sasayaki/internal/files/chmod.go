package files

import (
	"context"
	"io/fs"
	"os"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Permissions, and the one rule that makes a recursive chmod useful instead of dangerous.
//
// A customer setting 644 on a tree means "everyone can read my files". Applied literally
// to the directories as well it means "nobody can open the folders they are in", and the
// application that was working stops working. So the execute bit is derived for
// directories from the read bit, which is what `chmod -R a+rX` does and what every person
// who has ever run a recursive chmod actually wanted (files.proto, ChangeMode).

// permissionBits is the mask of what the file manager may set: the low nine bits. setuid,
// setgid and the sticky bit are not offered, because a customer who can set setuid on a
// file inside a volume that another of their containers mounts has escalated inside their
// own tenancy, and nothing in the file manager needs it.
const permissionBits = 0o777

func (h *Host) changeMode(ctx context.Context, root *openRoot, request *wisperpb.ChangeMode, out *replies) error {
	// followLeaf is true: chmod acts on what a link points at, not on the link, so a
	// symlink leaf is refused rather than silently changing something else's permissions.
	target, failed := resolve(root, request.GetPath(), true)
	if failed != nil {
		return failed
	}
	if failed := root.requireWritable(target); failed != nil {
		return failed
	}
	if request.GetMode()&^uint32(permissionBits) != 0 {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_PERMISSION_DENIED, target,
			"mode %#o has bits outside the nine permission bits; the file manager does not set "+
				"setuid, setgid or the sticky bit", request.GetMode())
	}

	mode := fs.FileMode(request.GetMode() & permissionBits)
	info, err := root.root.Lstat(systemName(target))
	if err != nil {
		return classify(ctx, err, target, "look at the path")
	}

	if !request.GetRecursive() || !info.IsDir() {
		if err := root.root.Chmod(systemName(target), modeFor(mode, info.IsDir())); err != nil {
			return classify(ctx, err, target, "change the permissions")
		}
		return out.done(1, h.now())
	}

	changed, failed := h.chmodTree(ctx, root, target, mode, 0)
	if failed != nil {
		return failed
	}
	return out.done(changed, h.now())
}

// modeFor is the derivation: a directory gains an execute bit wherever the requested mode
// has the matching read bit. 644 becomes 755 on a directory and stays 644 on a file; 600
// becomes 700 and stays 600.
func modeFor(mode fs.FileMode, directory bool) fs.FileMode {
	if !directory {
		return mode
	}
	return mode | ((mode & 0o444) >> 2)
}

// chmodTree applies the mode to everything underneath, never through a symlink.
func (h *Host) chmodTree(ctx context.Context, root *openRoot, target string, mode fs.FileMode, depth int) (int64, *failure) {
	if failed := interrupted(ctx, target, "changing permissions"); failed != nil {
		return 0, failed
	}
	if depth > maxTreeDepth {
		return 0, tooDeep(target)
	}

	if err := root.root.Chmod(systemName(target), modeFor(mode, true)); err != nil {
		return 0, classify(ctx, err, target, "change the permissions")
	}
	changed := int64(1)

	entries, failed := readEntries(ctx, root, target)
	if failed != nil {
		return changed, failed
	}

	for _, entry := range entries {
		child := childPath(target, entry.Name())
		if entry.Type()&fs.ModeSymlink != 0 {
			// Chmod would follow it. Skipped rather than refused: a recursive chmod that
			// failed because a build left a link in node_modules would be useless.
			continue
		}
		if entry.IsDir() {
			count, failed := h.chmodTree(ctx, root, child, mode, depth+1)
			changed += count
			if failed != nil {
				return changed, failed
			}
			continue
		}
		if err := root.root.Chmod(systemName(child), mode); err != nil {
			if os.IsNotExist(err) {
				continue
			}
			return changed, classify(ctx, err, child, "change the permissions")
		}
		changed++
	}
	return changed, nil
}
