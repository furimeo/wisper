package files

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/rpc"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// One request in, exactly one terminal event out.
//
// The panel has no other way to tell "finished" from "still working": one stream carries
// every file operation for the node, answers interleave, and a request that stops
// producing events without saying why leaves a spinner on somebody's phone forever. So
// every operation below ends in a terminal event, and this file is where that is arranged
// rather than hoped for.

// Handle performs one file operation and emits the events that answer it.
//
// The error return is for the case where nothing could be said at all - the stream is
// gone. Anything the customer should see is a FileError event, which is a successful
// Handle: the operation failed, the reporting did not.
func (h *Host) Handle(ctx context.Context, request *wisperpb.FileRequest, events rpc.FileEvents) error {
	out := &replies{requestID: request.GetRequestId(), events: events}

	err := h.perform(ctx, request, out)
	switch {
	case err == nil && out.terminated:
		return nil

	case err == nil:
		// Only reachable through a bug in this package: an operation returned success
		// without answering. Say something rather than leaving the panel waiting, and log
		// it as the defect it is.
		h.log.Error("a file operation finished without a terminal event",
			slog.String("request_id", request.GetRequestId()))
		return out.failed(refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR, "",
			"the node completed this operation but produced no answer"))

	default:
		var reported *failure
		if !errors.As(err, &reported) {
			reported = classify(ctx, err, "", "perform the operation")
		}
		h.log.Debug("file operation refused",
			slog.String("request_id", request.GetRequestId()),
			slog.String("root_id", request.GetRootId()),
			slog.String("code", reported.code.String()),
			slog.String("detail", reported.Error()))
		return out.failed(reported)
	}
}

// perform routes one request to the operation that answers it.
//
// The root is opened here, once, because every operation needs it and every operation
// would otherwise have to remember to close it. Resume, complete and abort are the
// exceptions in shape only - they name a session rather than a path - but they still name
// a root, and refusing a session against the wrong root is part of what makes a session id
// minted by a browser safe to accept.
func (h *Host) perform(ctx context.Context, request *wisperpb.FileRequest, out *replies) error {
	if request.GetOp() == nil {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR, "",
			"the request carries no operation")
	}
	if request.GetCancel() != nil {
		// The stream owns the registry of running operations and answers a cancellation
		// itself (internal/rpc, file_stream.go). One reaching here means the two have
		// drifted apart, which is worth saying out loud rather than absorbing.
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR, "",
			"a cancellation was routed to the file host instead of the stream that owns it")
	}

	root, failed := h.open(ctx, request.GetRootId())
	if failed != nil {
		return failed
	}
	defer root.Close()

	switch operation := request.GetOp().(type) {
	case *wisperpb.FileRequest_List:
		return h.listDirectory(ctx, root, operation.List, out)
	case *wisperpb.FileRequest_Stat:
		return h.statPath(ctx, root, operation.Stat, out)
	case *wisperpb.FileRequest_Read:
		return h.readFile(ctx, root, operation.Read, out)
	case *wisperpb.FileRequest_Write:
		return h.writeChunk(ctx, root, operation.Write, out)
	case *wisperpb.FileRequest_Resume:
		return h.resumeUpload(ctx, root, operation.Resume, out)
	case *wisperpb.FileRequest_Complete:
		return h.completeUpload(ctx, root, operation.Complete, out)
	case *wisperpb.FileRequest_Abort:
		return h.abortUpload(ctx, root, operation.Abort, out)
	case *wisperpb.FileRequest_CreateDirectory:
		return h.createDirectory(ctx, root, operation.CreateDirectory, out)
	case *wisperpb.FileRequest_Move:
		return h.movePath(ctx, root, operation.Move, out)
	case *wisperpb.FileRequest_Delete:
		return h.deletePath(ctx, root, operation.Delete, out)
	case *wisperpb.FileRequest_ChangeMode:
		return h.changeMode(ctx, root, operation.ChangeMode, out)
	case *wisperpb.FileRequest_Archive:
		return h.createArchive(ctx, root, operation.Archive, out)
	case *wisperpb.FileRequest_Extract:
		return h.extractArchive(ctx, root, operation.Extract, out)
	case *wisperpb.FileRequest_Measure:
		return h.measureDirectory(ctx, root, operation.Measure, out)
	default:
		// A field added to the oneof and not implemented here. The compiler cannot catch
		// it, so the node says which one rather than ignoring the request.
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR, "",
			"this node's agent does not implement the file operation %T", operation)
	}
}

