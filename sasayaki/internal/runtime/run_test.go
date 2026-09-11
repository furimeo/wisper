package runtime

import (
	"bufio"
	"bytes"
	"context"
	"io"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/moby/moby/client"
)

// attachedTo wires the fake so an exec's output is these bytes and its exit status is
// this code.
func attachedTo(api *fakeEngine, output []byte, exitCode int) *net.Conn {
	local, remote := net.Pipe()
	// Nothing reads the far end unless a test wants to see what was written, so it is
	// drained here to keep a write to stdin from blocking forever.
	go io.Copy(io.Discard, remote)

	api.onExecAttach = func(string) (client.ExecAttachResult, error) {
		return client.ExecAttachResult{HijackedResponse: client.HijackedResponse{
			Conn:   local,
			Reader: bufio.NewReader(bytes.NewReader(output)),
		}}, nil
	}
	api.onExecInspect = func(id string) (client.ExecInspectResult, error) {
		return client.ExecInspectResult{ID: id, Running: false, ExitCode: exitCode}, nil
	}
	return &local
}

func TestRunCapturesBothStreamsAndTheExitCode(t *testing.T) {
	api := newFake()
	attachedTo(api, bytes.Join([][]byte{
		frame(1, "CREATE ROLE\n"),
		frame(2, "NOTICE: role already exists\n"),
	}, nil), 3)
	docker := newDocker(t, api, newHost())

	result, err := docker.Run(context.Background(), "engine-postgres", RunOptions{
		Command: []string{"psql", "-c", "CREATE ROLE tenant_7"},
	})
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if string(result.Stdout) != "CREATE ROLE\n" {
		t.Errorf("stdout = %q", result.Stdout)
	}
	if string(result.Stderr) != "NOTICE: role already exists\n" {
		t.Errorf("stderr = %q", result.Stderr)
	}
	if result.ExitCode != 3 || result.Ok() {
		t.Errorf("result = %+v, want the exit code the process really returned", result)
	}
	if len(api.execs) != 1 || api.execs[0].TTY || api.execs[0].Privileged {
		t.Errorf("exec = %+v, want no tty and never privileged", api.execs)
	}
}

func TestRunSendsOutputToTheCallersWriterWhenThereIsOne(t *testing.T) {
	api := newFake()
	attachedTo(api, frame(1, "step 1 of 4\n"), 0)
	docker := newDocker(t, api, newHost())

	var watched bytes.Buffer
	result, err := docker.Run(context.Background(), "c1", RunOptions{
		Command: []string{"/bin/backup"},
		Stdout:  &watched,
	})
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if watched.String() != "step 1 of 4\n" {
		t.Errorf("the caller's writer got %q, and a cron run is watched as it happens", watched.String())
	}
	if result.Stdout != nil {
		t.Error("output was captured as well as forwarded, which doubles the memory for nothing")
	}
}

func TestRunTruncatesOutputRatherThanExhaustingTheNode(t *testing.T) {
	api := newFake()
	attachedTo(api, frame(1, strings.Repeat("x", captureLimit+4096)), 0)
	docker := newDocker(t, api, newHost())

	result, err := docker.Run(context.Background(), "c1", RunOptions{Command: []string{"/bin/noisy"}})
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if len(result.Stdout) != captureLimit {
		t.Errorf("captured %d bytes, want the limit of %d", len(result.Stdout), captureLimit)
	}
	if !result.Truncated {
		t.Error("output was dropped and nothing said so, which is worse than either the " +
			"silence or the memory")
	}
}

// A cron job with no enforced timeout is how a node ends up with two hundred copies of
// the same stuck script. Closing the connection does not stop the process, so the process
// is killed - and the result still carries an exit status.
func TestRunReportsATimeoutRatherThanHangingForever(t *testing.T) {
	api := newFake()
	local, remote := net.Pipe()
	defer remote.Close()
	api.onExecAttach = func(string) (client.ExecAttachResult, error) {
		return client.ExecAttachResult{HijackedResponse: client.HijackedResponse{
			Conn: local, Reader: bufio.NewReader(local),
		}}, nil
	}
	// PID zero, so nothing on the machine running this test can be signalled.
	api.onExecInspect = func(id string) (client.ExecInspectResult, error) {
		return client.ExecInspectResult{ID: id, Running: false, ExitCode: 137, PID: 0}, nil
	}
	docker := newDocker(t, api, newHost())

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Millisecond)
	defer cancel()

	result, err := docker.Run(ctx, "c1", RunOptions{Command: []string{"/bin/sleep", "3600"}})
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if !result.TimedOut {
		t.Error("a command that ran past its deadline was reported as having finished normally")
	}
	if result.ExitCode != 137 {
		t.Errorf("exit code = %d, want 137: the shell convention for a SIGKILL", result.ExitCode)
	}
	if result.Ok() {
		t.Error("a timed-out command reported success")
	}
}

func TestRunRefusesAnEmptyCommand(t *testing.T) {
	docker := newDocker(t, newFake(), newHost())
	if _, err := docker.Run(context.Background(), "c1", RunOptions{}); err == nil {
		t.Fatal("an exec with no argv was accepted")
	}
}

func TestRunFeedsStandardInputAndThenClosesIt(t *testing.T) {
	api := newFake()
	local, remote := net.Pipe()
	written := make(chan string, 1)
	go func() {
		buffer := make([]byte, 64)
		read, _ := remote.Read(buffer)
		written <- string(buffer[:read])
	}()
	api.onExecAttach = func(string) (client.ExecAttachResult, error) {
		return client.ExecAttachResult{HijackedResponse: client.HijackedResponse{
			Conn: local, Reader: bufio.NewReader(bytes.NewReader(frame(1, "ok\n"))),
		}}, nil
	}
	api.onExecInspect = func(id string) (client.ExecInspectResult, error) {
		return client.ExecInspectResult{ID: id, ExitCode: 0}, nil
	}
	docker := newDocker(t, api, newHost())

	if _, err := docker.Run(context.Background(), "c1", RunOptions{
		Command: []string{"psql"},
		Stdin:   []byte("SELECT 1;\n"),
	}); err != nil {
		t.Fatalf("Run: %v", err)
	}
	select {
	case got := <-written:
		if got != "SELECT 1;\n" {
			t.Errorf("stdin = %q", got)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("standard input never reached the process")
	}
	if !api.execs[0].AttachStdin {
		t.Error("stdin was supplied but not attached")
	}
}
