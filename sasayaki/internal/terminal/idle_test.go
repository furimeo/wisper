package terminal

import (
	"strings"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The two clocks a session runs against.
//
// Both exist because a terminal is not a page a customer closes; it is a process running
// as their application, and a forgotten tab keeps it alive. The idle timeout ends a
// session nobody is typing into, and the duration ceiling ends one where something is
// producing output forever - a `top`, a `tail -f` - which no amount of idleness would ever
// catch (terminal.proto, StartTerminal).
//
// The timings here are short so the suite is not, and generous enough that a loaded
// machine does not fail them.

func TestAnIdleTerminalIsClosedAndSaysWhy(t *testing.T) {
	test := newHarness(t, func(options *Options) {
		options.IdleTimeout = 150 * time.Millisecond
	})

	started := time.Now()
	finished := test.start(t, nil)
	if err := finish(t, finished); err != nil {
		t.Fatalf("an idle session was reported as a failure: %v", err)
	}
	if elapsed := time.Since(started); elapsed < 150*time.Millisecond {
		t.Fatalf("the session was closed after %s, before its idle timeout had passed", elapsed)
	}

	exit := wantExit(t, test.stream.frames())
	if exit.GetCode() != killedCode {
		t.Errorf("reported exit %d, expected %d", exit.GetCode(), killedCode)
	}
	if !strings.Contains(exit.GetReason(), "no input") {
		t.Errorf("the reason does not say the session was idle: %q", exit.GetReason())
	}
	if !test.pty.isClosed() {
		t.Error("the exec was left open after the idle timeout, so the shell keeps running")
	}
}

// The timeout is measured from the last keystroke, not from the start of the session.
func TestTypingKeepsATerminalAlive(t *testing.T) {
	test := newHarness(t, func(options *Options) {
		options.IdleTimeout = 600 * time.Millisecond
	})

	finished := test.start(t, nil)
	for range 5 {
		time.Sleep(100 * time.Millisecond)
		test.stream.panelSends(dataFrame("x"))
	}
	// Half a second past the start, which is most of the way through a second idle period.
	select {
	case err := <-finished:
		t.Fatalf("the session ended while the customer was typing: %v", err)
	default:
	}

	// And it does end once they stop.
	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}
	if !strings.Contains(wantExit(t, test.stream.frames()).GetReason(), "no input") {
		t.Error("the session ended for a reason other than being idle")
	}
}

// Output is not activity. A program that prints forever into a terminal nobody is watching
// is the case the idle timeout is for, so a chatty process must not hold the session open.
func TestOutputAloneDoesNotKeepATerminalAlive(t *testing.T) {
	test := newHarness(t, func(options *Options) {
		options.IdleTimeout = 150 * time.Millisecond
	})

	finished := test.start(t, nil)
	stop, chatter := make(chan struct{}), make(chan struct{})
	go func() {
		defer close(chatter)
		for {
			select {
			case <-stop:
				return
			case <-time.After(20 * time.Millisecond):
				test.pty.say([]byte("tick\r\n"))
			}
		}
	}()

	err := finish(t, finished)
	close(stop)
	<-chatter
	if err != nil {
		t.Fatalf("the session failed: %v", err)
	}
	if !strings.Contains(wantExit(t, test.stream.frames()).GetReason(), "no input") {
		t.Error("a session with output but no input was not closed as idle")
	}
}

// The ceiling applies to a session that is busy, which is the whole point of having a
// second clock.
func TestASessionCannotOutliveItsCeiling(t *testing.T) {
	test := newHarness(t, func(options *Options) {
		options.IdleTimeout = 30 * time.Second
		options.MaxDuration = 200 * time.Millisecond
	})

	finished := test.start(t, nil)
	stop, typing := make(chan struct{}), make(chan struct{})
	go func() {
		defer close(typing)
		for {
			select {
			case <-stop:
				return
			case <-time.After(20 * time.Millisecond):
				test.stream.panelSends(dataFrame("j"))
			}
		}
	}()

	err := finish(t, finished)
	close(stop)
	<-typing
	if err != nil {
		t.Fatalf("the session failed: %v", err)
	}

	exit := wantExit(t, test.stream.frames())
	if !strings.Contains(exit.GetReason(), "limit") {
		t.Errorf("the reason does not mention the ceiling: %q", exit.GetReason())
	}
	if exit.GetCode() != killedCode {
		t.Errorf("reported exit %d, expected %d", exit.GetCode(), killedCode)
	}
}

// The panel sends both numbers on every request, and they win over the node's defaults.
func TestTheRequestsOwnTimeoutsAreHonoured(t *testing.T) {
	test := newHarness(t, func(options *Options) {
		options.IdleTimeout = time.Hour
		options.MaxDuration = time.Hour
	})

	started := time.Now()
	finished := test.start(t, &wisperpb.StartTerminal{MaxDurationSeconds: 1})
	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}
	if elapsed := time.Since(started); elapsed > 4*time.Second {
		t.Fatalf("the session ran for %s, so the request's one-second ceiling was ignored", elapsed)
	}
	if !strings.Contains(wantExit(t, test.stream.frames()).GetReason(), "limit") {
		t.Error("the session did not end at the ceiling the request asked for")
	}
}

func TestNonsenseTimeoutsFallBackToTheNodesOwn(t *testing.T) {
	test := newHarness(t, func(options *Options) {
		options.IdleTimeout = 150 * time.Millisecond
	})

	// A negative timeout is a bug on the way in, and honouring it would end the session
	// before it drew its first prompt.
	finished := test.start(t, &wisperpb.StartTerminal{
		IdleTimeoutSeconds: -60,
		MaxDurationSeconds: -60,
	})
	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}
	if !strings.Contains(wantExit(t, test.stream.frames()).GetReason(), "no input") {
		t.Error("a session with a negative idle timeout did not fall back to the node's own")
	}
}
