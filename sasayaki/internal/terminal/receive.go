package terminal

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"sync/atomic"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/rpc"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The panel's half of the stream: keystrokes and window resizes.
//
// This is the goroutine that reads. It never sends - one gRPC stream has one sender, and
// that is the pump - and it never decides that the session is over; it records why it
// stopped and closes done, and the pump reads both.
//
// The two frame kinds are handled as the different things they are, which is the entire
// point of the message type. The predecessor could not tell a resize from a keystroke,
// so a phone rotating either did nothing or typed escape characters into the shell
// (design section 11.5).

// inbound is one session's receive loop.
type inbound struct {
	stream    rpc.TerminalStream
	sessionID string
	pty       Pty
	log       *slog.Logger

	// typedAt is when the customer last sent input, as wall-clock nanoseconds. Read by the
	// pump from another goroutine, hence the atomic; measured with time.Now rather than
	// with the host's injectable clock because the timer it is compared against is a real
	// one, and two clocks that disagree make a timeout fire at a moment nobody can explain.
	typedAt atomic.Int64

	// done is closed when this loop stops. panelGone is written before it closes, so the
	// pump may read it once done has fired and never before.
	done      chan struct{}
	panelGone bool
	cause     error
}

func newInbound(stream rpc.TerminalStream, sessionID string, pty Pty, log *slog.Logger) *inbound {
	receiving := &inbound{
		stream:    stream,
		sessionID: sessionID,
		pty:       pty,
		log:       log,
		done:      make(chan struct{}),
	}
	receiving.typedAt.Store(time.Now().UnixNano())
	return receiving
}

// lastInput is when the customer last typed. The idle timeout is measured from here.
func (i *inbound) lastInput() time.Time {
	return time.Unix(0, i.typedAt.Load())
}

// run reads frames until the stream or the pty ends.
func (i *inbound) run(ctx context.Context) {
	defer close(i.done)

	for {
		frame, err := i.stream.Recv()
		if err != nil {
			i.panelGone = true
			if !errors.Is(err, io.EOF) {
				i.cause = fmt.Errorf("terminal: read the panel's half of session %s: %w",
					i.sessionID, err)
			}
			return
		}

		// A frame for another session on this stream is a routing mistake somewhere above,
		// and the answer is to drop it. Typing one customer's keystrokes into another's
		// container because the ids did not match is the failure this field exists to
		// prevent (terminal.proto, TerminalFrame.session_id).
		if frame.GetSessionId() != i.sessionID {
			i.log.Warn("dropped a terminal frame addressed to another session",
				slog.String("addressed_to", frame.GetSessionId()))
			continue
		}

		if stop := i.apply(ctx, frame); stop {
			return
		}
	}
}

// apply performs one frame and reports whether the loop should stop.
func (i *inbound) apply(ctx context.Context, frame *wisperpb.TerminalFrame) bool {
	switch payload := frame.GetPayload().(type) {
	case *wisperpb.TerminalFrame_Data:
		return !i.typed(payload.Data)

	case *wisperpb.TerminalFrame_Resize:
		i.resize(ctx, payload.Resize)
		return false

	case nil:
		// A frame with no payload at all. It is not input, it is not a resize, and
		// guessing which it was meant to be is how bytes end up in the wrong place.
		i.log.Warn("dropped an empty terminal frame")
		return false

	default:
		// TerminalAttached and TerminalExit are node-to-panel frames. One arriving here
		// means the panel is confused about which way the stream runs, which is worth
		// saying out loud rather than absorbing.
		i.log.Warn("dropped a terminal frame the panel is not allowed to send",
			slog.String("payload", fmt.Sprintf("%T", payload)))
		return false
	}
}

// typed writes keystrokes to the pty and reports whether the pty is still usable.
func (i *inbound) typed(data []byte) bool {
	if len(data) == 0 {
		// A Data frame carrying nothing is not activity and writing it would be a syscall
		// that does nothing. It is not an error either: a browser that batches keystrokes
		// occasionally flushes an empty batch.
		return true
	}

	// Only input counts against the idle timeout. A resize does not: a page that is merely
	// open emits them when the window changes or the on-screen keyboard opens, so counting
	// them would keep a forgotten tab's shell alive forever, which is the exact thing the
	// timeout exists to stop (terminal.proto, StartTerminal.idle_timeout_seconds).
	i.typedAt.Store(time.Now().UnixNano())

	written, err := i.pty.Write(data)
	if err != nil {
		i.cause = fmt.Errorf("terminal: write %d bytes of input to the pty of session %s: %w",
			len(data), i.sessionID, err)
		return false
	}
	if written != len(data) {
		i.cause = fmt.Errorf("terminal: the pty of session %s took %d of %d bytes of input, so "+
			"the customer's keystrokes are no longer arriving whole", i.sessionID, written, len(data))
		return false
	}
	return true
}

// resize tells the pty its new shape.
//
// A failure is logged and the session carries on. The customer's shell still works at the
// old size, and killing a live session because a window changed shape would be a far worse
// answer than a screen that is drawn slightly wrong until the next resize.
func (i *inbound) resize(ctx context.Context, size *wisperpb.TerminalResize) {
	cols, rows := size.GetCols(), size.GetRows()
	if cols == 0 && rows == 0 {
		i.log.Warn("dropped a resize to nothing")
		return
	}
	if err := i.pty.Resize(ctx, cols, rows); err != nil {
		i.log.Warn("could not resize the pty",
			slog.Int("cols", int(cols)),
			slog.Int("rows", int(rows)),
			slog.String("error", err.Error()))
		return
	}
	i.log.Debug("resized the pty",
		slog.Int("cols", int(cols)),
		slog.Int("rows", int(rows)))
}
