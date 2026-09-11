package runtime

import (
	"context"
	"fmt"
	"time"

	"github.com/moby/moby/client"
)

// A pty inside a customer's container: the only shell wisper offers.
//
// There is no SSH and no SFTP anywhere in this platform, so this is it, and most of the
// people using it are holding a phone. What that means here is that the session exposes
// resize as a first-class operation and the exit code as a value somebody returns -
// rather than as bytes on the same channel as the output, which is how the predecessor
// lost both (design section 11.5). Framing the stream is the terminal package's job;
// giving it something that can be framed is this one's.
const (
	// The size a session gets when the browser has not said. Every terminal emulator
	// ever written assumes this when it is told nothing.
	defaultCols uint32 = 80
	defaultRows uint32 = 24

	// Bounds on what a browser may ask for. A phone rotating produces small numbers and
	// a maximised window on a large screen produces a few hundred; anything outside this
	// is a bug or an attempt to make the engine allocate, and clamping is better than
	// refusing because the customer still gets a working shell.
	maxCols uint32 = 1000
	maxRows uint32 = 1000

	// How long Wait keeps asking the engine for an exit code after the stream has ended.
	// The exec's own bookkeeping lands a moment after its output does; a second is three
	// orders of magnitude more than that gap has ever been.
	exitPollFor   = 2 * time.Second
	exitPollEvery = 20 * time.Millisecond
)

// ExecOptions is one interactive session.
type ExecOptions struct {
	// argv for the process to run. Empty means the image's own shell, which the panel
	// resolves rather than the node guessing - so a customer sees the same shell twice
	// running.
	Command []string
	// Directory to start in. Empty is the image's working directory.
	WorkingDir string
	// uid[:gid] inside the container. Empty is the image's own user. A terminal that is
	// root when the workload is not lets a customer create files their own application
	// cannot read, so the panel sends this deliberately.
	User string
	// Extra variables for this session only: TERM, LANG, COLUMNS. The workload's own
	// environment is already there.
	Env []string
	// The browser's size at open time, so the first prompt is not drawn at 80x24 and
	// then immediately redrawn.
	Cols uint32
	Rows uint32
}

// Session is an attached pty. Read is the container's output, Write is keystrokes.
//
// One goroutine reading and one writing is fine and is what a terminal does; two readers
// is not, and neither is anything else.
type Session struct {
	docker      *Docker
	execID      string
	containerID string
	attached    client.HijackedResponse
	cols        uint32
	rows        uint32
}

// Exec opens a pty in a running container.
//
// The size it was actually given is on the returned session, because it may not be the
// size that was asked for: a browser can request something the pty will not take, and the
// client has to be told what it got so its own grid matches (TerminalAttached).
func (d *Docker) Exec(ctx context.Context, containerID string, options ExecOptions) (*Session, error) {
	cols := clamp(options.Cols, defaultCols, maxCols)
	rows := clamp(options.Rows, defaultRows, maxRows)

	created, err := d.api.ExecCreate(ctx, containerID, client.ExecCreateOptions{
		Cmd:        options.Command,
		WorkingDir: options.WorkingDir,
		User:       options.User,
		Env:        options.Env,

		// A pty, so the shell line-edits, curses applications draw and Ctrl-C reaches
		// the foreground process group. It also means one undifferentiated output
		// stream, which is correct here: a terminal has never distinguished stdout from
		// stderr and a customer expecting one would be surprised by two.
		TTY:         true,
		ConsoleSize: client.ConsoleSize{Height: uint(rows), Width: uint(cols)},

		AttachStdin:  true,
		AttachStdout: true,
		AttachStderr: true,

		// Never. A privileged exec into an unprivileged container hands a customer the
		// host, and there is no request field that could ask for it.
		Privileged: false,
	})
	if err != nil {
		return nil, fmt.Errorf("runtime: open a terminal in the container %s: %w", containerID, err)
	}

	attached, err := d.api.ExecAttach(ctx, created.ID, client.ExecAttachOptions{
		TTY:         true,
		ConsoleSize: client.ConsoleSize{Height: uint(rows), Width: uint(cols)},
	})
	if err != nil {
		return nil, fmt.Errorf("runtime: attach to the terminal in the container %s: %w", containerID, err)
	}

	return &Session{
		docker:      d,
		execID:      created.ID,
		containerID: containerID,
		attached:    attached.HijackedResponse,
		cols:        cols,
		rows:        rows,
	}, nil
}

