package files

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"hash"
	"io"
	"log/slog"
	"os"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Assembling a finished upload, which is where it becomes the customer's file.
//
// A separate operation from the last chunk because assembly can fail - a checksum
// mismatch, a quota that filled while the upload was in flight - and that failure has to
// be reportable (files.proto, CompleteUpload).
//
// The move is a copy into the destination directory under a hidden name, then a rename.
// Not a rename from the parts directory: a volume can be a different filesystem from the
// state directory, and a rename across one fails with EXDEV halfway through a customer's
// upload. The copy is also where the whole file is checksummed, so the bytes are read once
// rather than twice, and the destination either does not exist or is the complete verified
// file - never anything in between.

func (h *Host) completeUpload(ctx context.Context, root *openRoot, request *wisperpb.CompleteUpload, out *replies) error {
	sessionID := request.GetSessionId()
	if err := checkIdentifier("upload session id", sessionID); err != nil {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_UNKNOWN_SESSION, "", "%v", err)
	}

	release, failed := h.lockSession(ctx, sessionID)
	if failed != nil {
		return failed
	}
	defer release()

	progress, err := h.store.UploadProgress(ctx, sessionID)
	if errors.Is(err, state.ErrNotFound) {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_UNKNOWN_SESSION, "",
			"this node has no upload session %s: it was swept, or it was started against "+
				"another node", sessionID)
	}
	if err != nil {
		return classify(ctx, err, "", "read the upload's progress")
	}
	session := progress.Session
	if session.RootID != root.id {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_UNKNOWN_SESSION, "",
			"upload session %s belongs to another file root", sessionID)
	}
	if session.Complete() {
		// A completion resent after the stream dropped. The file is already in place, and
		// answering it again is what makes the retry safe.
		return out.done(1, session.CompletedAt)
	}
	if !progress.Covered {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR, session.Path,
			"only %d of %d bytes have arrived; the first gap starts at %d",
			progress.ReceivedBytes, session.TotalBytes, progress.NextOffset)
	}

	destination, failed := resolve(root, session.Path, false)
	if failed != nil {
		return failed
	}
	if failed := root.requireWritable(destination); failed != nil {
		return failed
	}
	if failed := h.placeUpload(ctx, root, session, destination); failed != nil {
		return failed
	}

	finished := h.now()
	if err := h.store.CompleteUpload(ctx, sessionID, finished); err != nil {
		// The bytes are in place and the record says otherwise. Reported rather than
		// hidden: the file is the customer's and the row is the node's problem, and the
		// sweeper will not touch a session whose parts file is already gone.
		h.log.Error("an upload was assembled but could not be marked complete",
			slog.String("session_id", sessionID), slog.String("error", err.Error()))
		return classify(ctx, err, destination, "record the finished upload")
	}
	if err := h.removePartsFile(sessionID); err != nil {
		h.log.Warn("the parts file of a finished upload could not be removed",
			slog.String("session_id", sessionID), slog.String("error", err.Error()))
	}
	return out.done(1, finished)
}

// placeUpload copies the parts into the destination directory, checks the whole file's
// checksum and renames it into place.
func (h *Host) placeUpload(ctx context.Context, root *openRoot, session state.UploadSession, destination string) *failure {
	parent, name := parentPath(destination)
	// The folders on the way are created here rather than refused. Dragging a directory
	// onto the file manager uploads a hundred files whose paths describe a tree that does
	// not exist yet, and making the browser create each folder first would be a hundred
	// extra round trips on the connection least able to afford them.
	if err := root.root.MkdirAll(systemName(parent), newDirectoryMode); err != nil {
		return classify(ctx, err, parent, "create the destination folder")
	}

	directory, err := root.root.OpenRoot(systemName(parent))
	if err != nil {
		return classify(ctx, err, parent, "open the destination folder")
	}
	defer directory.Close()

	parts, err := h.openPartsFile(session.SessionID)
	if err != nil {
		return classify(ctx, err, destination, "open the upload's parts file")
	}
	defer parts.Close()

	temporary := uploadTempName(session.SessionID)
	digest := sha256.New()
	if failed := h.assemble(ctx, directory, temporary, parts, session, digest, destination); failed != nil {
		return failed
	}

	if actual := hex.EncodeToString(digest.Sum(nil)); !strings.EqualFold(actual, session.ContentSHA256) {
		_ = directory.Remove(temporary)
		// The bytes on disk are not the file the browser meant to send, and every chunk
		// of them is already recorded as received - so there is nothing left to retry
		// against. The session goes, and the customer uploads again from a clean start.
		h.forget(ctx, session.SessionID)
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_CHECKSUM_MISMATCH, destination,
			"the assembled file hashes to %s and was declared as %q; upload it again",
			actual, session.ContentSHA256)
	}

	return h.publish(ctx, directory, temporary, name, session.Overwrite, destination)
}

