package build

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"strings"
	"time"

	"github.com/moby/moby/api/types/container"
	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Running one command in a container that exists for the length of that command.
//
// Create, start, follow the output, wait for the exit code, remove. Every stage of a build
// is one of these, and each gets its own container rather than one long-lived container
// with several execs in it: an exec has no exit code the engine will hand back after a
// disconnect, and a container that outlives a stage is a container that outlives a crash.
//
// The removal is not deferred cleanup that can be skipped. sasayaki is crash-only and
// leaves nothing behind on exit, so a build container abandoned by a killed daemon is
// swept by the next build of the same workload (sweepAbandoned) rather than by a shutdown
// hook nobody runs.

const (
	// frameHeaderBytes is the engine's multiplexing header: one byte of stream, three of
	// padding, four of big-endian length. Copying that connection straight to a reader
	// without decoding it is the mistake that broke the predecessor's terminal, and it
	// would put binary garbage in the middle of a customer's build log here.
	frameHeaderBytes = 8
	// maxFrameBytes bounds one frame's allocation. The engine writes at most 16KiB, so
	// this is three orders of magnitude of headroom and exists only so that a corrupt
	// length field cannot ask for the machine's memory.
	maxFrameBytes = 16 << 20
	// stderrStream is the stream byte for stderr. Stdout is 1.
	stderrStream = 2

	// outputGrace is how long the last of a finished container's output is waited for
	// after the engine has reported its exit code. The two events race, and a build log
	// missing its final line - which is usually the error message - is the one loss a
	// customer notices.
	outputGrace = 5 * time.Second
)

// containerRun is one command inside one throwaway container.
type containerRun struct {
	// Name is what it is called in `docker ps` while it lives. Ids and stages, so an
	// operator watching a node during a deployment can see which build is where.
	Name string
	// BuildID goes on the label the abandoned-container sweep looks for.
	BuildID string
	// Image the command runs in: the panel's builder_image, or the pinned git image.
	Image string
	// Entrypoint and Command are argv. Never a shell string: a branch name with a
	// backtick in it must not become a second command (AGENTS.md section 5).
	Entrypoint []string
	Command    []string
	WorkingDir string
	// Env carries the plan's build variables, and for a clone the access token. Nothing in
	// this package writes it anywhere.
	Env []string
	// HostPath is bind-mounted at workMount, read-write, and is the only thing from the
	// host the container can see.
	HostPath string
	Limits   *wisperpb.ResourceLimits
	// Stdout and Stderr receive the container's output as it arrives. Either may be nil,
	// which discards that half.
	Stdout io.Writer
	Stderr io.Writer
}

// runOutcome is how a command ended.
type runOutcome struct {
	// ExitCode is the process's status, or 137 when the deadline passed and it was killed -
	// the shell's number for SIGKILL, so it reads as one to anybody who has seen it.
	ExitCode int32
	// TimedOut is set when the context's deadline ended it rather than the process.
	TimedOut bool
}

// Ok reports whether the command succeeded.
func (o runOutcome) Ok() bool { return o.ExitCode == 0 && !o.TimedOut }

