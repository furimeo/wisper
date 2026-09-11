package terminal

import (
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// One session id, one session; and a ceiling on how many a node will hold.

// The session id is what makes "drop a frame that is not mine" meaningful. Two streams
// claiming one id would turn that into a coin toss, and the losing side is somebody's
// keystrokes arriving in the wrong container.
func TestASecondStreamCannotClaimALiveSessionId(t *testing.T) {
	test := newHarness(t)

	finished := test.start(t, nil)
	eventually(t, "the first session to attach", func() bool { return test.host.live.count() == 1 })

	second := newFakeStream()
	t.Cleanup(second.hangUp)
	err := test.host.Serve(t.Context(), &wisperpb.StartTerminal{
		SessionId:  sessionID,
		WorkloadId: workloadID,
	}, second)
	if err == nil {
		t.Fatal("a second stream was allowed to claim a session id that was already live")
	}
	if !strings.Contains(err.Error(), "already attached") {
		t.Errorf("the refusal does not say the id is taken: %v", err)
	}
	if len(second.frames()) != 0 {
		t.Error("the duplicate stream was sent frames")
	}

	// The first session is untouched by the refusal.
	test.pty.stop()
	if err := finish(t, finished); err != nil {
		t.Fatalf("the live session was disturbed by a duplicate: %v", err)
	}

	// And once it has ended the id can be used again, which is what happens when a
	// customer reopens the same terminal after a reconnect.
	test.pty = newFakePty(containerID, 80, 24)
	test.engine.pty = test.pty
	test.stream = newFakeStream()
	t.Cleanup(test.stream.hangUp)
	reopened := test.start(t, nil)
	test.pty.stop()
	if err := finish(t, reopened); err != nil {
		t.Fatalf("the session id could not be reused after its session ended: %v", err)
	}
}

// Every session holds a hijacked connection, three goroutines and a bounded output queue.
// The panel decides who may open a terminal; nothing on its side stops a browser opening
// five hundred, and a node that agreed would fail in a way that took the customer's
// workloads with it.
func TestANodeRefusesMoreTerminalsThanItsLimit(t *testing.T) {
	test := newHarness(t, func(options *Options) { options.MaxSessions = 1 })

	finished := test.start(t, nil)
	eventually(t, "the first session to attach", func() bool { return test.host.live.count() == 1 })

	second := newFakeStream()
	t.Cleanup(second.hangUp)
	err := test.host.Serve(t.Context(), &wisperpb.StartTerminal{
		SessionId:  "sess-second",
		WorkloadId: workloadID,
	}, second)
	if err == nil {
		t.Fatal("a node with a one-terminal limit opened a second one")
	}
	if !strings.Contains(err.Error(), "limit") {
		t.Errorf("the refusal does not mention the limit: %v", err)
	}

	test.pty.stop()
	if err := finish(t, finished); err != nil {
		t.Fatalf("the live session failed: %v", err)
	}
	if held := test.host.live.count(); held != 0 {
		t.Fatalf("%d slots are still held once the session ended", held)
	}
}
