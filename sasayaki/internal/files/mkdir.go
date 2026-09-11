package files

import (
	"context"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// "New folder", and the deeper version an extraction needs.

// createDirectory answers a CreateDirectory.
//
// With parents = false this is one mkdir and an existing directory is an error the
// customer sees, which is what the file manager's "new folder" wants. With parents = true
// it is idempotent, like mkdir -p, because an extraction creates the same parent for every
// entry under it and failing on the second one would make a perfectly good archive
// unextractable.
func (h *Host) createDirectory(ctx context.Context, root *openRoot, request *wisperpb.CreateDirectory, out *replies) error {
	// followLeaf is false: the last element is what is being created, so there is nothing
	// there to follow. If a symlink is already sitting on the name, mkdir reports it as
	// "already exists", which is a truer answer than "the path escapes the root".
	target, failed := resolve(root, request.GetPath(), false)
	if failed != nil {
		return failed
	}
	if failed := root.requireWritable(target); failed != nil {
		return failed
	}
	if target == "" {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_ALREADY_EXISTS, "",
			"the file root itself is already there")
	}

	if request.GetParents() {
		if err := root.root.MkdirAll(systemName(target), newDirectoryMode); err != nil {
			return classify(ctx, err, target, "create the directory and its parents")
		}
	} else if err := root.root.Mkdir(systemName(target), newDirectoryMode); err != nil {
		return classify(ctx, err, target, "create the directory")
	}

	return out.done(1, h.now())
}