// ContainerID is the container the pty lives in, so a session in the audit log can be
// matched with what Docker saw.
func (s *Session) ContainerID() string { return s.containerID }

// Size is what the pty was actually given, after clamping.
func (s *Session) Size() (cols, rows uint32) { return s.cols, s.rows }

// Read is output from the pty. io.EOF means the process ended.
func (s *Session) Read(p []byte) (int, error) { return s.attached.Reader.Read(p) }

// Write is keystrokes going in.
func (s *Session) Write(p []byte) (int, error) { return s.attached.Conn.Write(p) }

// Resize tells the pty its new shape. On a phone this happens every time the on-screen
// keyboard opens, so it is an ordinary operation rather than an unusual one.
func (s *Session) Resize(ctx context.Context, cols, rows uint32) error {
	cols = clamp(cols, defaultCols, maxCols)
	rows = clamp(rows, defaultRows, maxRows)

	_, err := s.docker.api.ExecResize(ctx, s.execID, client.ExecResizeOptions{
		Height: uint(rows), Width: uint(cols),
	})
	if err != nil {
		return fmt.Errorf("runtime: resize the terminal in the container %s to %dx%d: %w",
			s.containerID, cols, rows, err)
	}
	s.cols, s.rows = cols, rows
	return nil
}

// CloseInput signals end-of-file to the process, which is what Ctrl-D means and what a
// customer piping something in expects when it runs out.
func (s *Session) CloseInput() error {
	if err := s.attached.CloseWrite(); err != nil {
		return fmt.Errorf("runtime: close the input of the terminal in %s: %w", s.containerID, err)
	}
	return nil
}

// Wait is the exit status once the process has finished.
//
// Called after Read has returned io.EOF. The engine's own record of the exec settles a
// moment after the stream closes, so this asks again for a short while rather than
// reporting the zero that a too-early inspect would give - and a terminal that shows
// "exited 0" for a shell that was killed is the failure this whole message type exists to
// prevent.
func (s *Session) Wait(ctx context.Context) (int, error) {
	deadline := s.docker.now().Add(exitPollFor)
	for {
		inspected, err := s.docker.api.ExecInspect(ctx, s.execID, client.ExecInspectOptions{})
		if err != nil {
			return 0, fmt.Errorf("runtime: ask how the terminal in %s ended: %w", s.containerID, err)
		}
		if !inspected.Running {
			return inspected.ExitCode, nil
		}
		if s.docker.now().After(deadline) {
			return 0, fmt.Errorf("runtime: the terminal process in %s was still running %s after "+
				"its output ended", s.containerID, exitPollFor)
		}
		select {
		case <-ctx.Done():
			return 0, ctx.Err()
		case <-time.After(exitPollEvery):
		}
	}
}

// Close hangs up.
//
// It does not kill the process, because there is no Engine API call that does: an exec
// ends when its process ends. What closing the connection does is take away its
// terminal, at which point a shell reading from a closed stdin exits on its own. Anything
// that does not is the reason StartTerminal carries a maximum duration.
func (s *Session) Close() {
	s.attached.Close()
}

// clamp keeps a browser's idea of its own size inside what a pty will take.
func clamp(value, fallback, highest uint32) uint32 {
	if value == 0 {
		return fallback
	}
	if value > highest {
		return highest
	}
	return value
}
