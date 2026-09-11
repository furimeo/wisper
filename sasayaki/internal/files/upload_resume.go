package files

import (
	"context"
	"errors"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// "What do you already have?" - the question that makes an upload resumable.
//
// The answer is ranges rather than a count, because a browser sends chunks in parallel and
// a dropped connection leaves holes rather than a clean prefix. The client resumes at the
// first gap, which the node computes for it: a phone that lost signal in a lift comes back
// to one number it can act on, not a list it has to walk itself.

func (h *Host) resumeUpload(ctx context.Context, root *openRoot, request *wisperpb.ResumeUpload, out *replies) error {
	sessionID := request.GetSessionId()
	if err := checkIdentifier("upload session id", sessionID); err != nil {
		return out.uploadState(&wisperpb.UploadState{SessionId: sessionID})
	}

	progress, err := h.store.UploadProgress(ctx, sessionID)
	if errors.Is(err, state.ErrNotFound) {
		// Never heard of it: the sweeper took it, or it was started somewhere else. known
		// stays false and the browser starts again from zero, which is a complete answer
		// rather than an error (files.proto, UploadState.known).
		return out.uploadState(&wisperpb.UploadState{SessionId: sessionID})
	}
	if err != nil {
		return classify(ctx, err, "", "read the upload's progress")
	}
	if progress.Session.RootID != root.id {
		// A session id that exists but under another root. Answered the same way rather
		// than with an error: telling a caller which root a session belongs to would let
		// one customer probe for another's uploads.
		return out.uploadState(&wisperpb.UploadState{SessionId: sessionID})
	}

	return out.uploadState(&wisperpb.UploadState{
		SessionId:     sessionID,
		Received:      wireRanges(progress.Received),
		TotalBytes:    progress.Session.TotalBytes,
		ReceivedBytes: progress.ReceivedBytes,
		ExpiresAt:     timestamppb.New(progress.Session.ExpiresAt),
		Known:         true,
	})
}

// wireRanges converts the store's ranges to the wire's. Both are half-open and both are
// coalesced and sorted by the time they get here (internal/state, ranges.go).
func wireRanges(ranges []state.ByteRange) []*wisperpb.ByteRange {
	out := make([]*wisperpb.ByteRange, 0, len(ranges))
	for _, received := range ranges {
		out = append(out, &wisperpb.ByteRange{Start: received.Start, EndExclusive: received.EndExclusive})
	}
	return out
}
