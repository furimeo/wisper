package terminal

import (
	"context"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/rpc"
	"github.com/furimeo/wisper/sasayaki/internal/runtime"
)

// What this package needs from the rest of the daemon, declared here by the consumer.
//
// There is one collaborator - the container engine - and it is behind an interface for a
// reason that is specific rather than habitual: the five behaviours this package has to be
// correct about are a resize arriving mid-session, an exit code reaching the browser,
// output surviving intact whatever bytes it contains, a slow reader not being allowed to
// exhaust the node, and a container dying underneath a live session. Not one of those is
// about Docker, and every one of them is intolerable to test against a real daemon: the
// last one would mean killing a container from inside a unit test, and the fourth would
// mean finding a way to make a real socket stop draining.
//
// The frames themselves are not abstracted. They are the contract with the panel, they are
// generated from terminal.proto, and a test that asserted against a local imitation of a
// TerminalFrame would be asserting against the wrong thing.

// Pty is one attached pseudo-terminal inside a customer's container.
//
// Read is the process's output and Write is keystrokes going the other way. One goroutine
// reading and one writing at a time, which is what a terminal does anyway; Resize, Wait
// and Close may be called from another.
type Pty interface {
	// ContainerID is the container the pty lives in, so a session in the audit log can be
	// matched with what Docker saw (TerminalAttached.container_id).
	ContainerID() string
	// Size is what the pty was actually given, after the engine clamped what the browser
	// asked for. The browser is told this rather than what it requested, so its own grid
	// matches the one the process is drawing on.
	Size() (cols, rows uint32)

	// Read is output from the process. io.EOF means it ended.
	Read(p []byte) (int, error)
	// Write is keystrokes. An error means the pty is gone, not that the byte was rejected.
	Write(p []byte) (int, error)

	// Resize tells the pty its new shape. On a phone this happens every time the on-screen
	// keyboard opens, so it is an ordinary operation and a failure is logged rather than
	// fatal.
	Resize(ctx context.Context, cols, rows uint32) error

	// Wait is the exit status, asked for after Read has ended. The engine's record of an
	// exec settles a moment after its output does, so an implementation is expected to
	// wait for it rather than report the zero an early inspect would give.
	Wait(ctx context.Context) (int, error)

	// Close hangs up. It does not kill the process - no Engine API call does - it takes
	// away its terminal, which is what makes a shell exit on its own.
	Close()
}

// Engine is the container engine, as a terminal needs it.
type Engine interface {
	// Container finds the container of one workload.
	//
	// A false second return means there is no such container, which is a fact: the
	// customer's service is not running and the terminal cannot open. An error means the
	// engine could not be asked, which is not a fact about anything, and the two are
	// answered differently - one is "start your service", the other is "come back in a
	// moment" (AGENTS.md section 4.5).
	Container(ctx context.Context, workloadID string) (reconcile.Container, bool, error)

	// Open attaches a pty. The size on the returned Pty is the one that was really given,
	// which may not be the one that was asked for.
	Open(ctx context.Context, containerID string, options runtime.ExecOptions) (Pty, error)

	// CanExecute reports whether a command can be started in the container at all, by
	// running it non-interactively and looking at the exit status. It is how the shell
	// ladder in shell.go finds out that an image has no bash before it hands the customer
	// a terminal containing an OCI error message.
	//
	// A false return is an answer about the command. An error is the engine failing to
	// answer, and the caller treats the two differently.
	CanExecute(ctx context.Context, containerID string, command []string) (bool, error)
}

// The contract with the control stream, checked by the compiler. rpc.TerminalHost is
// declared by its consumer, Go style, and implemented here.
var _ rpc.TerminalHost = (*Host)(nil)