// runContainer runs one command to completion.
//
// An error means the command could not be run - the image is missing, the engine refused.
// A command that ran and failed is not an error: it comes back as an outcome with an exit
// code, because "your build script returned 1" is the customer's information and not the
// daemon's failure.
func (b *Builder) runContainer(ctx context.Context, run containerRun) (runOutcome, error) {
	if len(run.Command) == 0 && len(run.Entrypoint) == 0 {
		return runOutcome{}, fmt.Errorf("build: no command was given to run in %s", run.Image)
	}

	config := &container.Config{
		Image:        run.Image,
		Entrypoint:   run.Entrypoint,
		Cmd:          run.Command,
		Env:          run.Env,
		WorkingDir:   run.WorkingDir,
		Tty:          false,
		AttachStdout: true,
		AttachStderr: true,
		Labels:       buildLabels(run.BuildID),
	}
	host := &container.HostConfig{
		Binds:     []string{run.HostPath + ":" + workMount},
		Resources: buildResources(run.Limits),
	}
	hardenBuild(host, b.buildRuntime(ctx))

	created, err := b.engine.ContainerCreate(ctx, client.ContainerCreateOptions{
		Name:       run.Name,
		Config:     config,
		HostConfig: host,
	})
	if err != nil {
		return runOutcome{}, fmt.Errorf("build: create the container for %s: %w", run.Name, err)
	}
	for _, warning := range created.Warnings {
		b.log.Warn("the engine accepted a build container with a warning",
			slog.String("container", created.ID), slog.String("warning", warning))
	}
	// Deliberately not ctx: the path that matters most is the one where ctx is already
	// cancelled by the build timeout, and a removal that inherited that would leave the
	// container behind exactly when it was killed.
	defer b.removeContainer(context.WithoutCancel(ctx), created.ID)

	// Opened before the start so nothing written in the first milliseconds is missed. The
	// engine holds the connection open until the container exits.
	logs, err := b.engine.ContainerLogs(ctx, created.ID, client.ContainerLogsOptions{
		ShowStdout: true,
		ShowStderr: true,
		Follow:     true,
	})
	if err != nil {
		return runOutcome{}, fmt.Errorf("build: read the output of %s: %w", run.Name, err)
	}

	read := make(chan struct{})
	go func() {
		defer close(read)
		if err := demultiplex(logs, run.Stdout, run.Stderr); err != nil {
			b.log.Debug("a build container's output ended early",
				slog.String("container", created.ID), slog.String("error", err.Error()))
		}
	}()

	if _, err := b.engine.ContainerStart(ctx, created.ID, client.ContainerStartOptions{}); err != nil {
		drainOutput(read, logs, 0)
		return runOutcome{}, fmt.Errorf("build: start the container for %s: %w", run.Name, err)
	}

	waited := b.engine.ContainerWait(ctx, created.ID, client.ContainerWaitOptions{
		Condition: container.WaitConditionNotRunning,
	})

	select {
	case result := <-waited.Result:
		drainOutput(read, logs, outputGrace)
		if failure := result.Error; failure != nil && strings.TrimSpace(failure.Message) != "" {
			return runOutcome{}, fmt.Errorf("build: the engine could not wait for %s: %s",
				run.Name, failure.Message)
		}
		return runOutcome{ExitCode: int32(result.StatusCode)}, nil

	case err := <-waited.Error:
		if ctx.Err() != nil {
			// The deadline, not the engine. The container is still running and the
			// deferred removal - with force, and with an uncancelled context - is what
			// stops it.
			drainOutput(read, logs, 0)
			return runOutcome{ExitCode: 137, TimedOut: true}, nil
		}
		drainOutput(read, logs, outputGrace)
		return runOutcome{}, fmt.Errorf("build: wait for %s: %w", run.Name, err)

	case <-ctx.Done():
		drainOutput(read, logs, 0)
		return runOutcome{ExitCode: 137, TimedOut: true}, nil
	}
}

// drainOutput waits for the log reader to reach the end of the stream, and closes the
// stream out from under it if it does not.
//
// The engine closes the connection when the container exits, so on the ordinary path this
// returns as soon as the last frame has been forwarded. The grace exists for the two paths
// where it will not: a build killed by its deadline is still holding an open follow, and a
// reader blocked on a connection nobody is going to write to again would hold the whole
// build goroutine forever - which is a hung deployment reported as nothing at all.
func drainOutput(read <-chan struct{}, stream io.Closer, grace time.Duration) {
	if grace > 0 {
		select {
		case <-read:
			return
		case <-time.After(grace):
		}
	}
	_ = stream.Close()
	<-read
}

// removeContainer takes the container away, forcing it if it is still running.
//
// Force is what makes a timeout mean something: the deadline passed, the process is still
// compiling, and nothing else in the Engine API stops it. A failure is logged rather than
// returned, because it happens on the path where the caller already has a result and
// replacing it with "and the cleanup failed too" would hide which of the two mattered.
func (b *Builder) removeContainer(ctx context.Context, containerID string) {
	_, err := b.engine.ContainerRemove(ctx, containerID, client.ContainerRemoveOptions{
		Force: true,
		// The workspace is a bind mount, not a Docker volume, so there is nothing here to
		// remove and saying so is cheaper than relying on the default.
		RemoveVolumes: false,
	})
	if err == nil {
		return
	}
	b.log.Warn("could not remove a finished build container",
		slog.String("container", containerID), slog.String("error", err.Error()))
}

// demultiplex splits the engine's interleaved output into the two streams it came from.
//
// io.EOF is the ordinary end and is not reported. Anything else is: a truncated frame
// means the connection went away mid-write, which is worth a debug line explaining why a
// build log stops before the exit code does.
func demultiplex(source io.Reader, stdout, stderr io.Writer) error {
	header := make([]byte, frameHeaderBytes)
	for {
		if _, err := io.ReadFull(source, header); err != nil {
			if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
				return nil
			}
			return err
		}

		size := binary.BigEndian.Uint32(header[4:frameHeaderBytes])
		if size > maxFrameBytes {
			return fmt.Errorf("build: the engine announced a %d-byte frame, which is beyond "+
				"anything it writes; the stream is not what it claims to be", size)
		}
		if size == 0 {
			continue
		}

		payload := make([]byte, size)
		if _, err := io.ReadFull(source, payload); err != nil {
			if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
				return nil
			}
			return err
		}

		destination := stdout
		if header[0] == stderrStream {
			destination = stderr
		}
		if destination != nil {
			if _, err := destination.Write(payload); err != nil {
				return err
			}
		}
	}
}
