package files

import (
	"context"
	"errors"
	"fmt"
	"path/filepath"

	"github.com/furimeo/wisper/sasayaki/internal/state"
)

// The one thing another package asks this one for.
//
// A customer without a Git repository deploys by uploading a zip, and that zip reaches the
// node the way every other file does: a resumable chunked upload into the staging file
// root. The builder then has to open it. It could not resolve the path itself without
// learning where roots live, and a second opinion about that is a second place for a
// path-traversal bug to live - so it is handed the answer (internal/build, ports.go,
// Uploads).
//
// The signature is the builder's, written there as its own interface. There is no
// compile-time assertion here on purpose: files would have to import build to make one,
// and the dependency belongs the other way round.

// ArchivePath is the absolute path of the file a completed upload session produced.
//
// An error for a session that does not exist, is not finished, or whose bytes are no
// longer on disk. The last case is real rather than defensive: the sweeper removes staged
// archives nobody built from, and a build command replayed a day after it was first sent
// has to fail with "the archive is gone" rather than with a path to nothing.
func (h *Host) ArchivePath(ctx context.Context, sessionID string) (string, error) {
	if err := checkIdentifier("upload session id", sessionID); err != nil {
		return "", err
	}

	session, err := h.store.Upload(ctx, sessionID)
	if errors.Is(err, state.ErrNotFound) {
		return "", fmt.Errorf("files: this node has no upload session %s", sessionID)
	}
	if err != nil {
		return "", err
	}
	if !session.Complete() {
		return "", fmt.Errorf("files: upload session %s has not been completed, so the archive "+
			"it is producing is not whole yet", sessionID)
	}

	root, failed := h.open(ctx, session.RootID)
	if failed != nil {
		return "", fmt.Errorf("files: the file root of upload session %s: %w", sessionID, failed)
	}
	defer root.Close()

	// Resolved again rather than trusted. The path was checked when the session was
	// opened, but the tree has changed since - that is what the upload did to it - and a
	// symlink that appeared in the meantime must not become a path handed to another
	// package to open.
	relative, failed := resolve(root, session.Path, true)
	if failed != nil {
		return "", fmt.Errorf("files: the destination of upload session %s: %w", sessionID, failed)
	}
	present, err := exists(root.root, relative)
	if err != nil {
		return "", fmt.Errorf("files: look at the archive of upload session %s: %w", sessionID, err)
	}
	if !present {
		return "", fmt.Errorf("files: the archive of upload session %s is no longer on this node", sessionID)
	}

	return filepath.Join(root.directory, filepath.FromSlash(relative)), nil
}
