package daemon

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/rpc"
	"github.com/furimeo/wisper/sasayaki/internal/runtime"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Which log subscriptions exist on this node, and what starts and stops one.
//
// Reading a container is next door in logtail.go; this is the register in front of it, and
// two things about the register are load-bearing.
//
// A reader hangs off the daemon's lifetime, not off the control stream that asked for it.
// StartLogStream is dispatched on the control stream's read goroutine with the stream's own
// context, and that context dies every time the tunnel blinks; a feed built on it would stop
// on a drop the client is about to reconnect through, and the customer would watch a pane
// that quietly stopped updating.
//
// And stopping is idempotent, because the last browser closing and the container exiting
// race each other. The panel sends StopLogStream for a subscription that finished by itself
// a moment earlier all the time; treating an unknown stream id as a problem would fill the
// log with complaints about the system working correctly.

// logStream is one open read of a container's output. It is an interface rather than
// *runtime.LogStream for the same reason terminal.Pty is: a concrete type over a hijacked
// Docker connection cannot be stood in for, and what is worth testing here - a container
// that exits mid-follow, a sink that is refusing chunks - needs no Docker at all.
type logStream interface {
	// Next is the following chunk, or io.EOF when the output has ended. io.EOF is not a
	// failure: for a follow it means the container exited, which is the moment the
	// subscription is closed.
	Next() (runtime.LogChunk, error)
	Close() error
}

// logReader is the container engine, as a log feed needs it.
type logReader interface {
	ContainerFor(ctx context.Context, workloadID string) (reconcile.Container, bool, error)
	Logs(ctx context.Context, containerID string, options runtime.LogOptions) (logStream, error)
}

// logSink is where chunks go. The boolean is not decoration: a full queue drops the chunk,
// and the next one that gets through carries dropped_bytes so the customer is told the gap
// is real rather than the application having gone quiet.
type logSink interface {
	SendLog(chunk *wisperpb.LogChunk) bool
}

// feed is one live subscription.
type feed struct {
	cancel context.CancelFunc
}

// logFeeds is the daemon's rpc.LogFeeds.
type logFeeds struct {
	// lifetime outlives any one control stream. Cancelled when the daemon stops.
	lifetime context.Context
	engine   logReader
	sink     logSink
	log      *slog.Logger
	now      func() time.Time

	mutex sync.Mutex
	open  map[string]*feed
}

var _ rpc.LogFeeds = (*logFeeds)(nil)

func newLogFeeds(lifetime context.Context, engine logReader, sink logSink, log *slog.Logger) *logFeeds {
	return &logFeeds{
		lifetime: lifetime,
		engine:   engine,
		sink:     sink,
		log:      log,
		now:      time.Now,
		open:     make(map[string]*feed),
	}
}

// StartLogStream begins pushing chunks for one subscription.
//
// Which sources reach here, and why the answers differ:
//
//   - A container is tailed from the engine (logtail.go).
//   - A build and a cron run are pushed by the command that owns them, tagged with the same
//     stream id the panel chose here. The panel opens the subscription before it sends
//     StartBuild precisely so the customer's log pane is open before the first line exists,
//     so there is nothing for this to start (build/logs.go).
//   - The daemon's own log is refused, and said so out loud rather than accepted and left
//     silent: it goes to the journal, and this daemon keeps no in-memory copy of it to
//     serve from.
func (f *logFeeds) StartLogStream(ctx context.Context, request *wisperpb.LogRequest) error {
	streamID := request.GetStreamId()
	if streamID == "" {
		return errors.New("the panel asked for a log stream with no stream id, and every chunk " +
			"has to carry one back")
	}

	switch request.GetSource() {
	case wisperpb.LogSource_LOG_SOURCE_CONTAINER:
		return f.tail(ctx, request)
	case wisperpb.LogSource_LOG_SOURCE_BUILD, wisperpb.LogSource_LOG_SOURCE_CRON:
		f.log.Debug("a subscription was opened for output the command that produces it pushes itself",
			slog.String("stream_id", streamID),
			slog.String("source", request.GetSource().String()))
		return nil
	case wisperpb.LogSource_LOG_SOURCE_SYSTEM:
		return errors.New("this node's own log is written to the systemd journal and is not held " +
			"in memory, so there is nothing here to send; read it with `journalctl -u sasayaki`")
	default:
		return fmt.Errorf("this node does not know the log source %s", request.GetSource())
	}
}

// replace registers a subscription, ending any earlier one under the same id.
//
// The panel reopens a subscription after a reconnect, and the second request may ask for a
// different `since`. Two readers on one stream id would interleave their chunks into one
// pane, so the older one is stopped rather than left running.
func (f *logFeeds) replace(streamID string, live *feed) {
	f.mutex.Lock()
	previous, existed := f.open[streamID]
	f.open[streamID] = live
	f.mutex.Unlock()

	if existed {
		f.log.Debug("a log subscription was reopened, so the previous reader was stopped",
			slog.String("stream_id", streamID))
		previous.cancel()
	}
}

// StopLogStream ends one subscription.
//
// Idempotent, and an unknown id is not a problem: the last browser closing and the container
// exiting race each other, so the panel routinely stops a subscription that already ended.
func (f *logFeeds) StopLogStream(streamID string, reason string) {
	f.mutex.Lock()
	live, running := f.open[streamID]
	if running {
		delete(f.open, streamID)
	}
	f.mutex.Unlock()

	if !running {
		return
	}
	f.log.Debug("closing a log subscription",
		slog.String("stream_id", streamID), slog.String("reason", reason))
	live.cancel()
}

// finished forgets a subscription that ended by itself, unless it has already been replaced
// by a newer reader under the same id.
func (f *logFeeds) finished(streamID string, live *feed) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	if f.open[streamID] == live {
		delete(f.open, streamID)
	}
}
