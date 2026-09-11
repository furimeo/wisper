package daemon

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/runtime"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Reading one container's output into one subscription.
//
// The registry that decides which subscriptions exist is next door in logs.go; this is the
// part that runs on a goroutine for as long as a browser is watching, and it has three
// jobs beyond copying bytes.
//
// It never blocks on the uplink. A queue that is full drops the chunk and the reader
// carries on, because a log feed that waited would eventually block the container writing
// to it - a customer's application stopped by the act of watching it. What was dropped is
// counted and reported on the next chunk that gets through, so the gap is something the
// customer is told about rather than something they have to notice.
//
// It announces the end. A container that exits closes the engine's stream, and the panel is
// sent a chunk with `end` so it can release the subscription instead of holding one open for
// output that is never coming.
//
// And it does not announce the end when the panel is the one that stopped it. That
// subscription has already been let go, and a frame arriving for it is a frame the panel has
// to work out how to ignore.

// tail opens the engine's log stream and starts the goroutine that drains it.
//
// The stream is opened here rather than on the goroutine so that "there is no container for
// that workload" is answered as an error to the command, which the panel turns into a line
// the customer can read - instead of an empty pane and a subscription nobody ever closes.
func (f *logFeeds) tail(ctx context.Context, request *wisperpb.LogRequest) error {
	workloadID := request.GetSubjectId()
	if workloadID == "" {
		return errors.New("the panel asked for a container's output without saying which workload")
	}

	container, found, err := f.engine.ContainerFor(ctx, workloadID)
	if err != nil {
		return fmt.Errorf("look for the container of workload %s: %w", workloadID, err)
	}
	if !found {
		return fmt.Errorf("no container for workload %s exists on this node yet", workloadID)
	}

	options := runtime.LogOptions{Tail: request.GetTailLines(), Follow: request.GetFollow()}
	if since := request.GetSince(); since.IsValid() {
		// Only when the panel really sent one. An absent timestamp reads back as the epoch
		// rather than as the zero time, and asking the engine for everything since 1970 is
		// not the same request as asking it for everything.
		options.Since = since.AsTime()
	}

	reading, cancel := context.WithCancel(f.lifetime)
	stream, err := f.engine.Logs(reading, container.ID, options)
	if err != nil {
		cancel()
		return fmt.Errorf("read the output of workload %s: %w", workloadID, err)
	}

	live := &feed{cancel: cancel}
	f.replace(request.GetStreamId(), live)
	go f.pump(reading, request, live, stream)
	return nil
}

// pump reads until the output ends, the subscription is stopped, or the daemon does.
func (f *logFeeds) pump(ctx context.Context, request *wisperpb.LogRequest, live *feed, stream logStream) {
	defer stream.Close()
	defer f.finished(request.GetStreamId(), live)

	// Bytes the sink refused since the last chunk that got through, reported on the next
	// one so a customer sees "42 bytes missing" rather than a silent gap.
	var dropped int64

	for {
		chunk, err := stream.Next()
		switch {
		case ctx.Err() != nil:
			// Stopped on purpose. No end frame: the panel already let this subscription go,
			// and an end frame for one it has forgotten is a frame it has to ignore.
			return
		case errors.Is(err, io.EOF):
			// The history is complete, or the container exited under a follow. Both are the
			// subscription finishing normally.
			f.close(request, dropped, "")
			return
		case err != nil:
			f.close(request, dropped, err.Error())
			return
		}

		if len(chunk.Data) == 0 {
			continue
		}
		if f.sink.SendLog(f.chunk(request, chunk, dropped)) {
			dropped = 0
			continue
		}
		dropped += int64(len(chunk.Data))
	}
}

// chunk is one of the engine's writes, addressed to the subscription that asked for it.
func (f *logFeeds) chunk(request *wisperpb.LogRequest, read runtime.LogChunk, dropped int64) *wisperpb.LogChunk {
	at := read.At
	if at.IsZero() {
		// The log driver recorded no timestamp. The moment it was read is a worse answer
		// than the moment it was written and a much better one than none, which the browser
		// would render as the epoch.
		at = f.now()
	}

	kind := wisperpb.LogStreamKind_LOG_STREAM_KIND_STDOUT
	if read.Stderr {
		kind = wisperpb.LogStreamKind_LOG_STREAM_KIND_STDERR
	}

	return &wisperpb.LogChunk{
		StreamId:     request.GetStreamId(),
		Source:       request.GetSource(),
		SubjectId:    request.GetSubjectId(),
		Kind:         kind,
		Data:         read.Data,
		At:           timestamppb.New(at),
		DroppedBytes: dropped,
	}
}

// close tells the panel the source ended, so it can release the subscription without having
// to send a stop command for something that is already over.
func (f *logFeeds) close(request *wisperpb.LogRequest, dropped int64, failure string) {
	final := &wisperpb.LogChunk{
		StreamId:     request.GetStreamId(),
		Source:       request.GetSource(),
		SubjectId:    request.GetSubjectId(),
		Kind:         wisperpb.LogStreamKind_LOG_STREAM_KIND_STDOUT,
		At:           timestamppb.New(f.now()),
		DroppedBytes: dropped,
		End:          true,
	}
	if failure != "" {
		final.Kind = wisperpb.LogStreamKind_LOG_STREAM_KIND_STDERR
		final.Data = []byte("this node stopped reading the output: " + failure + "\n")
		f.log.Warn("a log subscription ended on a failure",
			slog.String("stream_id", request.GetStreamId()),
			slog.String("subject", request.GetSubjectId()),
			slog.String("error", failure))
	}
	f.sink.SendLog(final)
}

// containerLogs adapts the node's container runtime to logReader.
//
// One method needs widening - Logs returns a *runtime.LogStream, and an interface method
// cannot return something a test can stand in for unless the interface says so - and the
// other is a straight forward.
func containerLogs(docker *runtime.Docker) logReader { return dockerLogs{docker: docker} }

type dockerLogs struct {
	docker *runtime.Docker
}

func (d dockerLogs) ContainerFor(ctx context.Context, workloadID string) (reconcile.Container, bool, error) {
	return d.docker.ContainerFor(ctx, workloadID)
}

// Logs assigns the result before returning it rather than forwarding in one line: a nil
// *runtime.LogStream put straight into a logStream is an interface that is not nil, and a
// caller's error check that does not fire.
func (d dockerLogs) Logs(ctx context.Context, containerID string, options runtime.LogOptions) (logStream, error) {
	stream, err := d.docker.Logs(ctx, containerID, options)
	if err != nil {
		return nil, err
	}
	return stream, nil
}
