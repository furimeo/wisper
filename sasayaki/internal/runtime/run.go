package runtime

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"

	"github.com/moby/moby/client"
)

// Running one command inside a container and waiting for it.
//
// This is what a cron entry is, and what a database grant is: `psql -c 'CREATE ROLE ...'`
// inside the shared engine container. It is a different shape from exec.go - no pty, two
// separate output streams, an exit code that is the whole point - so it is a different
// file rather than a boolean on the same function.
//
// The part worth reading twice is the timeout. Cancelling the context closes the
// connection, and closing the connection does not stop the process: there is no Engine
// API call that kills an exec. A cron job that ignored its timeout would keep running,
// the next tick would start another, and a node ends up with two hundred copies of the
// same stuck script. So the process is killed by pid, which works because the engine
// reports the host-side pid of an exec and this daemon runs as root.

// captureLimit is how much output is held in memory when the caller supplied nowhere to
// put it. Enough for any error message worth reading and any `SELECT` a grant makes;
// small enough that a cron job printing a progress bar for an hour cannot exhaust the
// node. Beyond it the output is truncated and RunResult says so, which is a better
// outcome than either silence or an out-of-memory kill.
const captureLimit = 1 << 20

// RunOptions is one non-interactive command.
type RunOptions struct {
	// argv. Never a shell string: the panel builds argv and the node passes argv, so a
	// database name with a semicolon in it cannot become a second command.
	Command    []string
	WorkingDir string
	User       string
	Env        []string
	// Fed to the process's standard input and then closed. Used for a SQL script that is
	// too long, or too quote-heavy, to be an argument.
	Stdin []byte
	// Where to send output as it arrives. Nil captures it into RunResult instead, which
	// is what a database operation wants; a cron run passes writers that forward to the
	// panel's log stream so a customer can watch it happen.
	Stdout io.Writer
	Stderr io.Writer
}

// RunResult is how a command ended.
type RunResult struct {
	// The process's exit status, or 137 when it was killed for running past its
	// deadline - the shell convention, so the number reads as SIGKILL to anybody who has
	// seen one.
	ExitCode int
	// Captured output, when RunOptions left Stdout or Stderr nil.
	Stdout []byte
	Stderr []byte
	// Set when output was dropped because it went past captureLimit.
	Truncated bool
	// Set when the deadline passed and the process had to be killed.
	TimedOut bool
}

// Ok reports whether the command succeeded.
func (r RunResult) Ok() bool { return r.ExitCode == 0 && !r.TimedOut }

// Run executes a command inside a running container and waits for it to finish.
func (d *Docker) Run(ctx context.Context, containerID string, options RunOptions) (RunResult, error) {
	if len(options.Command) == 0 {
		return RunResult{}, fmt.Errorf("runtime: no command was given to run in the container %s", containerID)
	}

	created, err := d.api.ExecCreate(ctx, containerID, client.ExecCreateOptions{
		Cmd:          options.Command,
		WorkingDir:   options.WorkingDir,
		User:         options.User,
		Env:          options.Env,
		TTY:          false,
		AttachStdin:  len(options.Stdin) > 0,
		AttachStdout: true,
		AttachStderr: true,
		Privileged:   false,
	})
	if err != nil {
		return RunResult{}, fmt.Errorf("runtime: prepare %q in the container %s: %w",
			options.Command[0], containerID, err)
	}

	attached, err := d.api.ExecAttach(ctx, created.ID, client.ExecAttachOptions{TTY: false})
	if err != nil {
		return RunResult{}, fmt.Errorf("runtime: run %q in the container %s: %w",
			options.Command[0], containerID, err)
	}
	defer attached.Close()

	stdout := capture(options.Stdout)
	stderr := capture(options.Stderr)

	if len(options.Stdin) > 0 {
		if _, err := attached.Conn.Write(options.Stdin); err != nil {
			return RunResult{}, fmt.Errorf("runtime: write the input of %q in the container %s: %w",
				options.Command[0], containerID, err)
		}
	}
	// Always, even with no input: a process reading standard input would otherwise wait
	// for a stream nobody is going to write to, which looks exactly like a hang.
	_ = attached.CloseWrite()

	result := RunResult{}
	finished := make(chan error, 1)
	go func() { finished <- drain(attached.Reader, stdout, stderr) }()

	select {
	case err := <-finished:
		if err != nil {
			return result, fmt.Errorf("runtime: read the output of %q in the container %s: %w",
				options.Command[0], containerID, err)
		}
	case <-ctx.Done():
		result.TimedOut = true
		d.kill(created.ID, containerID, options.Command[0])
		attached.Close()
		<-finished
	}

	result.Stdout, result.Truncated = stdout.taken()
	errOutput, errTruncated := stderr.taken()
	result.Stderr = errOutput
	result.Truncated = result.Truncated || errTruncated

	// Deliberately not ctx: the deadline that just expired is the reason we are here, and
	// asking the engine for the exit code with an already-cancelled context would lose
	// the one number the caller needs.
	code, err := d.exitCode(context.WithoutCancel(ctx), created.ID)
	if err != nil {
		if result.TimedOut {
			// The kill landed, the engine has not caught up, and the answer is not in
			// doubt. 137 is what a SIGKILL produces.
			result.ExitCode = 137
			return result, nil
		}
		return result, err
	}
	result.ExitCode = code
	return result, nil
}

