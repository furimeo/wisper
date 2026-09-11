package rpc

import (
	"context"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

func fileStream(t *testing.T, panel *panelStub) *panelFileStream {
	t.Helper()
	return receive(t, panel.fileOps, "the node never opened the file operation stream")
}

func TestFileRequestsAreServedAndTerminated(t *testing.T) {
	panel := startPanel(t)
	node := newFakeNode()
	startNode(t, panel.credential(), node)

	transfers := fileStream(t, panel)
	transfers.send(t, &wisperpb.FileRequest{
		RequestId: "r-1",
		RootId:    "root-1",
		Op:        &wisperpb.FileRequest_List{List: &wisperpb.ListDirectory{Path: "."}},
	})

	if handled := receive(t, node.files, "the request never reached the file package"); handled.GetRootId() != "root-1" {
		t.Errorf("root id = %q", handled.GetRootId())
	}
	transfers.expect(t, "a terminal event for r-1", func(e *wisperpb.FileEvent) bool {
		return e.GetRequestId() == "r-1" && e.GetDone() != nil
	})
}

// One stream carries every operation for the node, so a slow one must not hold up the
// next, and a cancellation has to arrive while its target is still running.
func TestCancelStopsTheOperationAndAnswersTheCancelItself(t *testing.T) {
	panel := startPanel(t)
	node := newFakeNode()
	node.fileHandler = func(ctx context.Context, request *wisperpb.FileRequest, events FileEvents) error {
		<-ctx.Done()
		return events.Send(&wisperpb.FileEvent{
			RequestId: request.GetRequestId(),
			Result: &wisperpb.FileEvent_Error{Error: &wisperpb.FileError{
				Code:   wisperpb.FileErrorCode_FILE_ERROR_CODE_CANCELLED,
				Detail: "the panel cancelled this",
			}},
		})
	}
	startNode(t, panel.credential(), node)

	transfers := fileStream(t, panel)
	transfers.send(t, &wisperpb.FileRequest{
		RequestId: "r-slow",
		RootId:    "root-1",
		Op:        &wisperpb.FileRequest_Measure{Measure: &wisperpb.MeasureDirectory{Path: "."}},
	})
	receive(t, node.files, "the slow request never started")

	transfers.send(t, &wisperpb.FileRequest{
		RequestId: "r-cancel",
		RootId:    "root-1",
		Op:        &wisperpb.FileRequest_Cancel{Cancel: &wisperpb.CancelRequest{CancelledRequestId: "r-slow"}},
	})

	answer := transfers.expect(t, "an answer to the cancel", func(e *wisperpb.FileEvent) bool {
		return e.GetRequestId() == "r-cancel"
	})
	if answer.GetDone().GetAffected() != 1 {
		t.Errorf("cancel reported %d affected, want 1: it was running", answer.GetDone().GetAffected())
	}

	stopped := transfers.expect(t, "the cancelled operation to finish", func(e *wisperpb.FileEvent) bool {
		return e.GetRequestId() == "r-slow"
	})
	if stopped.GetError().GetCode() != wisperpb.FileErrorCode_FILE_ERROR_CODE_CANCELLED {
		t.Errorf("cancelled operation ended with %v, want CANCELLED", stopped.GetError().GetCode())
	}

	// Cancelling something that already finished is not an error - the panel cannot
	// know the difference from where it is standing.
	transfers.send(t, &wisperpb.FileRequest{
		RequestId: "r-cancel-2",
		Op:        &wisperpb.FileRequest_Cancel{Cancel: &wisperpb.CancelRequest{CancelledRequestId: "r-slow"}},
	})
	late := transfers.expect(t, "an answer to the second cancel", func(e *wisperpb.FileEvent) bool {
		return e.GetRequestId() == "r-cancel-2"
	})
	if late.GetDone() == nil || late.GetDone().GetAffected() != 0 {
		t.Error("a cancel for an operation that already finished must still be answered, with nothing affected")
	}
}

// A handler that fails without saying anything would leave the panel holding a spinner
// forever, so the stream reports it instead.
func TestAFileHandlerThatSaysNothingStillTerminatesTheRequest(t *testing.T) {
	panel := startPanel(t)
	node := newFakeNode()
	node.fileHandler = func(context.Context, *wisperpb.FileRequest, FileEvents) error {
		return context.DeadlineExceeded
	}
	startNode(t, panel.credential(), node)

	transfers := fileStream(t, panel)
	transfers.send(t, &wisperpb.FileRequest{
		RequestId: "r-broken",
		Op:        &wisperpb.FileRequest_Stat{Stat: &wisperpb.StatPath{Path: "x"}},
	})

	failure := transfers.expect(t, "a failure for r-broken", func(e *wisperpb.FileEvent) bool {
		return e.GetRequestId() == "r-broken" && e.GetError() != nil
	})
	if failure.GetError().GetCode() != wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR {
		t.Errorf("code = %v, want an IO error", failure.GetError().GetCode())
	}
}
