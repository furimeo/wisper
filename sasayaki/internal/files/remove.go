package files

import (
	"context"
	"io/fs"
	"os"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Deleting, which has no undo.
//
// There is no trash on a node: what this removes is gone until a backup is restored. That
// is why `recursive` is required for a non-empty directory rather than assumed, and why
// the count of what was removed goes back to the panel - a customer who meant to delete
// one file and is told nine hundred went should find out immediately.

func (h *Host) deletePath(ctx context.Context, root *openRoot, request *wisperpb.DeletePath, out *replies) error {
	// followLeaf is false: deleting a symlink deletes the link, never what it points at.
	target, failed := resolve(root, request.GetPath(), false)
	if failed != nil {
		return failed
	}
	if failed := root.requireWritable(target); failed != nil {
		return failed
	}
	if target == "" {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_PERMISSION_DENIED, "",
			"the file root itself cannot be deleted; delete what is in it instead")
	}

	info, err := root.root.Lstat(systemName(target))
	if err != nil {
		return classify(ctx, err, target, "look at the path")
	}

	if info.IsDir() {
		if request.GetRecursive() {
			removed, failed := h.removeTree(ctx, root, target, 0)
			if failed != nil {
				return failed
			}
			return out.done(removed, h.now())
		}
		// Emptiness is checked here rather than left to the kernel. Not for the check
		// itself - Remove would refuse too - but because the errno differs by platform and
		// the panel branches on the code: this is the one refusal in the file manager that
		// has a specific "add the recursive flag" answer in the UI.
		entries, failed := readEntries(ctx, root, target)
		if failed != nil {
			return failed
		}
		if len(entries) > 0 {
			return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_DIRECTORY_NOT_EMPTY, target,
				"%q still holds %d entries; deleting it has to be asked for as a recursive delete",
				target, len(entries))
		}
	}

	if err := root.root.Remove(systemName(target)); err != nil {
		return classify(ctx, err, target, "delete the path")
	}
	return out.done(1, h.now())
}

// removeTree deletes a directory and everything under it, and says how much that was.
//
// Written here rather than delegated to os.Root.RemoveAll for two reasons that both matter
// to a customer: it counts, so the panel can report what happened, and it checks the
// context between entries, so cancelling a delete of a hundred thousand files stops rather
// than finishing in the background.
//
// It never descends into a symlink. ReadDir reports the entry's own type, so a link to a
// directory is removed as a link and what it points at is untouched - which is the
// difference between deleting a folder and deleting the volume it was linked to.
func (h *Host) removeTree(ctx context.Context, root *openRoot, target string, depth int) (int64, *failure) {
	if failed := interrupted(ctx, target, "deleting the directory"); failed != nil {
		return 0, failed
	}
	if depth > maxTreeDepth {
		return 0, tooDeep(target)
	}

	entries, failed := readEntries(ctx, root, target)
	if failed != nil {
		return 0, failed
	}

	var removed int64
	for _, entry := range entries {
		child := childPath(target, entry.Name())
		if entry.IsDir() && entry.Type()&fs.ModeSymlink == 0 {
			count, failed := h.removeTree(ctx, root, child, depth+1)
			removed += count
			if failed != nil {
				return removed, failed
			}
			continue
		}
		if err := root.root.Remove(systemName(child)); err != nil {
			if os.IsNotExist(err) {
				// Something else removed it first. The customer asked for it to be gone
				// and it is gone.
				continue
			}
			return removed, classify(ctx, err, child, "delete the file")
		}
		removed++
	}

	if err := root.root.Remove(systemName(target)); err != nil {
		return removed, classify(ctx, err, target, "delete the directory")
	}
	return removed + 1, nil
}
