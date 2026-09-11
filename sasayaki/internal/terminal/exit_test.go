package terminal

import (
	"errors"
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
)

// How a session ends.
//
// The predecessor lost this entirely: a shell that exited 130 and a connection that
// dropped both looked like a terminal that had stopped responding, and a customer had no
// way to tell "I pressed Ctrl-C" from "the platform is broken". Every case below produces
// a TerminalExit that says which.

func TestTheProcessExitCodeReachesTheBrowser(t *testing.T) {
	for _, code := range []int{0, 1, 2, 127, 130, 255} {
		test := newHarness(t)
		test.pty.exitCode = code

		finished := test.start(t, nil)
		test.pty.stop()
		if err := finish(t, finished); err != nil {
			t.Fatalf("the session failed: %v", err)
		}

		exit := wantExit(t, test.stream.frames())
		if exit.GetCode() != int32(code) {
			t.Errorf("the shell exited %d and the browser was told %d", code, exit.GetCode())
		}
		// No reason: the process ended by itself, which is not the platform having done
		// something to it (terminal.proto, TerminalExit.reason).
		if exit.GetReason() != "" {
			t.Errorf("a shell that exited on its own was reported with reason %q", exit.GetReason())
		}
		if !exit.GetEndedAt().AsTime().Equal(noon) {
			t.Errorf("ended at %s, expected %s", exit.GetEndedAt().AsTime(), noon)
		}
	}
}

// The engine's record of an exec settles a moment after its output stops, so the exit code
// is asked for after the read has ended rather than guessed at from the read itself.
func TestTheExitStatusIsAskedForAfterTheOutputEnds(t *testing.T) {
	test := newHarness(t)
	test.pty.exitCode = 42

	finished := test.start(t, nil)
	test.pty.say([]byte("working\r\n"))
	test.pty.stop()
	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}

	if test.pty.waits != 1 {
		t.Fatalf("the exit status was asked for %d times, expected once", test.pty.waits)
	}
	if code := wantExit(t, test.stream.frames()).GetCode(); code != 42 {
		t.Errorf("reported exit %d, expected 42", code)
	}
}

// A container killed underneath a live session: the read fails, the exec record goes with
// the container, and the customer has to be told what happened rather than shown a zero.
func TestAContainerRemovedUnderTheSessionIsReportedAsSuch(t *testing.T) {
	test := newHarness(t)
	test.pty.exitErr = errors.New("no such exec: 4f2c")
	test.engine.afterAttach = func() (reconcile.Container, bool, error) {
		return reconcile.Container{}, false, nil
	}

	finished := test.start(t, nil)
	test.pty.say([]byte("about to die\r\n"))
	test.pty.breaks(errors.New("read tcp: connection reset by peer"))

	if err := finish(t, finished); err != nil {
		t.Fatalf("a container dying under a session was reported as a node failure: %v", err)
	}

	frames := test.stream.frames()
	if got := string(outputOf(t, frames)); got != "about to die\r\n" {
		t.Errorf("the output written before the container died was lost: %q", got)
	}
	exit := wantExit(t, frames)
	if exit.GetCode() != killedCode {
		t.Errorf("reported exit %d, expected %d", exit.GetCode(), killedCode)
	}
	if !strings.Contains(exit.GetReason(), "removed while the terminal was open") {
		t.Errorf("the reason does not say the container went away: %q", exit.GetReason())
	}
}

func TestAContainerStoppedUnderTheSessionCarriesItsStatus(t *testing.T) {
	test := newHarness(t)
	test.pty.exitErr = errors.New("no such exec: 4f2c")
	test.engine.afterAttach = func() (reconcile.Container, bool, error) {
		return reconcile.Container{
			ID:      containerID,
			Running: false,
			Status:  "Exited (137) 1 second ago",
		}, true, nil
	}

	finished := test.start(t, nil)
	test.pty.breaks(errors.New("read tcp: connection reset by peer"))
	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}

	exit := wantExit(t, test.stream.frames())
	if exit.GetCode() != killedCode {
		t.Errorf("reported exit %d, expected %d", exit.GetCode(), killedCode)
	}
	if !strings.Contains(exit.GetReason(), "Exited (137) 1 second ago") {
		t.Errorf("the reason does not carry the engine's own status: %q", exit.GetReason())
	}
}

// "Cannot see it" is not "does not exist". When the engine cannot answer either question,
// the session says the node does not know rather than inventing a cause a customer would
// act on (AGENTS.md section 4.5).
func TestAnUnknownEndingSaysSoRatherThanGuessing(t *testing.T) {
	test := newHarness(t)
	test.pty.exitErr = errors.New("inspect failed: timeout")
	test.engine.afterAttach = func() (reconcile.Container, bool, error) {
		return reconcile.Container{}, false, errors.New("docker is not answering")
	}

	finished := test.start(t, nil)
	test.pty.stop()
	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}

	exit := wantExit(t, test.stream.frames())
	if strings.Contains(exit.GetReason(), "removed") || strings.Contains(exit.GetReason(), "stopped") {
		t.Errorf("an unanswerable engine was reported as a dead container: %q", exit.GetReason())
	}
	if !strings.Contains(exit.GetReason(), "could not tell how") {
		t.Errorf("the reason does not admit the node does not know: %q", exit.GetReason())
	}
}

// A container that is still running when the shell's exit status cannot be read is not a
// dead container either.
func TestALiveContainerIsNotBlamedForAnUnreadableExitStatus(t *testing.T) {
	test := newHarness(t)
	test.pty.exitErr = errors.New("inspect failed: timeout")

	finished := test.start(t, nil)
	test.pty.stop()
	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}

	exit := wantExit(t, test.stream.frames())
	if strings.Contains(exit.GetReason(), "container") {
		t.Errorf("a live container was blamed: %q", exit.GetReason())
	}
	if exit.GetCode() != killedCode {
		t.Errorf("reported exit %d, expected %d", exit.GetCode(), killedCode)
	}
}
