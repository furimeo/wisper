package files

import (
	"context"
	"io/fs"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The two things every tree operation in this package needs, written once.
//
// Four operations walk a directory tree - delete, recursive chmod, measure and archive -
// and each does something different at each node, so there is no shared visitor here.
// What they do share is the shape of a single step and the depth at which they refuse to
// go further, and those are worth having in one place: a walk that forgot to close the
// directory handle leaks a file descriptor per directory, and one without a depth bound
// walks the daemon's stack down on a hostile tree.

// maxTreeDepth is how deep any walk in this package goes.
//
// Not a real limit on customer data: a legitimate node_modules is about fifteen levels,
// and a tree a hundred and twenty-eight deep is either generated or hostile. Both are
// better refused with a message than allowed to recurse.
const maxTreeDepth = 128

// tooDeep is the refusal, shared so the four walks give the same answer.
func tooDeep(target string) *failure {
	return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR, target,
		"this directory is nested more than %d levels deep, which the node will not walk",
		maxTreeDepth)
}

// readEntries lists one directory through the root, closing the handle before it returns.
//
// The close error is checked rather than deferred and dropped: on a full or failing disk
// it is the first sign, and a walk that reports success over it would have the caller
// believe it saw the whole directory.
func readEntries(ctx context.Context, root *openRoot, target string) ([]fs.DirEntry, *failure) {
	directory, err := root.root.Open(systemName(target))
	if err != nil {
		return nil, classify(ctx, err, target, "open the directory")
	}
	entries, err := directory.ReadDir(-1)
	closeErr := directory.Close()
	if err != nil {
		return nil, classify(ctx, err, target, "read the directory")
	}
	if closeErr != nil {
		return nil, classify(ctx, closeErr, target, "read the directory")
	}
	return entries, nil
}
