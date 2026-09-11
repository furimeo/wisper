package files

import (
	"context"
	"errors"

	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The customer pressed cancel, or closed the tab.
//
// Deleting the parts now rather than leaving them for the sweeper is worth its own
// operation: a customer who abandoned a two-gigabyte upload should not be paying for it
// for the next day, and a node that only reclaims space on a timer fills up during the
// afternoon somebody spends retrying a bad connection (files.proto, AbortUpload).

func (h *Host) abortUpload(ctx context.Context, root *openRoot, request *wisperpb.AbortUpload, out *replies) error {
	sessionID := request.GetSessionId()
	if err := checkIdentifier("upload session id", sessionID); err != nil {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_UNKNOWN_SESSION, "", "%v", err)
	}

	release, failed := h.lockSession(ctx, sessionID)
	if failed != nil {
		return failed
	}
	defer release()

	session, err := h.store.Upload(ctx, sessionID)
	if errors.Is(err, state.ErrNotFound) {
		// Already gone: the sweeper took it, or this abort arrived twice. Both mean what
		// the customer asked for is true, so it is answered as done rather than as an
		// error they can do nothing about.
		return out.done(0, h.now())
	}
	if err != nil {
		return classify(ctx, err, "", "look up the upload session")
	}
	if session.RootID != root.id {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_UNKNOWN_SESSION, "",
			"upload session %s belongs to another file root", sessionID)
	}
	if session.Complete() {
		// The file is already the customer's. Aborting cannot mean "delete it": that is a
		// delete, and it is a different button with a different confirmation.
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_ALREADY_EXISTS, session.Path,
			"upload session %s finished already; delete the file if that is what you meant",
			sessionID)
	}

	if _, err := h.store.ForgetUpload(ctx, sessionID); err != nil && !errors.Is(err, state.ErrNotFound) {
		return classify(ctx, err, session.Path, "forget the upload session")
	}
	if err := h.removePartsFile(sessionID); err != nil {
		return classify(ctx, err, session.Path, "delete the upload's parts file")
	}
	return out.done(1, h.now())
}