// resolve is the two steps every path-bearing operation starts with: validate the wire
// path, then refuse a path that would be reached through a symbolic link (path.go).
func resolve(root *openRoot, raw string, followLeaf bool) (string, *failure) {
	path, failed := cleanPath(raw)
	if failed != nil {
		return "", failed
	}
	if failed := ensureTraversable(root.root, path, followLeaf); failed != nil {
		return "", failed
	}
	return path, nil
}

// interrupted turns a cancelled context into the failure the panel expects, and is what
// every loop that can run long calls on each iteration.
func interrupted(ctx context.Context, path, what string) *failure {
	if ctx.Err() == nil {
		return nil
	}
	return &failure{
		code:   wisperpb.FileErrorCode_FILE_ERROR_CODE_CANCELLED,
		detail: what + " was cancelled",
		path:   path,
		cause:  ctx.Err(),
	}
}

// replies is one request's half of the stream.
//
// Not safe for concurrent use, and it does not need to be: the control stream gives each
// request its own goroutine, and nothing in this package fans one request out across
// several. The sink underneath is shared and serialised by rpc.
type replies struct {
	requestID string
	events    rpc.FileEvents
	// terminated is set by the event that ends the operation, so a second one cannot be
	// sent and a missing one is noticed.
	terminated bool
	// broken records that the stream itself failed. Once it has, reporting a failure over
	// it would only produce a second error nobody can read.
	broken bool
}

func (r *replies) send(result *wisperpb.FileEvent, terminal bool) error {
	if r.broken {
		return fmt.Errorf("files: the file operation stream is gone, so request %s cannot be answered",
			r.requestID)
	}
	result.RequestId = r.requestID
	if err := r.events.Send(result); err != nil {
		r.broken = true
		return fmt.Errorf("files: send an answer to request %s: %w", r.requestID, err)
	}
	if terminal {
		r.terminated = true
	}
	return nil
}

func (r *replies) listing(listing *wisperpb.DirectoryListing) error {
	return r.send(&wisperpb.FileEvent{Result: &wisperpb.FileEvent_Listing{Listing: listing}}, true)
}

func (r *replies) info(info *wisperpb.FileInfo) error {
	return r.send(&wisperpb.FileEvent{Result: &wisperpb.FileEvent_Info{Info: info}}, true)
}

// chunk is terminal only when it is the last one: a download is many events and exactly
// one of them ends the operation (files.proto, FileChunk.last).
func (r *replies) chunk(chunk *wisperpb.FileChunk) error {
	return r.send(&wisperpb.FileEvent{Result: &wisperpb.FileEvent_Chunk{Chunk: chunk}}, chunk.GetLast())
}

func (r *replies) ack(ack *wisperpb.UploadAck) error {
	return r.send(&wisperpb.FileEvent{Result: &wisperpb.FileEvent_Ack{Ack: ack}}, true)
}

func (r *replies) uploadState(uploadState *wisperpb.UploadState) error {
	return r.send(&wisperpb.FileEvent{Result: &wisperpb.FileEvent_State{State: uploadState}}, true)
}

func (r *replies) size(size *wisperpb.DirectorySize) error {
	return r.send(&wisperpb.FileEvent{Result: &wisperpb.FileEvent_Size{Size: size}}, true)
}

// done is the success terminator for an operation with no payload of its own. `affected`
// is filled where it is cheap and useful - entries deleted, files extracted - and zero
// otherwise, rather than being invented.
func (r *replies) done(affected int64, at time.Time) error {
	return r.send(&wisperpb.FileEvent{Result: &wisperpb.FileEvent_Done{Done: &wisperpb.OperationDone{
		Affected:   affected,
		FinishedAt: timestamppb.New(at),
	}}}, true)
}

func (r *replies) failed(reported *failure) error {
	if r.broken {
		return fmt.Errorf("files: request %s failed (%s) and the stream is gone, so the panel "+
			"was not told: %s", r.requestID, reported.code, reported.Error())
	}
	return r.send(&wisperpb.FileEvent{Result: &wisperpb.FileEvent_Error{Error: reported.message()}}, true)
}
