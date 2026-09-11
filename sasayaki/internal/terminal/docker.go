package terminal

import (
	"context"
	"io"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/runtime"
)

// The one real Engine, wired to the node's container runtime.
//
// It exists for a single reason, and it is worth stating so nobody removes it as
// ceremony: runtime.Exec returns a *runtime.Session, a concrete type over a hijacked
// Docker connection, and Go has no way to let an interface method return something a test
// can stand in for unless the interface says so. Widening that one return type is the
// whole of this file. Everything else is a straight forward.
//
// The alternative - having this package take a *runtime.Docker directly - would mean the
// only way to test a resize, an exit code or a container dying underneath a session is a
// live Docker daemon, which is precisely the arrangement that let the predecessor ship a
// terminal nobody had ever tested.

// Docker adapts the node's container runtime to Engine.
//
// The composition root calls it once:
//
//	terminal.New(terminal.Options{Engine: terminal.Docker(docker), ...})
func Docker(docker *runtime.Docker) Engine {
	return dockerEngine{docker: docker}
}

type dockerEngine struct {
	docker *runtime.Docker
}

func (e dockerEngine) Container(ctx context.Context, workloadID string) (reconcile.Container, bool, error) {
	return e.docker.ContainerFor(ctx, workloadID)
}

// Open attaches a pty.
//
// The result is assigned before it is returned rather than forwarded in one line, because
// a nil *runtime.Session put straight into a Pty is an interface that is not nil and a
// caller's error check that does not fire.
func (e dockerEngine) Open(ctx context.Context, containerID string, options runtime.ExecOptions) (Pty, error) {
	session, err := e.docker.Exec(ctx, containerID, options)
	if err != nil {
		return nil, err
	}
	return session, nil
}

// CanExecute runs the command with no terminal and reports whether it succeeded.
//
// Output goes nowhere on purpose. This is a probe - `<shell> -c exit 0` - and the only
// thing wanted from it is the exit status; a missing interpreter writes the engine's
// complaint to stderr and exits 126, and holding on to that text would only invite
// somebody to show it to a customer as if it were their shell's.
func (e dockerEngine) CanExecute(ctx context.Context, containerID string, command []string) (bool, error) {
	result, err := e.docker.Run(ctx, containerID, runtime.RunOptions{
		Command: command,
		Stdout:  io.Discard,
		Stderr:  io.Discard,
	})
	if err != nil {
		return false, err
	}
	return result.Ok(), nil
}
