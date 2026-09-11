package files

import (
	"context"
	"fmt"
	"os"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Where the bytes of an upload live while it is happening, and what stops two goroutines
// from writing them at once.
//
//	<state>/uploads/parts/<session-id>
//
// Inside no file root, deliberately (doc.go). A half-uploaded file must not appear in the
// customer's listing, must not be served by the edge and must not be swept into a backup;
// it is not their file yet. It becomes one at CompleteUpload, in one move, or never.
//
// The parts file is written at the offset each chunk declares, so a browser uploading four
// chunks in parallel produces a sparse file with holes rather than four files that have to
// be concatenated in the right order afterwards. Which bytes actually arrived is the
// SQLite table's answer, not the file's length (internal/state, upload_chunk.go).

// openParts opens the parts directory as a root, creating it if this is the first upload
// since the node was installed.
//
// A root rather than a plain path even though nothing in here comes from a customer: the
// session id is validated by checkIdentifier before it gets this far, and opening through
// a root means a future caller who forgets that check still cannot escape the directory.
func (h *Host) openParts() (*os.Root, error) {
	directory := partsRoot(h.stateDir)
	if err := os.MkdirAll(directory, nodeDirectoryMode); err != nil {
		return nil, fmt.Errorf("files: create %s: %w", directory, err)
	}
	root, err := os.OpenRoot(directory)
	if err != nil {
		return nil, fmt.Errorf("files: open %s: %w", directory, err)
	}
	return root, nil
}

// openPartsFile opens one session's parts file for writing, creating it if it is not there.
//
// O_CREATE without O_TRUNC: a resumed upload has to find the bytes that arrived before the
// connection dropped, and truncating here would make "resume" mean "start again" while
// telling SQLite otherwise.
func (h *Host) openPartsFile(sessionID string) (*os.File, error) {
	if err := checkIdentifier("upload session id", sessionID); err != nil {
		return nil, err
	}
	parts, err := h.openParts()
	if err != nil {
		return nil, err
	}
	defer parts.Close()

	file, err := parts.OpenFile(sessionID, os.O_RDWR|os.O_CREATE, 0o600)
	if err != nil {
		return nil, fmt.Errorf("files: open the parts file of upload session %s: %w", sessionID, err)
	}
	return file, nil
}

// removePartsFile deletes the bytes of a session that is over, however it ended.
//
// Missing is success: an abort that arrives after the sweeper has already been through, or
// a completion that already moved the file, must not fail because the thing they wanted
// gone is gone.
func (h *Host) removePartsFile(sessionID string) error {
	if err := checkIdentifier("upload session id", sessionID); err != nil {
		return err
	}
	parts, err := h.openParts()
	if err != nil {
		return err
	}
	defer parts.Close()

	if err := parts.Remove(sessionID); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("files: delete the parts file of upload session %s: %w", sessionID, err)
	}
	return nil
}

// lockSession serialises everything that touches one upload.
//
// A browser sends chunks in parallel, and the completion of a session can arrive while a
// retried chunk is still being written. Both write the parts file and both update the same
// SQLite row, so they take turns. Different sessions never wait on each other, which is
// what keeps one customer's slow upload from holding up everybody else's.
//
// It waits on the context rather than forever: a chunk queued behind a completion that is
// checksumming a two-gigabyte file must be able to give up when the customer navigates
// away.
func (h *Host) lockSession(ctx context.Context, sessionID string) (func(), *failure) {
	value, _ := h.sessions.LoadOrStore(sessionID, make(chan struct{}, 1))
	gate, ok := value.(chan struct{})
	if !ok {
		return nil, refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR, "",
			"the lock for upload session %s is not a lock", sessionID)
	}

	select {
	case gate <- struct{}{}:
		return func() { <-gate }, nil
	case <-ctx.Done():
		return nil, &failure{
			code:   wisperpb.FileErrorCode_FILE_ERROR_CODE_CANCELLED,
			detail: "waiting for another chunk of this upload to finish was cancelled",
			cause:  ctx.Err(),
		}
	}
}
