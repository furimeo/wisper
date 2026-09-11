package build

import (
	"bytes"
	"fmt"
	"sync"
	"time"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Build output on its way to the browser that is already watching.
//
// The panel starts the subscription before it sends StartBuild, so the customer's log
// panel is open before the first line exists. Everything this package emits - the
// daemon's own narration as well as the compiler's - goes through one of these, tagged
// with the stream id the panel chose.
//
// Line by line rather than by arbitrary chunk. The engine hands over whatever happened to
// be in the pipe, which cuts lines in half and joins two together, and a browser rendering
// those directly shows a build log that reassembles itself as it scrolls. So a partial
// tail is held until its newline arrives - with two escapes, because holding forever is
// worse than a torn line: a carriage return flushes, which is how a progress bar gets
// through, and so does a tail that grows past a screen's worth.

const (
	// heldLineLimit is how much of an unterminated line is buffered before it is sent
	// anyway. A build tool that prints a spinner and never a newline still reaches the
	// customer, and a hostile one cannot make the daemon hold a gigabyte.
	heldLineLimit = 8 << 10

	// chunkLimit bounds one LogChunk. Well under the gRPC message limit, and small enough
	// that a single enormous line is delivered in pieces the browser can render as they
	// arrive rather than all at once at the end.
	chunkLimit = 32 << 10
)

// buildLog is one build's output stream. Safe for concurrent use: the container's stdout
// and stderr are read by two goroutines and the daemon narrates from a third.
type buildLog struct {
	sink     LogSink
	streamID string
	buildID  string
	now      func() time.Time

	mutex sync.Mutex
	// held is the unterminated tail of each stream, keyed by kind so a half-written stderr
	// line is not completed by the next stdout one.
	held map[wisperpb.LogStreamKind][]byte
	// dropped counts bytes the sink refused since the last chunk that got through, so the
	// customer is told the gap is real rather than the build having gone quiet.
	dropped int64
	// ended guards against a second end frame, which would leave the panel holding a
	// subscription it has already released.
	ended bool
}

func newBuildLog(sink LogSink, streamID, buildID string, now func() time.Time) *buildLog {
	return &buildLog{
		sink:     sink,
		streamID: streamID,
		buildID:  buildID,
		now:      now,
		held:     make(map[wisperpb.LogStreamKind][]byte, 2),
	}
}

// stdout and stderr are io.Writers for the two halves of a container's output.
func (l *buildLog) stdout() *logWriter {
	return &logWriter{log: l, kind: wisperpb.LogStreamKind_LOG_STREAM_KIND_STDOUT}
}

func (l *buildLog) stderr() *logWriter {
	return &logWriter{log: l, kind: wisperpb.LogStreamKind_LOG_STREAM_KIND_STDERR}
}

// say is the daemon's own narration: which stage started, what it is cloning, why it
// stopped. It always ends in a newline, so it can never be joined onto a compiler's
// half-written line.
func (l *buildLog) say(format string, arguments ...any) {
	l.write(wisperpb.LogStreamKind_LOG_STREAM_KIND_STDOUT, []byte(fmt.Sprintf(format, arguments...)+"\n"))
}

// write takes output as it arrives and emits whole lines.
func (l *buildLog) write(kind wisperpb.LogStreamKind, payload []byte) {
	if len(payload) == 0 {
		return
	}
	l.mutex.Lock()
	defer l.mutex.Unlock()

	buffer := append(l.held[kind], payload...)
	for {
		cut := bytes.IndexAny(buffer, "\n\r")
		if cut < 0 {
			break
		}
		l.emit(kind, buffer[:cut+1])
		buffer = buffer[cut+1:]
	}

	if len(buffer) >= heldLineLimit {
		l.emit(kind, buffer)
		buffer = nil
	}
	if len(buffer) == 0 {
		delete(l.held, kind)
		return
	}
	// Copied out of the slice that came from the reader, which reuses its buffer.
	l.held[kind] = append([]byte(nil), buffer...)
}

// flush sends whatever is held without waiting for a newline. Called at every stage
// boundary, so a command that exited without a trailing newline still has its last words
// delivered before the next stage's first line.
func (l *buildLog) flush() {
	l.mutex.Lock()
	defer l.mutex.Unlock()
	l.flushLocked()
}

func (l *buildLog) flushLocked() {
	for kind, buffer := range l.held {
		if len(buffer) > 0 {
			l.emit(kind, buffer)
		}
		delete(l.held, kind)
	}
}

// end closes the subscription. The panel releases it on this frame rather than waiting for
// a stop command, because the browser watching a finished build has nothing more to wait
// for.
func (l *buildLog) end() {
	l.mutex.Lock()
	defer l.mutex.Unlock()
	if l.ended {
		return
	}
	l.ended = true
	l.flushLocked()
	l.offer(&wisperpb.LogChunk{
		StreamId:     l.streamID,
		Source:       wisperpb.LogSource_LOG_SOURCE_BUILD,
		SubjectId:    l.buildID,
		Kind:         wisperpb.LogStreamKind_LOG_STREAM_KIND_STDOUT,
		At:           timestamppb.New(l.now()),
		DroppedBytes: l.dropped,
		End:          true,
	})
}

// emit sends one line, split if it is longer than a chunk. Called with the mutex held.
func (l *buildLog) emit(kind wisperpb.LogStreamKind, line []byte) {
	for len(line) > 0 {
		size := min(len(line), chunkLimit)
		l.offer(&wisperpb.LogChunk{
			StreamId:  l.streamID,
			Source:    wisperpb.LogSource_LOG_SOURCE_BUILD,
			SubjectId: l.buildID,
			Kind:      kind,
			Data:      append([]byte(nil), line[:size]...),
			At:        timestamppb.New(l.now()),
		})
		line = line[size:]
	}
}

// offer hands one chunk to the sink and remembers what it refused. Called with the mutex
// held.
func (l *buildLog) offer(chunk *wisperpb.LogChunk) {
	if l.streamID == "" {
		// The panel did not ask for a log stream. Nothing to push to, and no reason to
		// pretend: the build's outcome still travels in the CommandResult.
		return
	}
	chunk.DroppedBytes = l.dropped
	if !l.sink.SendLog(chunk) {
		l.dropped += int64(len(chunk.GetData()))
		return
	}
	l.dropped = 0
}

// logWriter is one half of a container's output as an io.Writer.
type logWriter struct {
	log  *buildLog
	kind wisperpb.LogStreamKind
}

// Write never fails. A log feed that cannot keep up is a gap the chunk after it reports,
// not a reason to fail the build that produced the line.
func (w *logWriter) Write(payload []byte) (int, error) {
	w.log.write(w.kind, payload)
	return len(payload), nil
}
