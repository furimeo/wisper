package terminal

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// One node's worth of terminal host, wired to the fakes in fakes_test.go, plus the
// assertions every test here makes about what the browser would have seen.
//
// Serve blocks for as long as the session lives, so a test starts one in the background
// and drives it from the outside: it makes the pty say something, it makes the panel send
// a frame, and it waits for the session to end. finish is what turns a deadlock into a
// named failure instead of a test binary killed ten minutes later.

var noon = time.Date(2026, time.March, 4, 12, 0, 0, 0, time.UTC)

// errAlreadyGone is what a stream that has been hung up on returns. Named once because
// several tests need a failure that is not io.EOF.
var errAlreadyGone = errors.New("the terminal stream is gone")

const (
	sessionID   = "sess-7f3a"
	workloadID  = "wl-api"
	containerID = "c0ffee1234"
)

// harness is one node's worth of terminal host, with the handles a test needs behind it.
type harness struct {
	host   *Host
	engine *fakeEngine
	pty    *fakePty
	stream *fakeStream
	clock  time.Time
}

func newHarness(t *testing.T, adjust ...func(*Options)) *harness {
	t.Helper()

	test := &harness{clock: noon}
	test.pty = newFakePty(containerID, 80, 24)
	test.engine = &fakeEngine{
		pty:        test.pty,
		executable: map[string]bool{"/bin/bash": true},
		container: reconcile.Container{
			ID:         containerID,
			WorkloadID: workloadID,
			Running:    true,
			Status:     "Up 4 minutes",
		},
		exists: true,
	}
	test.stream = newFakeStream()
	t.Cleanup(test.stream.hangUp)
	t.Cleanup(test.pty.Close)

	options := Options{
		Engine: test.engine,
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
		Now:    func() time.Time { return test.clock },
		// Long enough that no test hits one of these unless it asked to.
		IdleTimeout: 30 * time.Second,
		MaxDuration: 60 * time.Second,
	}
	for _, change := range adjust {
		change(&options)
	}

	host, err := New(options)
	if err != nil {
		t.Fatalf("open the terminal host: %v", err)
	}
	test.host = host
	return test
}

// start runs one session in the background and hands back the channel its result will
// arrive on. Serve blocks for as long as the session lives, so every test does this.
func (h *harness) start(t *testing.T, request *wisperpb.StartTerminal) <-chan error {
	t.Helper()
	return h.startWith(t, t.Context(), request)
}

func (h *harness) startWith(t *testing.T, ctx context.Context, request *wisperpb.StartTerminal) <-chan error {
	t.Helper()
	if request == nil {
		request = &wisperpb.StartTerminal{}
	}
	if request.SessionId == "" {
		request.SessionId = sessionID
	}
	if request.WorkloadId == "" {
		request.WorkloadId = workloadID
	}

	finished := make(chan error, 1)
	go func() { finished <- h.host.Serve(ctx, request, h.stream) }()
	return finished
}

// finish waits for a session to end. A session that has not finished in five seconds is a
// deadlock, and reporting it as one is more useful than a test binary killed at ten
// minutes with no indication of where it stopped.
func finish(t *testing.T, finished <-chan error) error {
	t.Helper()
	select {
	case err := <-finished:
		return err
	case <-time.After(5 * time.Second):
		t.Fatal("the session did not end within five seconds")
		return nil
	}
}

// eventually polls until the condition holds. Used where the thing being waited for is
// produced by another goroutine and there is no channel to wait on - a frame arriving, a
// resize landing on the pty.
func eventually(t *testing.T, what string, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

// dataFrame and resizeFrame are the two things the panel is allowed to send.
func dataFrame(payload string) *wisperpb.TerminalFrame {
	return &wisperpb.TerminalFrame{
		SessionId: sessionID,
		Payload:   &wisperpb.TerminalFrame_Data{Data: []byte(payload)},
	}
}

func resizeFrame(cols, rows uint32) *wisperpb.TerminalFrame {
	return &wisperpb.TerminalFrame{
		SessionId: sessionID,
		Payload:   &wisperpb.TerminalFrame_Resize{Resize: &wisperpb.TerminalResize{Cols: cols, Rows: rows}},
	}
}

// wantAttached asserts the first frame is the attachment and returns it.
func wantAttached(t *testing.T, frames []*wisperpb.TerminalFrame) *wisperpb.TerminalAttached {
	t.Helper()
	if len(frames) == 0 {
		t.Fatal("the session sent nothing at all, so the browser would wait forever")
	}
	attached := frames[0].GetAttached()
	if attached == nil {
		t.Fatalf("the first frame is %T, not the attachment", frames[0].GetPayload())
	}
	return attached
}

// wantExit asserts the last frame is a TerminalExit and returns it. Everything this
// package does ends in one.
func wantExit(t *testing.T, frames []*wisperpb.TerminalFrame) *wisperpb.TerminalExit {
	t.Helper()
	if len(frames) == 0 {
		t.Fatal("the session sent nothing at all, so the browser would wait forever")
	}
	last := frames[len(frames)-1]
	exit := last.GetExit()
	if exit == nil {
		t.Fatalf("the last frame is %T, not an exit", last.GetPayload())
	}
	return exit
}

// outputOf is every Data frame joined back together, which is what the browser sees.
func outputOf(t *testing.T, frames []*wisperpb.TerminalFrame) []byte {
	t.Helper()
	var output []byte
	for index, frame := range frames {
		if _, isData := frame.GetPayload().(*wisperpb.TerminalFrame_Data); !isData {
			continue
		}
		if frame.GetSessionId() != sessionID {
			t.Fatalf("frame %d carries session id %q instead of %q",
				index, frame.GetSessionId(), sessionID)
		}
		output = append(output, frame.GetData()...)
	}
	return output
}
