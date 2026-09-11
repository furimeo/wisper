package terminal

import (
	"context"
	"fmt"
	"log/slog"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/rpc"
	"github.com/furimeo/wisper/sasayaki/internal/runtime"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// One session, from the first frame to the last.
//
// The stream is already open when Serve is called: the panel put a StartTerminal on the
// control stream and the node dialled back, because the node is the only side that can
// open a connection (internal/rpc, terminal_stream.go). So a failure to attach is reported
// by returning an error, which the control stream turns into a CommandResult the panel can
// show, rather than by sending frames on a stream the panel has not joined to a browser
// yet.
//
// Once the pty is open the opposite holds: everything that happens is reported as frames,
// and Serve returns nil. A shell that exits 130 is a session that worked.

// session is one attached terminal and everything the goroutines below share.
//
// stream and frames are the same connection seen from the two directions that use it: the
// receive loop reads from the stream, and the pump - the only goroutine allowed to send -
// writes through the framer, which is the only thing in this package that can build a
// frame.
type session struct {
	host        *Host
	id          string
	workloadID  string
	containerID string
	pty         Pty
	stream      rpc.TerminalStream
	frames      *framer
	log         *slog.Logger
}

// Serve owns the stream for one terminal session.
func (h *Host) Serve(ctx context.Context, request *wisperpb.StartTerminal, stream rpc.TerminalStream) error {
	// Verbatim, not trimmed. This id is compared byte for byte against the one on every
	// frame the panel sends, so a session that quietly used a tidied-up copy would drop
	// every keystroke as belonging to somebody else. Whitespace is only ever looked at to
	// decide whether there is an id at all.
	sessionID := request.GetSessionId()
	workloadID := request.GetWorkloadId()

	if strings.TrimSpace(sessionID) == "" {
		return fmt.Errorf("terminal: a terminal was requested for workload %q with no session id, "+
			"and every frame on the stream has to carry one", workloadID)
	}
	if strings.TrimSpace(workloadID) == "" {
		return fmt.Errorf("terminal: session %s names no workload, so there is no container to "+
			"open a shell in", sessionID)
	}

	release, err := h.live.claim(sessionID)
	if err != nil {
		return err
	}
	defer release()

	log := h.log.With(
		slog.String("session_id", sessionID),
		slog.String("workload_id", workloadID))

	// From here on the panel is told the stream is over whatever happens. A half left open
	// is a browser holding a terminal that will never say anything again.
	frames := &framer{sessionID: sessionID, stream: stream}
	defer frames.closeSend(log)

	pty, err := h.attach(ctx, request, workloadID, log)
	if err != nil {
		return err
	}
	// Closing the pty is the cleanup. It takes the process's terminal away, which is what
	// makes a shell reading from a closed stdin exit; there is no Engine API call that
	// kills an exec, so this is the whole of what disconnecting can do.
	defer pty.Close()

	cols, rows := pty.Size()
	live := &session{
		host:        h,
		id:          sessionID,
		workloadID:  workloadID,
		containerID: pty.ContainerID(),
		pty:         pty,
		stream:      stream,
		frames:      frames,
		log:         log.With(slog.String("container", pty.ContainerID())),
	}

	// The size is echoed back after the engine clamped it: a browser can ask for a shape a
	// pty will not take, and a client whose grid disagrees with the one the process is
	// drawing on renders a mess (terminal.proto, TerminalAttached).
	if err := frames.attached(workloadID, live.containerID, cols, rows, h.now()); err != nil {
		return err
	}
	live.log.Info("terminal attached",
		slog.Int("cols", int(cols)),
		slog.Int("rows", int(rows)))

	return live.pump(ctx, request)
}

// attach finds the container and opens a pty in it.
//
// The three refusals are deliberately different sentences. "No container" is the
// customer's service not being deployed here, "not running" is it being stopped, and an
// error from the engine is neither - it is the node being unable to answer, and saying
// "your service is not running" in that case would be a guess presented as a fact
// (AGENTS.md section 4.5).
func (h *Host) attach(ctx context.Context, request *wisperpb.StartTerminal, workloadID string, log *slog.Logger) (Pty, error) {
	found, exists, err := h.engine.Container(ctx, workloadID)
	if err != nil {
		return nil, fmt.Errorf("terminal: look for the container of workload %s: %w", workloadID, err)
	}
	if !exists {
		return nil, fmt.Errorf("terminal: workload %s has no container on this node, so there is "+
			"nothing to open a shell in", workloadID)
	}
	if !found.Running {
		return nil, fmt.Errorf("terminal: the container of workload %s is not running (%s), and a "+
			"shell cannot be started in a stopped container", workloadID, found.Status)
	}

	command := h.commandFor(ctx, found.ID, request, log)
	pty, err := h.engine.Open(ctx, found.ID, runtime.ExecOptions{
		Command:    command,
		WorkingDir: request.GetWorkingDir(),
		User:       request.GetUser(),
		Env:        environmentFor(request),
		Cols:       request.GetInitialCols(),
		Rows:       request.GetInitialRows(),
	})
	if err != nil {
		return nil, fmt.Errorf("terminal: open %s in the container %s of workload %s: %w",
			strings.Join(command, " "), found.ID, workloadID, err)
	}
	return pty, nil
}