// publish is the last step, and the one place where two uploads racing for one filename is
// decided.
//
// Not "check whether it exists, then rename": two browsers uploading to the same path both
// pass that check and the second silently replaces the first, which is how a customer ends
// up with the file they did not send. A hard link fails atomically when the name is taken,
// so exactly one of them wins and the other is told. Overwriting is a rename, which
// replaces atomically - the caller asked for whatever is there to go.
func (h *Host) publish(
	ctx context.Context,
	directory *os.Root,
	temporary, name string,
	overwrite bool,
	destination string,
) *failure {
	if overwrite {
		if err := directory.Rename(temporary, name); err != nil {
			_ = directory.Remove(temporary)
			return classify(ctx, err, destination, "move the upload into place")
		}
		return nil
	}

	err := directory.Link(temporary, name)
	_ = directory.Remove(temporary)
	if err == nil {
		return nil
	}
	if os.IsExist(err) {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_ALREADY_EXISTS, destination,
			"something is already at %q, and this upload was not asked to overwrite it",
			destination)
	}
	return classify(ctx, err, destination, "put the upload in place")
}

// assemble is the copy, in one pass, hashing as it goes.
//
// Exactly TotalBytes are copied. The parts file cannot be longer than that - every chunk
// was checked against the declared size before it was written - but reading it through a
// limit means a file that somehow is longer produces a checksum failure rather than a
// destination with extra bytes on the end.
func (h *Host) assemble(
	ctx context.Context,
	directory *os.Root,
	temporary string,
	parts *os.File,
	session state.UploadSession,
	digest hash.Hash,
	destination string,
) *failure {
	// O_EXCL, so a temporary file left by a killed daemon is noticed rather than appended
	// to. It is this package's own name in the customer's directory, so removing it and
	// trying once more is safe and is what a retry after a crash needs.
	target, err := directory.OpenFile(temporary, os.O_WRONLY|os.O_CREATE|os.O_EXCL, newFileMode)
	if os.IsExist(err) {
		if removeErr := directory.Remove(temporary); removeErr != nil {
			return classify(ctx, removeErr, destination, "clear a leftover partial upload")
		}
		target, err = directory.OpenFile(temporary, os.O_WRONLY|os.O_CREATE|os.O_EXCL, newFileMode)
	}
	if err != nil {
		return classify(ctx, err, destination, "create the destination file")
	}

	failed := h.copyChecked(ctx, target, io.LimitReader(parts, session.TotalBytes), digest, session, destination)
	// fsync before the rename. This is the one place in the file manager where durability
	// is worth an fsync: a rename that lands before the data does leaves a file that
	// exists, has the right length and is full of zeroes, which is worse than no file.
	if failed == nil {
		if err := target.Sync(); err != nil {
			failed = classify(ctx, err, destination, "flush the destination file")
		}
	}
	if err := target.Close(); err != nil && failed == nil {
		failed = classify(ctx, err, destination, "close the destination file")
	}
	if failed != nil {
		_ = directory.Remove(temporary)
	}
	return failed
}

// copyChecked streams the parts into the destination, hashing, and stops when the customer
// gives up rather than after another gigabyte.
func (h *Host) copyChecked(
	ctx context.Context,
	target io.Writer,
	source io.Reader,
	digest hash.Hash,
	session state.UploadSession,
	destination string,
) *failure {
	buffer := make([]byte, h.limits.DownloadChunkBytes)
	var copied int64
	for {
		if failed := interrupted(ctx, destination, "assembling the upload"); failed != nil {
			return failed
		}
		read, err := source.Read(buffer)
		if read > 0 {
			digest.Write(buffer[:read])
			if _, writeErr := target.Write(buffer[:read]); writeErr != nil {
				return classify(ctx, writeErr, destination, "write the destination file")
			}
			copied += int64(read)
		}
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return classify(ctx, err, destination, "read the upload's parts file")
		}
	}

	if copied != session.TotalBytes {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_CHECKSUM_MISMATCH, destination,
			"the parts of this upload hold %d bytes and the file was declared as %d",
			copied, session.TotalBytes)
	}
	return nil
}

// uploadTempName is what a half-assembled upload is called inside the destination
// directory: hidden, prefixed so it is obviously the node's, and named after the session
// so two uploads into one folder cannot collide.
func uploadTempName(sessionID string) string {
	return ".wisper-upload-" + sessionID
}

// forget drops a session and its bytes, logging rather than failing: the caller is already
// reporting something more important than this.
func (h *Host) forget(ctx context.Context, sessionID string) {
	if _, err := h.store.ForgetUpload(ctx, sessionID); err != nil && !errors.Is(err, state.ErrNotFound) {
		h.log.Warn("an upload session could not be forgotten",
			slog.String("session_id", sessionID), slog.String("error", err.Error()))
	}
	if err := h.removePartsFile(sessionID); err != nil {
		h.log.Warn("the parts file of an upload session could not be removed",
			slog.String("session_id", sessionID), slog.String("error", err.Error()))
	}
}