// exitCode asks the engine how an exec finished.
func (d *Docker) exitCode(ctx context.Context, execID string) (int, error) {
	inspected, err := d.api.ExecInspect(ctx, execID, client.ExecInspectOptions{})
	if err != nil {
		return 0, fmt.Errorf("runtime: ask how a command ended: %w", err)
	}
	return inspected.ExitCode, nil
}

// kill stops an exec that has run past its deadline.
//
// By pid, because the Engine API has no call for it. The pid the engine reports is on the
// host - an exec is a host process that joined the container's namespaces - and this
// daemon runs as root, so the signal lands. A failure is logged rather than returned: the
// command has already failed by timing out, and turning "could not kill it either" into
// the error the caller sees would hide which of the two actually happened.
func (d *Docker) kill(execID, containerID, command string) {
	inspected, err := d.api.ExecInspect(context.Background(), execID, client.ExecInspectOptions{})
	if err != nil || !inspected.Running || inspected.PID <= 0 {
		return
	}
	process, err := os.FindProcess(inspected.PID)
	if err == nil {
		err = process.Kill()
	}
	if err != nil {
		d.log.Warn("a command ran past its deadline and could not be killed",
			slog.String("container", containerID),
			slog.String("command", command),
			slog.Int("pid", inspected.PID),
			slog.String("error", err.Error()))
		return
	}
	d.log.Warn("killed a command that ran past its deadline",
		slog.String("container", containerID),
		slog.String("command", command),
		slog.Int("pid", inspected.PID))
}

// drain splits the engine's multiplexed exec output into the two streams it came from.
func drain(source io.Reader, stdout, stderr *sink) error {
	header := make([]byte, frameHeaderBytes)
	for {
		isStderr, payload, err := readFrame(source, header)
		if errors.Is(err, io.EOF) {
			return nil
		}
		if err != nil {
			return err
		}
		if isStderr {
			stderr.Write(payload)
			continue
		}
		stdout.Write(payload)
	}
}

// sink is where one of the two output streams goes: a caller's writer, or a bounded
// buffer when there is none.
type sink struct {
	to        io.Writer
	held      bytes.Buffer
	truncated bool
}

func capture(to io.Writer) *sink { return &sink{to: to} }

func (s *sink) Write(payload []byte) {
	if s.to != nil {
		// The caller's writer decides what to do with it; a log feed that cannot keep up
		// is the log feed's problem to report, not this function's to fail on.
		_, _ = s.to.Write(payload)
		return
	}
	room := captureLimit - s.held.Len()
	if room <= 0 {
		s.truncated = true
		return
	}
	if len(payload) > room {
		payload = payload[:room]
		s.truncated = true
	}
	s.held.Write(payload)
}

func (s *sink) taken() ([]byte, bool) {
	if s.to != nil {
		return nil, false
	}
	return s.held.Bytes(), s.truncated
}
