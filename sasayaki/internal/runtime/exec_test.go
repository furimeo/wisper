package runtime

import (
	"bufio"
	"bytes"
	"context"
	"io"
	"net"
	"testing"
	"time"

	"github.com/moby/moby/client"
)

// terminalOn wires the fake so a session's output is these bytes, and hands back a
// channel carrying whatever the session writes towards the container.
func terminalOn(api *fakeEngine, output string) <-chan string {
	local, remote := net.Pipe()
	typed := make(chan string, 4)
	go func() {
		buffer := make([]byte, 256)
		for {
			read, err := remote.Read(buffer)
			if read > 0 {
				typed <- string(buffer[:read])
			}
			if err != nil {
				return
			}
		}
	}()

	api.onExecAttach = func(string) (client.ExecAttachResult, error) {
		return client.ExecAttachResult{HijackedResponse: client.HijackedResponse{
			Conn:   local,
			Reader: bufio.NewReader(bytes.NewReader([]byte(output))),
		}}, nil
	}
	return typed
}

func TestExecOpensAPtyWithTheBrowsersSize(t *testing.T) {
	api := newFake()
	terminalOn(api, "$ ")
	docker := newDocker(t, api, newHost())

	session, err := docker.Exec(context.Background(), "c1", ExecOptions{
		Command: []string{"/bin/sh"},
		User:    "1000:1000",
		Env:     []string{"TERM=xterm-256color"},
		Cols:    120,
		Rows:    40,
	})
	if err != nil {
		t.Fatalf("Exec: %v", err)
	}
	defer session.Close()

	created := api.execs[0]
	if !created.TTY {
		t.Error("no tty, so the shell would not line-edit and Ctrl-C would not reach the " +
			"foreground process")
	}
	if created.Privileged {
		t.Error("a privileged exec into an unprivileged container hands a customer the host")
	}
	if !created.AttachStdin || !created.AttachStdout || !created.AttachStderr {
		t.Errorf("exec = %+v, want all three streams attached", created)
	}
	if created.ConsoleSize.Width != 120 || created.ConsoleSize.Height != 40 {
		t.Errorf("console = %+v, want the browser's size so the first prompt is not drawn "+
			"at 80x24 and immediately redrawn", created.ConsoleSize)
	}
	if cols, rows := session.Size(); cols != 120 || rows != 40 {
		t.Errorf("size = %dx%d, want the size that was actually given", cols, rows)
	}
	if session.ContainerID() != "c1" {
		t.Errorf("containerID = %q", session.ContainerID())
	}
}

func TestASizeTheBrowserCannotMeanIsClampedRatherThanRefused(t *testing.T) {
	api := newFake()
	terminalOn(api, "")
	docker := newDocker(t, api, newHost())

	session, err := docker.Exec(context.Background(), "c1", ExecOptions{})
	if err != nil {
		t.Fatalf("Exec: %v", err)
	}
	defer session.Close()

	if cols, rows := session.Size(); cols != defaultCols || rows != defaultRows {
		t.Errorf("size = %dx%d, want the 80x24 every emulator assumes when told nothing", cols, rows)
	}

	if err := session.Resize(context.Background(), 100000, 0); err != nil {
		t.Fatalf("Resize: %v", err)
	}
	if len(api.resizes) != 1 {
		t.Fatalf("the pty was not resized")
	}
	if api.resizes[0].Width != uint(maxCols) || api.resizes[0].Height != uint(defaultRows) {
		t.Errorf("resize = %+v, want it clamped: a customer still gets a working shell", api.resizes[0])
	}
	if cols, rows := session.Size(); cols != maxCols || rows != defaultRows {
		t.Errorf("size = %dx%d, want the session to remember what it was clamped to", cols, rows)
	}
}

func TestASessionPumpsBytesBothWays(t *testing.T) {
	api := newFake()
	typed := terminalOn(api, "hello from the pty")
	docker := newDocker(t, api, newHost())

	session, err := docker.Exec(context.Background(), "c1", ExecOptions{})
	if err != nil {
		t.Fatalf("Exec: %v", err)
	}
	defer session.Close()

	output, err := io.ReadAll(session)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if string(output) != "hello from the pty" {
		t.Errorf("output = %q", output)
	}
	if _, err := session.Write([]byte("ls -la\r")); err != nil {
		t.Fatalf("write: %v", err)
	}
	select {
	case keystrokes := <-typed:
		if keystrokes != "ls -la\r" {
			t.Errorf("the container received %q", keystrokes)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("keystrokes never reached the container")
	}
	if err := session.CloseInput(); err != nil {
		t.Fatalf("CloseInput: %v", err)
	}
}

func TestWaitReturnsTheExitCodeOnceTheEngineHasCaughtUp(t *testing.T) {
	api := newFake()
	terminalOn(api, "")
	// The exec's bookkeeping settles a moment after its output ends, which is why Wait
	// asks more than once instead of reporting the zero an early inspect would give.
	answers := 0
	api.onExecInspect = func(id string) (client.ExecInspectResult, error) {
		answers++
		if answers < 3 {
			return client.ExecInspectResult{ID: id, Running: true}, nil
		}
		return client.ExecInspectResult{ID: id, Running: false, ExitCode: 130}, nil
	}
	docker := newDocker(t, api, newHost())

	session, err := docker.Exec(context.Background(), "c1", ExecOptions{})
	if err != nil {
		t.Fatalf("Exec: %v", err)
	}
	defer session.Close()

	code, err := session.Wait(context.Background())
	if err != nil {
		t.Fatalf("Wait: %v", err)
	}
	if code != 130 {
		t.Errorf("exit code = %d, want 130 so the UI can say Ctrl-C instead of a terminal "+
			"that just stopped responding", code)
	}
}

func TestClampKeepsASizeInsideWhatAPtyTakes(t *testing.T) {
	if got := clamp(0, 80, 1000); got != 80 {
		t.Errorf("clamp(0) = %d, want the fallback", got)
	}
	if got := clamp(24, 80, 1000); got != 24 {
		t.Errorf("clamp(24) = %d, want it untouched", got)
	}
	if got := clamp(99999, 80, 1000); got != 1000 {
		t.Errorf("clamp(99999) = %d, want the ceiling", got)
	}
}
