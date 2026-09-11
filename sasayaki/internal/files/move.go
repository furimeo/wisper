package files

import (
	"context"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Rename and move, which are one operation.
//
// Separating them produces two code paths with one bug each, and the bug is always the
// same one: the path check that the other path forgot. There is one here, applied to both
// ends (files.proto, MovePath).

func (h *Host) movePath(ctx context.Context, root *openRoot, request *wisperpb.MovePath, out *replies) error {
	// followLeaf is false at both ends. Moving a symlink moves the link itself, which is
	// what a customer tidying up a directory expects, and what makes a link removable at
	// all: an operation that refused to touch it would leave it there forever.
	from, failed := resolve(root, request.GetFrom(), false)
	if failed != nil {
		return failed
	}
	to, failed := resolve(root, request.GetTo(), false)
	if failed != nil {
		return failed
	}
	if failed := root.requireWritable(from); failed != nil {
		return failed
	}

	switch {
	case from == "":
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_PERMISSION_DENIED, "",
			"the file root itself cannot be moved")
	case to == "":
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_ALREADY_EXISTS, "",
			"the file root itself is already there")
	case from == to:
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_ALREADY_EXISTS, to,
			"the source and the destination are the same path")
	case insideSlashTree(from, to):
		// The kernel would refuse this with EINVAL, which reaches a customer as "invalid
		// argument" and explains nothing.
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR, to,
			"%q is inside %q, and a directory cannot be moved into itself", to, from)
	}

	if !request.GetOverwrite() {
		present, err := exists(root.root, to)
		if err != nil {
			return classify(ctx, err, to, "look at the destination")
		}
		if present {
			return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_ALREADY_EXISTS, to,
				"something is already at %q, and this move was not asked to overwrite it", to)
		}
	}

	if err := root.root.Rename(systemName(from), systemName(to)); err != nil {
		return classify(ctx, err, from, "move the path")
	}
	return out.done(1, h.now())
}
