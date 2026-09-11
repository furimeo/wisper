package runtime

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"strconv"
	"time"

	"github.com/moby/moby/client"
)

// Reading a container's output.
//
// The engine multiplexes stdout and stderr into one connection with an eight-byte header
// in front of every write: one byte saying which stream, three padding, then a big-endian
// length. Anything that copies that connection to a socket without decoding it - which is
// exactly what the predecessor's terminal did with io.Copy - delivers those header bytes
// to the browser as text and loses the distinction between a crash and an access log.
//
// So this reads frames, and hands the caller one chunk at a time with the stream it came
// from and the moment the engine recorded for it. Chunks and not lines, deliberately: a
// build writes "installing..." and then thinks for ten seconds, and a reader that waited
// for a newline would make it look hung (stats.proto, LogChunk).
const (
	// The header the engine puts in front of every write on a multiplexed stream.
	frameHeaderBytes = 8

	// Bytes a single frame may claim. The engine's own writes are at most 16KiB, so this
	// is two orders of magnitude of headroom; it exists so that a corrupted or hostile
	// length field allocates a bounded buffer instead of the machine's memory.
	maxFrameBytes = 16 << 20

	// The stream byte for stderr. Stdout is 1 and stdin is 0, neither of which needs a
	// name here.
	stderrStream = 2
)

// LogOptions is what to read.
type LogOptions struct {
	// Lines of history before following. Zero or less is everything the driver still
	// holds, which is what a build's one-shot fetch wants.
	Tail int32
	// Keep the connection open and deliver output as it happens.
	Follow bool
	// Only output after this moment. Set when a browser reconnects, so a customer does
	// not get the last hundred lines twice.
	Since time.Time
}

// LogChunk is one write by the container.
type LogChunk struct {
	// Stderr distinguishes the crash from the access log. False is stdout.
	Stderr bool
	// Data as the container wrote it. Bytes and not a string: a log line can be invalid
	// UTF-8, and decoding belongs at the end of the path, once, in the browser.
	Data []byte
	// The engine's timestamp for the first byte. Zero when the driver did not record one.
	At time.Time
}

// LogStream is an open read of one container's output. Not safe for concurrent use: one
// reader per stream, which is what the subscription model above it already gives.
type LogStream struct {
	body      io.ReadCloser
	header    [frameHeaderBytes]byte
	multiplex bool
	raw       []byte
}

// Logs opens a container's output.
//
// The container is inspected first for one reason: a container with a tty has no
// multiplexing at all and its output arrives raw, so reading it as frames would return
// eight bytes of the customer's first log line interpreted as a length. Workloads created
// by this package never have a tty, but a container created by an older version - or by
// hand, during an incident - might.
func (d *Docker) Logs(ctx context.Context, containerID string, options LogOptions) (*LogStream, error) {
	inspected, err := d.api.ContainerInspect(ctx, containerID, client.ContainerInspectOptions{})
	if err != nil {
		return nil, fmt.Errorf("runtime: inspect the container %s before reading its logs: %w",
			containerID, err)
	}
	tty := inspected.Container.Config != nil && inspected.Container.Config.Tty

	request := client.ContainerLogsOptions{
		ShowStdout: true,
		ShowStderr: true,
		Follow:     options.Follow,
		// Asked for on the wire and stripped again below. The driver records the moment
		// each line was written, and without it a chunk that arrives after a reconnect
		// would be stamped with the time it was read instead of the time it happened.
		Timestamps: true,
		Tail:       "all",
	}
	if options.Tail > 0 {
		request.Tail = strconv.Itoa(int(options.Tail))
	}
	if !options.Since.IsZero() {
		// Seconds and nanoseconds, because a browser reconnecting a second after it
		// dropped would otherwise be given the whole of that second again.
		request.Since = fmt.Sprintf("%d.%09d", options.Since.Unix(), options.Since.Nanosecond())
	}

	body, err := d.api.ContainerLogs(ctx, containerID, request)
	if err != nil {
		return nil, fmt.Errorf("runtime: read the logs of the container %s: %w", containerID, err)
	}
	stream := &LogStream{body: body, multiplex: !tty}
	if tty {
		// Only a tty stream is read straight into a buffer; a multiplexed one is read
		// frame by frame and allocates exactly what each frame announces.
		stream.raw = make([]byte, 32<<10)
	}
	return stream, nil
}

