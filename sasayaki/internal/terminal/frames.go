package terminal

import (
	"fmt"
	"log/slog"
	"time"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/rpc"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Everything this node says on a terminal stream is built here.
//
// One file, three constructors, and no method anywhere that takes a naked byte slice and
// puts it on the wire. That is the whole fix for the bug in the design document: the
// predecessor's io.Copy could not have been written against this type, because there is
// nothing here to copy into (design section 11.5).
//
// The session id is stamped from one place, on the way out, for every frame. It costs a
// few bytes on a frame whose stream identity is already implied, and it turns a routing
// mistake at the far end into a dropped frame instead of a customer's keystrokes arriving
// in somebody else's container (terminal.proto, TerminalFrame.session_id).
//
// Not safe for concurrent use, and it must not be made so: a gRPC stream permits one
// sender, and the pump goroutine is it.
type framer struct {
	sessionID string
	stream    rpc.TerminalStream
	// broken records that the stream has already failed. A second Send on a dead stream
	// produces a second error saying nothing the first did not, and the interesting one is
	// the first.
	broken bool
}

// attached is the node's acknowledgement that the pty is open and bytes are about to flow.
// It is the first frame the panel sees on this stream and is what lets it join the stream
// to the browser that is waiting for it.
func (f *framer) attached(workloadID, containerID string, cols, rows uint32, at time.Time) error {
	return f.send(&wisperpb.TerminalFrame{
		Payload: &wisperpb.TerminalFrame_Attached{Attached: &wisperpb.TerminalAttached{
			WorkloadId:  workloadID,
			ContainerId: containerID,
			Cols:        cols,
			Rows:        rows,
			AttachedAt:  timestamppb.New(at),
		}},
	}, "the attachment")
}

// data is output from the process, exactly as it was written.
//
// Bytes and never a string, and not touched on the way past: a pty emits whatever the
// program writes, which is routinely an escape sequence split across a read boundary and
// occasionally not valid UTF-8 at all. Decoding is the browser's job, once, at the end.
func (f *framer) data(payload []byte) error {
	return f.send(&wisperpb.TerminalFrame{
		Payload: &wisperpb.TerminalFrame_Data{Data: payload},
	}, "output")
}

// exit is the last frame. Sending it is what turns a terminal that stopped into a terminal
// that says "exited 130".
func (f *framer) exit(code int32, reason string, at time.Time) error {
	return f.send(&wisperpb.TerminalFrame{
		Payload: &wisperpb.TerminalFrame_Exit{Exit: &wisperpb.TerminalExit{
			Code:    code,
			Reason:  reason,
			EndedAt: timestamppb.New(at),
		}},
	}, "the exit status")
}

func (f *framer) send(frame *wisperpb.TerminalFrame, what string) error {
	if f.broken {
		return fmt.Errorf("terminal: the stream of session %s is gone, so %s could not be sent",
			f.sessionID, what)
	}
	frame.SessionId = f.sessionID
	if err := f.stream.Send(frame); err != nil {
		f.broken = true
		return fmt.Errorf("terminal: send %s of session %s: %w", what, f.sessionID, err)
	}
	return nil
}

// closeSend ends this node's half of the stream, which is how the panel learns there will
// be no more output. Deferred by Serve so it happens on every path, including the ones
// where the session never attached at all.
func (f *framer) closeSend(log *slog.Logger) {
	if err := f.stream.CloseSend(); err != nil {
		log.Debug("could not close the terminal stream cleanly",
			slog.String("error", err.Error()))
	}
}
