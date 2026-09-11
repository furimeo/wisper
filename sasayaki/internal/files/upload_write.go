package files

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// One chunk of a resumable upload.
//
// This is the only way bytes enter a customer's disk. Saving an edited file from the
// inline editor is a one-chunk session, so there is no second write path that has to stay
// consistent with this one (files.proto, WriteChunk).
//
// Nothing about a chunk is taken on trust. The offset is recomputed from the chunk index
// and the session's own chunk size, because a resumed upload that believes a
// client-supplied offset can be talked into writing anywhere in the file. The bytes are
// checksummed, because mobile networks corrupt more than they are given credit for and a
// bad chunk accepted silently becomes a corrupt file the customer finds weeks later. And
// the range is checked against the declared size, so a session cannot be grown past what
// the quota check at its start allowed.

// defaultOrphanTTL is how long an unfinished upload survives when the panel has published
// no retention policy. Long enough for a customer to come back after a bad journey, short
// enough that abandoned parts are not the reason a node fills up.
const defaultOrphanTTL = 24 * time.Hour

func (h *Host) writeChunk(ctx context.Context, root *openRoot, request *wisperpb.WriteChunk, out *replies) error {
	session := request.GetSession()
	chunk := request.GetChunk()
	sessionID := session.GetSessionId()
	if err := checkIdentifier("upload session id", sessionID); err != nil {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_UNKNOWN_SESSION, "", "%v", err)
	}

	// followLeaf is false: the destination is being created, and if a symlink is sitting on
	// the name the completion refuses it as "already exists" rather than writing through it.
	destination, failed := resolve(root, session.GetPath(), false)
	if failed != nil {
		return failed
	}
	if failed := root.requireWritable(destination); failed != nil {
		return failed
	}
	if destination == "" {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_IS_A_DIRECTORY, "",
			"an upload needs a filename, and this one names the file root itself")
	}

	release, failed := h.lockSession(ctx, sessionID)
	if failed != nil {
		return failed
	}
	defer release()

	stored, failed := h.beginSession(ctx, root, session, destination)
	if failed != nil {
		return failed
	}
	if failed := validateChunk(stored, chunk); failed != nil {
		return failed
	}

	progress, failed := h.absorb(ctx, stored, chunk)
	if failed != nil {
		return failed
	}
	return out.ack(&wisperpb.UploadAck{
		SessionId:     sessionID,
		ChunkIndex:    chunk.GetChunkIndex(),
		ReceivedBytes: progress.ReceivedBytes,
		NextOffset:    progress.NextOffset,
	})
}

// beginSession finds the open session or opens it, and is where an upload that cannot
// possibly fit is refused before a byte of it is written.
//
// The session description travels on every chunk rather than being opened once, because
// the node may have restarted between two of them and has to rebuild the session from what
// is on disk plus the message in hand. So the second call is the normal case; only the
// first one measures the disk.
func (h *Host) beginSession(
	ctx context.Context,
	root *openRoot,
	session *wisperpb.UploadSession,
	destination string,
) (state.UploadSession, *failure) {
	sessionID := session.GetSessionId()
	// The whole chunk is held in memory while its checksum is verified, so the declared
	// chunk size is a bound on what one upload can make the daemon allocate. Checked before
	// the session is opened rather than per chunk, because it is a property of the session
	// and a client that gets it wrong should be told once.
	if session.GetChunkSize() > h.limits.MaxUploadChunkBytes {
		return state.UploadSession{}, refuse(
			wisperpb.FileErrorCode_FILE_ERROR_CODE_TOO_LARGE, destination,
			"this upload declares %d-byte chunks and this node accepts at most %d",
			session.GetChunkSize(), h.limits.MaxUploadChunkBytes)
	}

	existing, err := h.store.Upload(ctx, sessionID)
	switch {
	case err == nil:
		if existing.RootID != root.id {
			return state.UploadSession{}, refuse(
				wisperpb.FileErrorCode_FILE_ERROR_CODE_UNKNOWN_SESSION, destination,
				"upload session %s belongs to another file root", sessionID)
		}
		if existing.Complete() {
			return state.UploadSession{}, refuse(
				wisperpb.FileErrorCode_FILE_ERROR_CODE_UNKNOWN_SESSION, destination,
				"upload session %s was completed at %s and takes no more chunks",
				sessionID, existing.CompletedAt.Format(time.RFC3339))
		}
	case errors.Is(err, state.ErrNotFound):
		// A new session. This is the one moment where the whole file's size is known and
		// nothing has been written yet, which is exactly when a quota refusal is useful.
		if failed := h.ensureRoom(ctx, root, destination, session.GetTotalBytes()); failed != nil {
			return state.UploadSession{}, failed
		}
	default:
		return state.UploadSession{}, classify(ctx, err, destination, "look up the upload session")
	}

	now := h.now()
	ttl := h.retention(ctx).OrphanUploadTTL
	if ttl <= 0 {
		ttl = defaultOrphanTTL
	}

	opened, err := h.store.BeginUpload(ctx, state.UploadSession{
		SessionID:     sessionID,
		RootID:        root.id,
		Path:          destination,
		StagingPath:   partsRelativePath(sessionID),
		TotalBytes:    session.GetTotalBytes(),
		ChunkSize:     session.GetChunkSize(),
		ContentSHA256: session.GetContentSha256(),
		Overwrite:     session.GetOverwrite(),
		CreatedAt:     now,
		UpdatedAt:     now,
		ExpiresAt:     now.Add(ttl),
	})
	if errors.Is(err, state.ErrUploadConflict) {
		return state.UploadSession{}, refuse(
			wisperpb.FileErrorCode_FILE_ERROR_CODE_UNKNOWN_SESSION, destination,
			"upload session %s was opened for a different file; start a new session", sessionID)
	}
	if err != nil {
		return state.UploadSession{}, classify(ctx, err, destination, "open the upload session")
	}
	return opened, nil
}

