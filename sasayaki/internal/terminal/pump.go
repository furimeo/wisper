package terminal

import (
	"context"
	"log/slog"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The loop that runs a session, and the only goroutine that sends.
//
// Everything a terminal has to be right about meets here: output goes out in order and
// before the exit frame, a keystroke and a resize are different events, an idle session is
// closed, a session that has run all day is closed regardless, and a process that outruns
// the browser is slowed down rather than buffered.
//
// The ordering guarantee is a consequence of the shape rather than of a lock. The output
// goroutine closes the chunk channel after its last chunk, and a closed channel yields
// what is still in it before it yields closed - so by the time this loop learns the pty
// has ended it has already sent every byte the process wrote.

// inputFailureGrace is how long the loop waits for the output side to finish after the pty
// has stopped accepting input.
//
// The two halves are the same connection, so input failing means the process is going
// away; giving the reader a moment lets the customer see the last of the output and get a
// real exit code instead of a 137. Bounded because the connection is already broken and
// waiting on it forever would pin a session slot.
const inputFailureGrace = 2 * time.Second

func (s *session) pump(ctx context.Context, request *wisperpb.StartTerminal) error {
	idle := s.host.idleFor(request)
	lifespan := s.host.durationFor(request)

	// Closed on the way out, which is what stops the output goroutine when it is blocked
	// handing over a chunk nobody is going to take.
	quit := make(chan struct{})
	defer close(quit)

	chunks := make(chan []byte, outputQueue)
	failed := make(chan error, 1)
	go readOutput(s.pty, chunks, failed, quit)

	// The receive loop outlives this function when the panel neither sends nor hangs up:
	// nothing here can cancel a gRPC call the rpc package opened, and Recv only returns
	// when the stream does. It holds a closed pty and a stream that is already finished, it
	// exits the moment either says anything, and the alternative - blocking Serve until the
	// panel closes - would pin the session's slot on a client that has stopped answering.
	receiving := newInbound(s.stream, s.id, s.pty, s.log)
	go receiving.run(ctx)

	idleTimer := time.NewTimer(idle)
	defer idleTimer.Stop()
	lifetime := time.NewTimer(lifespan)
	defer lifetime.Stop()

	// Set to nil once handled: receiving from a nil channel blocks forever, which is how a
	// one-shot case is written in a loop that keeps going afterwards.
	panelHalf := receiving.done
	var overrun <-chan time.Time

	for {
		select {
		case chunk, ok := <-chunks:
			if !ok {
				return s.processEnded(ctx, readFailure(failed))
			}
			if err := s.frames.data(chunk); err != nil {
				return err
			}

		case <-idleTimer.C:
			// The timer is armed once and re-armed against the last keystroke rather than
			// being reset by the receiving goroutine on every byte. One timer, owned by one
			// goroutine, and a customer typing quickly does not pay for a timer operation
			// per character.
			since := time.Since(receiving.lastInput())
			if since < idle {
				idleTimer.Reset(idle - since)
				continue
			}
			return s.endedByPlatform(reasonIdle(idle), false)

		case <-lifetime.C:
			return s.endedByPlatform(reasonTooLong(lifespan), false)

		case <-panelHalf:
			panelHalf = nil
			if receiving.panelGone {
				// The customer closed the tab, or the tunnel dropped. Either way the exit
				// frame is being sent into a stream that has already gone, so a failure to
				// send it is expected rather than reportable.
				s.logPanelGone(receiving.cause)
				return s.endedByPlatform(reasonPanelClosed, true)
			}
			// The pty refused input, so the process is on its way out. Keep going for a
			// moment: the output side is about to end too, and it carries the real exit
			// code and the last thing the program printed.
			s.log.Warn("the pty stopped accepting input",
				slog.String("error", describeCause(receiving.cause)))
			overrun = time.After(inputFailureGrace)

		case <-overrun:
			return s.endedByPlatform(reasonInputLost, false)

		case <-ctx.Done():
			return s.endedByPlatform(reasonShuttingDown, true)
		}
	}
}

// readFailure is why the pty stopped, if it said.
//
// Non-blocking: the output goroutine sends the reason before it closes the chunk channel,
// so by the time this is called the value is already there. The default arm exists so that
// a future change to that ordering costs a nil error rather than a session that hangs
// forever holding a slot.
func readFailure(failed <-chan error) error {
	select {
	case err := <-failed:
		return err
	default:
		return nil
	}
}

func (s *session) logPanelGone(cause error) {
	if cause == nil {
		s.log.Info("the panel closed the terminal")
		return
	}
	s.log.Info("the terminal stream to the panel ended",
		slog.String("error", cause.Error()))
}

// describeCause keeps a log line honest when the receive loop stopped without recording
// why. It cannot happen today - every path that stops it sets a cause or sets panelGone -
// and dereferencing a nil to find that out would take the daemon down with it.
func describeCause(cause error) string {
	if cause == nil {
		return "no reason was recorded"
	}
	return cause.Error()
}