// Next is the following chunk, or io.EOF when the container's output has ended.
//
// io.EOF is not a failure and callers must not report it as one: for a one-shot read it
// means the history is complete, and for a follow it means the container exited, which is
// exactly the moment the subscription is told to close (LogChunk.end).
func (s *LogStream) Next() (LogChunk, error) {
	if !s.multiplex {
		return s.nextRaw()
	}
	return s.nextFrame()
}

// nextFrame reads one multiplexed write.
func (s *LogStream) nextFrame() (LogChunk, error) {
	stderr, payload, err := readFrame(s.body, s.header[:])
	if err != nil {
		return LogChunk{}, err
	}
	at, data := splitTimestamp(payload)
	return LogChunk{Stderr: stderr, Data: data, At: at}, nil
}

// readFrame reads one of the engine's multiplexed writes: which stream it came from and
// what was written. io.EOF ends the stream, and a header cut in half is an end too - a
// container removed underneath a follow, a connection dropped mid-write - because there
// is nothing further to read either way.
//
// Shared with run.go, which reads exactly the same framing off an exec's connection.
func readFrame(source io.Reader, header []byte) (stderr bool, payload []byte, err error) {
	if _, err := io.ReadFull(source, header[:frameHeaderBytes]); err != nil {
		if err == io.ErrUnexpectedEOF {
			return false, nil, io.EOF
		}
		return false, nil, err
	}

	size := binary.BigEndian.Uint32(header[4:frameHeaderBytes])
	if size > maxFrameBytes {
		return false, nil, fmt.Errorf("runtime: the engine announced a %d-byte frame, which is "+
			"beyond anything it writes; the stream is not what it claims to be", size)
	}

	payload = make([]byte, size)
	if _, err := io.ReadFull(source, payload); err != nil {
		if err == io.ErrUnexpectedEOF {
			return false, nil, io.EOF
		}
		return false, nil, err
	}
	return header[0] == stderrStream, payload, nil
}

// nextRaw reads from a container that has a tty, where there is one stream and no framing.
func (s *LogStream) nextRaw() (LogChunk, error) {
	read, err := s.body.Read(s.raw)
	if read > 0 {
		payload := make([]byte, read)
		copy(payload, s.raw[:read])
		at, data := splitTimestamp(payload)
		return LogChunk{Data: data, At: at}, nil
	}
	if err == nil {
		err = io.EOF
	}
	return LogChunk{}, err
}

// Close ends the read. Safe to call on a stream that has already reached its end.
func (s *LogStream) Close() error {
	if err := s.body.Close(); err != nil {
		return fmt.Errorf("runtime: close a log stream: %w", err)
	}
	return nil
}

// splitTimestamp takes the engine's RFC 3339 prefix off a log entry.
//
// It is only removed when it really is one. A container that writes its own timestamps -
// and plenty do - must not have the first token of its own output eaten, so anything that
// does not parse is left exactly as the container wrote it and reported with no time.
func splitTimestamp(payload []byte) (time.Time, []byte) {
	space := -1
	for index, character := range payload {
		if character == ' ' {
			space = index
			break
		}
		// A timestamp has no room to be this long, and scanning a whole 16KiB line
		// looking for one would be work done on every chunk for nothing.
		if index > 40 {
			return time.Time{}, payload
		}
	}
	if space <= 0 {
		return time.Time{}, payload
	}
	at, err := time.Parse(time.RFC3339Nano, string(payload[:space]))
	if err != nil {
		return time.Time{}, payload
	}
	return at.UTC(), payload[space+1:]
}
