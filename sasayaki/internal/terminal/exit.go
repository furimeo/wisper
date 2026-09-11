package terminal

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"time"
)

// How a session ends, and how the panel is told.
//
// This file is the answer to the second half of the predecessor's terminal bug. Output had
// nowhere to say "and then it exited 130", so a shell that was killed, a shell that
// finished and a connection that dropped all looked identical: a terminal that stopped
// responding. Every path out of a session below produces a TerminalExit, and every one of
// them is either a real exit code or a stated reason.
//
// The division is the one terminal.proto draws. A session that ended because its process
// ended reports that process's status and no reason. A session ended by the platform - the
// idle timeout, the duration ceiling, the panel hanging up, the node shutting down, the
// container dying underneath it - reports killedCode and says which, because "exited 0"
// for a shell nobody exited is worse than no answer at all.

const (
	// What a session reports when the platform ended it rather than the process. 137 is
	// 128+SIGKILL, which reads as "it was killed" to anybody who has seen a shell before -
	// and it is honest, because taking a process's terminal away is the closest thing to a
	// kill the Engine API offers. The reason field carries what actually happened.
	killedCode int32 = 137

	// How long to keep asking the engine how the process ended once its output has
	// stopped. The engine's record of an exec settles a moment after the stream closes;
	// this is the ceiling on that moment, not an expectation of it.
	exitWaitTimeout = 5 * time.Second
)

const (
	reasonPanelClosed  = "the panel closed the terminal"
	reasonShuttingDown = "the node closed the terminal while shutting down"
	reasonInputLost    = "the connection to the pty stopped accepting input"
)

func reasonIdle(after time.Duration) string {
	return fmt.Sprintf("no input for %s, so the terminal was closed", after)
}

func reasonTooLong(after time.Duration) string {
	return fmt.Sprintf("the terminal reached its %s limit", after)
}

// processEnded is the ordinary ending: the shell exited and the customer should see why.
func (s *session) processEnded(ctx context.Context, cause error) error {
	code, reason := s.exitStatus(ctx, cause)
	return s.finish(code, reason, false)
}

// endedByPlatform is every other ending.
//
// tolerant says that the panel is already known to be gone, so failing to send the exit
// frame is expected rather than a fault: there is nobody left to tell, and returning an
// error would have the control stream report a session that worked as one that failed.
func (s *session) endedByPlatform(reason string, tolerant bool) error {
	return s.finish(killedCode, reason, tolerant)
}

// exitStatus works out what to report for a process whose output has ended.
func (s *session) exitStatus(ctx context.Context, cause error) (int32, string) {
	if cause != nil && !errors.Is(cause, io.EOF) {
		// The read failed rather than ending. Worth recording, but not worth reporting
		// instead of the exit code: the engine usually still knows how the process
		// finished, and that is the number the customer needs.
		s.log.Debug("the pty output ended with an error",
			slog.String("error", cause.Error()))
	}

	// Deliberately detached from the session's context. The reason this session is ending
	// may well be that the context was cancelled, and asking the engine for the exit code
	// with an already-cancelled context would lose the one number this whole message type
	// exists to carry.
	asking, cancel := context.WithTimeout(context.WithoutCancel(ctx), exitWaitTimeout)
	defer cancel()

	code, err := s.pty.Wait(asking)
	if err == nil {
		return int32(code), ""
	}

	s.log.Debug("could not read the exit status of the shell",
		slog.String("error", err.Error()))
	return killedCode, s.whyItStopped(asking, err)
}

// whyItStopped explains an exec whose exit status the engine would not give up.
//
// On a live node this has one cause far more often than all the others: the container went
// away underneath the session, taking the exec record with it. So the container is asked
// about before a guess is made - and when the engine cannot answer that either, the answer
// says the node does not know rather than inventing a cause, because "the container
// stopped" is a sentence a customer will act on and it must never be a guess (AGENTS.md
// section 4.5).
func (s *session) whyItStopped(ctx context.Context, waitErr error) string {
	found, exists, err := s.host.engine.Container(ctx, s.workloadID)
	switch {
	case err != nil:
		return fmt.Sprintf("the shell ended and the node could not tell how: %s", waitErr)
	case !exists:
		return "the container was removed while the terminal was open"
	case !found.Running:
		return fmt.Sprintf("the container stopped while the terminal was open (%s)", found.Status)
	default:
		return fmt.Sprintf("the shell ended and the node could not tell how: %s", waitErr)
	}
}

// finish sends the last frame.
func (s *session) finish(code int32, reason string, tolerant bool) error {
	attributes := []any{slog.Int("code", int(code))}
	if reason != "" {
		attributes = append(attributes, slog.String("reason", reason))
	}
	s.log.Info("terminal session ended", attributes...)

	if err := s.frames.exit(code, reason, s.host.now()); err != nil {
		if tolerant {
			s.log.Debug("the panel had already gone, so it was not told how the session ended",
				slog.String("error", err.Error()))
			return nil
		}
		return err
	}
	return nil
}
