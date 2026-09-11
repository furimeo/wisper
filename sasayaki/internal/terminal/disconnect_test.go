package terminal

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"
)

// What happens when the other end goes away.
//
// Most sessions end this way rather than with a shell exiting: a customer closes the tab,
// puts the phone in their pocket, walks into a lift. None of those is a failure, and none
// of them may leave an exec attached to their container - the whole reason a session has a
// lifetime is that it is a process running as their application.

func TestThePanelHangingUpClosesTheExec(t *testing.T) {
	test := newHarness(t)

	finished := test.start(t, nil)
	eventually(t, "the attachment", func() bool { return len(test.stream.frames()) == 1 })

	test.stream.hangUp()
	if err := finish(t, finished); err != nil {
		t.Fatalf("a customer closing the tab was reported as a node failure: %v", err)
	}
	if !test.pty.isClosed() {
		t.Fatal("the exec was left attached after the panel disconnected")
	}

	exit := wantExit(t, test.stream.frames())
	if exit.GetReason() != reasonPanelClosed {
		t.Errorf("the reason is %q, expected %q", exit.GetReason(), reasonPanelClosed)
	}
	if !test.stream.sendClosed() {
		t.Error("the node's half of the stream was left open")
	}
}

// A stream that fails rather than ending cleanly - a dropped tunnel - is the same outcome.
// It is normal on this deployment, not an incident (AGENTS.md section 4.3).
func TestABrokenStreamEndsTheSessionWithoutFailingIt(t *testing.T) {
	test := newHarness(t)
	test.stream.recvErr = errors.New("rpc error: code = Unavailable")

	finished := test.start(t, nil)
	eventually(t, "the attachment", func() bool { return len(test.stream.frames()) == 1 })
	test.stream.hangUp()

	if err := finish(t, finished); err != nil {
		t.Fatalf("a dropped tunnel was reported as a node failure: %v", err)
	}
	if !test.pty.isClosed() {
		t.Fatal("the exec was left attached after the stream broke")
	}
}

// The node shutting down ends every session it is holding, and says so rather than looking
// like the customer's shell crashed.
func TestShuttingDownEndsTheSession(t *testing.T) {
	test := newHarness(t)

	ctx, stopping := context.WithCancel(t.Context())
	finished := test.startWith(t, ctx, nil)
	eventually(t, "the attachment", func() bool { return len(test.stream.frames()) == 1 })

	stopping()
	if err := finish(t, finished); err != nil {
		t.Fatalf("shutting down was reported as a session failure: %v", err)
	}
	if !test.pty.isClosed() {
		t.Fatal("the exec was left attached after the node stopped the session")
	}
	if reason := wantExit(t, test.stream.frames()).GetReason(); reason != reasonShuttingDown {
		t.Errorf("the reason is %q, expected %q", reason, reasonShuttingDown)
	}
}

// A pty that stops accepting input is a process on its way out. The session waits a moment
// for the output side to finish, because that is where the real exit code is.
func TestAPtyThatRefusesInputWaitsForTheOutputToFinish(t *testing.T) {
	test := newHarness(t)
	test.pty.writeErr = errors.New("write tcp: broken pipe")
	test.pty.exitCode = 130

	finished := test.start(t, nil)
	eventually(t, "the attachment", func() bool { return len(test.stream.frames()) == 1 })

	test.stream.panelSends(dataFrame("\x03"))
	// The last thing the process printed, arriving after its input had already failed.
	test.pty.say([]byte("^C\r\n"))
	test.pty.stop()

	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}
	frames := test.stream.frames()
	if got := string(outputOf(t, frames)); got != "^C\r\n" {
		t.Errorf("the last output was lost: %q", got)
	}
	if code := wantExit(t, frames).GetCode(); code != 130 {
		t.Errorf("reported exit %d, expected the process's own 130", code)
	}
}

// And when the output side never finishes, the session gives up rather than holding a slot
// on a connection that is already broken.
func TestAPtyThatRefusesInputDoesNotWaitForever(t *testing.T) {
	test := newHarness(t)
	test.pty.writeErr = errors.New("write tcp: broken pipe")

	started := time.Now()
	finished := test.start(t, nil)
	eventually(t, "the attachment", func() bool { return len(test.stream.frames()) == 1 })
	test.stream.panelSends(dataFrame("hello"))

	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}
	if elapsed := time.Since(started); elapsed < inputFailureGrace {
		t.Errorf("the session gave up after %s, before giving the output side its %s",
			elapsed, inputFailureGrace)
	}
	exit := wantExit(t, test.stream.frames())
	if !strings.Contains(exit.GetReason(), "input") {
		t.Errorf("the reason does not say the pty stopped taking input: %q", exit.GetReason())
	}
}
