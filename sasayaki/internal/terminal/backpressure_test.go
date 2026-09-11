package terminal

import (
	"bytes"
	"testing"
	"time"
)

// A process that writes faster than the browser can read must be slowed down, not
// buffered.
//
// This is the failure that does not show up in a demo. One customer runs `yes` in a
// terminal on a phone with two bars of signal; the pty produces gigabytes a minute and the
// stream takes kilobytes a second. A node that queued the difference would be killed by
// the kernel's out-of-memory killer, taking every other customer's containers with it, and
// the log afterwards would show nothing but a terminal session.

func TestASlowReaderStopsThePtyBeingDrained(t *testing.T) {
	test := newHarness(t)
	// The attachment goes through and everything after it waits, which is what a stream
	// that has stopped draining looks like from in here.
	test.stream.gate = make(chan struct{})
	test.stream.blockAfter = 1

	chunk := bytes.Repeat([]byte("y\n"), readChunkBytes/2)
	written := 0
	finished := test.start(t, nil)
	// The pty's own queue holds 64 of these, which is two megabytes: far more than the
	// session is allowed to have read by the time the assertion below runs.
	for range 64 {
		test.pty.say(chunk)
		written += len(chunk)
	}

	// Long enough that a session which was going to run away has done so.
	time.Sleep(250 * time.Millisecond)

	// One chunk in the framer, outputQueue waiting, one held by the reader that is blocked
	// handing it over. Anything past that is a queue that grows with the process's output.
	ceiling := int64((outputQueue + 2) * readChunkBytes)
	served := test.pty.served.Load()
	switch {
	case served == 0:
		t.Fatal("the session read nothing at all, so this test proves nothing about backpressure")
	case served > ceiling:
		t.Fatalf("the session read %d bytes from a pty whose reader was blocked; the bound is %d, "+
			"so a chatty process can make this node allocate without limit", served, ceiling)
	}

	// Once the reader catches up the session finishes normally and nothing was dropped:
	// backpressure slows a terminal down, it does not lose bytes out of the middle of it.
	close(test.stream.gate)
	test.pty.stop()
	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}
	if got := len(outputOf(t, test.stream.frames())); got != written {
		t.Fatalf("the process wrote %d bytes and the browser would have seen %d", written, got)
	}
}