// validateChunk is every check that can be made without touching the disk.
func validateChunk(session state.UploadSession, chunk *wisperpb.FileChunk) *failure {
	if session.ChunkSize <= 0 {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR, session.Path,
			"upload session %s declares a chunk size of %d", session.SessionID, session.ChunkSize)
	}
	if chunk.GetChunkIndex() < 0 {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR, session.Path,
			"chunk index %d is negative", chunk.GetChunkIndex())
	}

	// The offset the node uses is derived, never the one it was sent. The sent one is
	// compared against it so a client that has drifted is told, rather than having its
	// bytes quietly written somewhere else.
	expected := chunk.GetChunkIndex() * session.ChunkSize
	if chunk.GetOffset() != expected {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR, session.Path,
			"chunk %d of a %d-byte-chunk upload starts at %d, and this one claims offset %d",
			chunk.GetChunkIndex(), session.ChunkSize, expected, chunk.GetOffset())
	}

	length := int64(len(chunk.GetData()))
	if expected+length > session.TotalBytes {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_TOO_LARGE, session.Path,
			"this chunk would put %d bytes in a file declared as %d",
			expected+length, session.TotalBytes)
	}

	// The checksum before the length rules below, because a chunk that lost bytes in
	// transit is both - and "resend this chunk" is what the browser can act on, while
	// "chunks may not be short" reads like a protocol complaint about its own code.
	digest := sha256.Sum256(chunk.GetData())
	if actual := hex.EncodeToString(digest[:]); actual != chunk.GetSha256() {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_CHECKSUM_MISMATCH, session.Path,
			"chunk %d arrived as %s and was sent as %s; send it again",
			chunk.GetChunkIndex(), actual, chunk.GetSha256())
	}

	if length == 0 && session.TotalBytes > 0 {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR, session.Path,
			"an empty chunk cannot be part of a %d-byte file", session.TotalBytes)
	}
	// A short chunk that is not the last one would leave a hole no chunk index can name:
	// the next chunk starts at (index+1)*chunk_size, not at where this one stopped.
	if length > 0 && length < session.ChunkSize && expected+length != session.TotalBytes {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR, session.Path,
			"chunk %d carries %d bytes, and only the last chunk of a %d-byte-chunk upload may be short",
			chunk.GetChunkIndex(), length, session.ChunkSize)
	}
	return nil
}

// absorb writes the bytes and then records them.
//
// That order is the whole durability argument. If the daemon dies between the two, the
// range is not recorded and the client sends the chunk again, which is harmless. The other
// order would have SQLite claim bytes that are not on disk, and the file would be
// completed with a hole in it that nothing afterwards could detect.
func (h *Host) absorb(ctx context.Context, session state.UploadSession, chunk *wisperpb.FileChunk) (state.UploadProgress, *failure) {
	if len(chunk.GetData()) == 0 {
		// A zero-byte file: no range to record, and Covers reports it complete already.
		progress, err := h.store.UploadProgress(ctx, session.SessionID)
		if err != nil {
			return state.UploadProgress{}, classify(ctx, err, session.Path, "read the upload's progress")
		}
		return progress, nil
	}

	file, err := h.openPartsFile(session.SessionID)
	if err != nil {
		return state.UploadProgress{}, classify(ctx, err, session.Path, "open the upload's parts file")
	}
	written, writeErr := file.WriteAt(chunk.GetData(), chunk.GetOffset())
	closeErr := file.Close()
	if writeErr != nil {
		return state.UploadProgress{}, classify(ctx, writeErr, session.Path, "write the chunk")
	}
	if closeErr != nil {
		return state.UploadProgress{}, classify(ctx, closeErr, session.Path, "write the chunk")
	}

	received := state.ByteRange{Start: chunk.GetOffset(), EndExclusive: chunk.GetOffset() + int64(written)}
	progress, err := h.store.RecordChunk(ctx, session.SessionID, received, h.now())
	if err != nil {
		return state.UploadProgress{}, classify(ctx, err, session.Path, "record the chunk")
	}
	return progress, nil
}
