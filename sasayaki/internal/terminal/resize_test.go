package terminal

import (
	"errors"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The panel's half of the stream: what a resize does, and what happens to a frame that has
// no business being there.
//
// A resize is a message type rather than a byte sequence because the predecessor had no
// way to tell one from the other. On a phone this frame arrives every time the on-screen
// keyboard opens, so it is the most common non-keystroke event there is, and getting it
// wrong means curses applications draw over themselves for the whole session
// (design section 11.5).

func TestAResizeMidSessionReachesThePty(t *testing.T) {
	test := newHarness(t)

	finished := test.start(t, nil)
	test.pty.say([]byte("$ "))
	eventually(t, "the prompt", func() bool { return len(outputOf(t, test.stream.frames())) > 0 })

	test.stream.panelSends(resizeFrame(120, 40))
	eventually(t, "the resize", func() bool { return len(test.pty.windows()) == 1 })

	// The session carries on afterwards: a resize is an ordinary event, not the end of
	// anything.
	test.pty.say([]byte("still here"))
	test.pty.stop()
	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}

	windows := test.pty.windows()
	if len(windows) != 1 || windows[0].cols != 120 || windows[0].rows != 40 {
		t.Fatalf("the pty was resized to %v, expected one resize to 120x40", windows)
	}
	if got := string(outputOf(t, test.stream.frames())); got != "$ still here" {
		t.Errorf("the browser would have seen %q, expected the output either side of the resize", got)
	}
}

func TestSeveralResizesAllArrive(t *testing.T) {
	test := newHarness(t)

	finished := test.start(t, nil)
	for _, size := range []window{{80, 24}, {40, 90}, {132, 43}} {
		test.stream.panelSends(resizeFrame(size.cols, size.rows))
	}
	eventually(t, "three resizes", func() bool { return len(test.pty.windows()) == 3 })
	test.pty.stop()
	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}

	got := test.pty.windows()
	want := []window{{80, 24}, {40, 90}, {132, 43}}
	for index, expected := range want {
		if got[index] != expected {
			t.Errorf("resize %d was %v, expected %v", index, got[index], expected)
		}
	}
}

// A resize the engine refuses is not a reason to take somebody's shell away. Their session
// still works at the old size.
func TestAResizeThatFailsDoesNotEndTheSession(t *testing.T) {
	test := newHarness(t)
	test.pty.resizeErr = errors.New("the exec is not a tty")

	finished := test.start(t, nil)
	test.stream.panelSends(resizeFrame(100, 30))
	test.stream.panelSends(dataFrame("echo alive\n"))
	eventually(t, "the keystrokes", func() bool { return test.pty.keystrokes() == "echo alive\n" })

	test.pty.say([]byte("alive\r\n"))
	test.pty.stop()
	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}
	if got := string(outputOf(t, test.stream.frames())); got != "alive\r\n" {
		t.Errorf("the browser would have seen %q after a failed resize", got)
	}
}

func TestAResizeToNothingIsDropped(t *testing.T) {
	test := newHarness(t)

	finished := test.start(t, nil)
	test.stream.panelSends(resizeFrame(0, 0))
	test.stream.panelSends(dataFrame("x"))
	eventually(t, "the keystroke", func() bool { return test.pty.keystrokes() == "x" })
	test.pty.stop()
	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}
	if windows := test.pty.windows(); len(windows) != 0 {
		t.Fatalf("the pty was resized to %v by a frame that asked for nothing", windows)
	}
}

// The session id is on every frame precisely so that a frame which arrived by mistake can
// be dropped instead of typed into somebody else's container.
func TestAFrameForAnotherSessionIsDropped(t *testing.T) {
	test := newHarness(t)

	finished := test.start(t, nil)
	test.stream.panelSends(&wisperpb.TerminalFrame{
		SessionId: "sess-somebody-else",
		Payload:   &wisperpb.TerminalFrame_Data{Data: []byte("rm -rf /\n")},
	})
	test.stream.panelSends(&wisperpb.TerminalFrame{
		SessionId: "sess-somebody-else",
		Payload:   &wisperpb.TerminalFrame_Resize{Resize: &wisperpb.TerminalResize{Cols: 1, Rows: 1}},
	})
	test.stream.panelSends(dataFrame("whoami\n"))
	eventually(t, "the session's own keystrokes", func() bool { return test.pty.keystrokes() == "whoami\n" })

	test.pty.stop()
	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}
	if typed := test.pty.keystrokes(); typed != "whoami\n" {
		t.Fatalf("the pty received %q: a frame for another session was typed into this one", typed)
	}
	if windows := test.pty.windows(); len(windows) != 0 {
		t.Fatalf("another session's resize was applied to this pty: %v", windows)
	}
}

// TerminalAttached and TerminalExit only travel node to panel. One arriving the other way
// is dropped rather than guessed at.
func TestFramesThePanelMayNotSendAreDropped(t *testing.T) {
	test := newHarness(t)

	finished := test.start(t, nil)
	test.stream.panelSends(&wisperpb.TerminalFrame{
		SessionId: sessionID,
		Payload:   &wisperpb.TerminalFrame_Exit{Exit: &wisperpb.TerminalExit{Code: 0}},
	})
	test.stream.panelSends(&wisperpb.TerminalFrame{
		SessionId: sessionID,
		Payload:   &wisperpb.TerminalFrame_Attached{Attached: &wisperpb.TerminalAttached{}},
	})
	// A frame with no payload at all, which is what a client with a bug sends.
	test.stream.panelSends(&wisperpb.TerminalFrame{SessionId: sessionID})
	test.stream.panelSends(dataFrame("ls\n"))
	eventually(t, "the keystrokes", func() bool { return test.pty.keystrokes() == "ls\n" })

	test.pty.exitCode = 7
	test.pty.stop()
	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}
	// The node's own exit frame is the one that counts, not the one the panel sent.
	if code := wantExit(t, test.stream.frames()).GetCode(); code != 7 {
		t.Errorf("reported exit %d, expected the pty's own 7", code)
	}
}

// An empty Data frame is a browser flushing an empty batch. It is neither input nor an
// error, and it must not count as activity against the idle timeout.
func TestAnEmptyDataFrameIsHarmless(t *testing.T) {
	test := newHarness(t)

	finished := test.start(t, nil)
	test.stream.panelSends(&wisperpb.TerminalFrame{
		SessionId: sessionID,
		Payload:   &wisperpb.TerminalFrame_Data{Data: []byte{}},
	})
	test.stream.panelSends(dataFrame("q"))
	eventually(t, "the keystroke", func() bool { return test.pty.keystrokes() == "q" })
	test.pty.stop()
	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}
}
