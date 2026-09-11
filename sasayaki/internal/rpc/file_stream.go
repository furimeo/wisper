package rpc

import (
	"context"
	"fmt"
	"log/slog"
	"sync"

	"google.golang.org/grpc"
	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The file manager's stream, which reads backwards and is not: the node opens the call
// and then receives the requests, because the panel cannot dial a node (design section
// 8.2). It stays open for the life of the daemon like the control stream does, and the
// supervisor reopens it on the same schedule.
//
// One stream carries every operation for this node, so nothing here may block on one:
// a customer downloading a two gigabyte archive must not stop the next directory
// listing, and a cancellation has to arrive while the thing being cancelled is still
// running. Every request therefore gets a goroutine and a context of its own, and the
// only shared thing is the writer, which is serialised.

// runFileOps opens the FileOp stream and serves requests until it fails or the daemon
// stops.
func (c *Client) runFileOps(ctx context.Context) error {
	stream, err := c.panel.FileOp(ctx)
	if err != nil {
		return fmt.Errorf("open file operation stream: %w", err)
	}

	events := &fileEventSink{stream: stream}
	operations := &fileOperations{running: make(map[string]context.CancelFunc)}
	// Whatever is still running when the stream ends cannot deliver its answer, so it
	// stops. A tree walk nobody is waiting for is disk the node's customers are paying
	// for twice.
	defer operations.cancelAll()

	var handlers sync.WaitGroup
	defer handlers.Wait()

	for {
		request, err := stream.Recv()
		if err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			return fmt.Errorf("file operation stream ended: %w", err)
		}

		if cancel := request.GetCancel(); cancel != nil {
			c.cancelFileOperation(request, cancel, operations, events)
			continue
		}

		requestID := request.GetRequestId()
		operationCtx, stop := context.WithCancel(ctx)
		if !operations.begin(requestID, stop) {
			stop()
			c.log.Warn("panel repeated a file request that is still running",
				slog.String("request_id", requestID))
			continue
		}

		handlers.Add(1)
		go func() {
			defer handlers.Done()
			defer operations.finish(requestID)
			defer stop()

			if err := c.handlers.Files.Handle(operationCtx, request, events); err != nil {
				// The handler could not even report a FileError. Say so on the stream
				// anyway: an operation with no terminal event leaves the panel holding
				// a spinner forever.
				c.log.Error("file operation failed",
					slog.String("request_id", requestID), slog.String("error", err.Error()))
				// If this send fails too the stream is gone, and the Recv loop above is
				// already on its way to reporting that.
				_ = events.Send(&wisperpb.FileEvent{
					RequestId: requestID,
					Result: &wisperpb.FileEvent_Error{Error: &wisperpb.FileError{
						Code:   wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR,
						Detail: err.Error(),
					}},
				})
			}
		}()
	}
}

// cancelFileOperation stops the target operation and answers the cancel request itself.
//
// Cancel is handled here rather than passed to the file package because the thing being
// cancelled is a goroutine this package started: the context is already in hand, and
// routing a cancellation through the handler that is busy being cancelled would need a
// second registry saying the same thing.
func (c *Client) cancelFileOperation(
	request *wisperpb.FileRequest,
	cancel *wisperpb.CancelRequest,
	operations *fileOperations,
	events *fileEventSink,
) {
	target := cancel.GetCancelledRequestId()
	stopped := operations.cancel(target)
	c.log.Info("cancelling a file operation",
		slog.String("request_id", target), slog.Bool("was_running", stopped))

	var affected int64
	if stopped {
		affected = 1
	}
	// The cancelled operation reports its own ending, with FILE_ERROR_CODE_CANCELLED,
	// when it notices its context is done. This answers only the cancel itself.
	_ = events.Send(&wisperpb.FileEvent{
		RequestId: request.GetRequestId(),
		Result: &wisperpb.FileEvent_Done{Done: &wisperpb.OperationDone{
			Affected:   affected,
			FinishedAt: timestamppb.Now(),
		}},
	})
}

// fileEventSink serialises writes from every concurrent operation onto the one stream.
type fileEventSink struct {
	mu     sync.Mutex
	stream grpc.BidiStreamingClient[wisperpb.FileEvent, wisperpb.FileRequest]
}

func (s *fileEventSink) Send(event *wisperpb.FileEvent) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.stream.Send(event)
}

// fileOperations is what is running right now, so a cancellation can find it.
type fileOperations struct {
	mu      sync.Mutex
	running map[string]context.CancelFunc
}

func (o *fileOperations) begin(requestID string, stop context.CancelFunc) bool {
	o.mu.Lock()
	defer o.mu.Unlock()
	if _, busy := o.running[requestID]; busy {
		return false
	}
	o.running[requestID] = stop
	return true
}

func (o *fileOperations) finish(requestID string) {
	o.mu.Lock()
	defer o.mu.Unlock()
	delete(o.running, requestID)
}

func (o *fileOperations) cancel(requestID string) bool {
	o.mu.Lock()
	stop, running := o.running[requestID]
	o.mu.Unlock()
	if !running {
		return false
	}
	stop()
	return true
}

func (o *fileOperations) cancelAll() {
	o.mu.Lock()
	stops := make([]context.CancelFunc, 0, len(o.running))
	for _, stop := range o.running {
		stops = append(stops, stop)
	}
	o.mu.Unlock()
	for _, stop := range stops {
		stop()
	}
}
