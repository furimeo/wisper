package terminal

import (
	"bytes"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Output has to arrive exactly as the process wrote it, and all of it has to arrive before
// the exit frame.
//
// This is the half of the predecessor's bug that nobody noticed until a customer ran
// something with colour in it: bytes were copied past the framer, so a chunk boundary
// could land in the middle of an escape sequence and the engine's own stream header was
// delivered as text.

func TestOutputSurvivesWhateverBytesTheProcessWrote(t *testing.T) {
	test := newHarness(t)

	everyByte := make([]byte, 256)
	for value := range everyByte {
		everyByte[value] = byte(value)
	}
	pieces := [][]byte{
		everyByte,
		// An escape sequence deliberately cut in half across two writes, which is what a
		// pty does whenever a program's output crosses a read boundary.
		[]byte("\x1b[3"),
		[]byte("1;42m warning \x1b[0m"),
		// Not valid UTF-8 in any encoding, which a `cat` of a binary file produces within
		// the first line.
		{0xff, 0xfe, 0xfd, 0x00, 0x80},
		[]byte("done\r\n"),
	}

	finished := test.start(t, nil)
	var written []byte
	for _, piece := range pieces {
		test.pty.say(piece)
		written = append(written, piece...)
	}
	test.pty.stop()

	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}

	frames := test.stream.frames()
	if got := outputOf(t, frames); !bytes.Equal(got, written) {
		t.Fatalf("the browser would have seen %q, the process wrote %q", got, written)
	}

	// One write, one frame: nothing here coalesces or splits what the process produced,
	// because a terminal that buffers until a newline makes a program that thinks look hung.
	data := 0
	for _, frame := range frames {
		if _, isData := frame.GetPayload().(*wisperpb.TerminalFrame_Data); isData {
			data++
		}
	}
	if data != len(pieces) {
		t.Errorf("the process made %d writes and the session sent %d data frames", len(pieces), data)
	}
}

func TestEveryByteArrivesBeforeTheExitFrame(t *testing.T) {
	test := newHarness(t)
	test.pty.exitCode = 3

	finished := test.start(t, nil)
	for _, line := range []string{"one\r\n", "two\r\n", "three\r\n"} {
		test.pty.say([]byte(line))
	}
	test.pty.stop()

	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}

	frames := test.stream.frames()
	seenExit := false
	for _, frame := range frames {
		switch frame.GetPayload().(type) {
		case *wisperpb.TerminalFrame_Exit:
			seenExit = true
		case *wisperpb.TerminalFrame_Data:
			if seenExit {
				t.Fatal("output was sent after the exit frame, so the browser would have " +
					"drawn it into a terminal it had already closed")
			}
		}
	}
	if got := string(outputOf(t, frames)); got != "one\r\ntwo\r\nthree\r\n" {
		t.Errorf("the browser would have seen %q", got)
	}
	if code := wantExit(t, frames).GetCode(); code != 3 {
		t.Errorf("reported exit %d, expected 3", code)
	}
}

// A single write larger than one read is split across frames, and the split must be
// invisible once the browser puts them back together.
func TestALargeWriteIsSplitButNotAltered(t *testing.T) {
	test := newHarness(t)

	huge := make([]byte, readChunkBytes*2+7)
	for index := range huge {
		huge[index] = byte(index % 251)
	}

	finished := test.start(t, nil)
	test.pty.say(huge)
	test.pty.stop()

	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}

	frames := test.stream.frames()
	if got := outputOf(t, frames); !bytes.Equal(got, huge) {
		t.Fatalf("a %d-byte write came back as %d bytes and they differ", len(huge), len(got))
	}
	for _, frame := range frames {
		if _, isData := frame.GetPayload().(*wisperpb.TerminalFrame_Data); !isData {
			continue
		}
		if len(frame.GetData()) > readChunkBytes {
			t.Fatalf("a frame carries %d bytes, which is past the %d-byte read size",
				len(frame.GetData()), readChunkBytes)
		}
	}
}

// A stream that will not take the first frame is a session that cannot start, and saying
// so is better than pumping a pty into nothing.
func TestAStreamThatCannotBeWrittenToIsReported(t *testing.T) {
	test := newHarness(t)
	test.stream.sendErr = errAlreadyGone

	err := finish(t, test.start(t, nil))
	if err == nil {
		t.Fatal("a session whose stream refused every frame was reported as a success")
	}
	if !test.pty.isClosed() {
		t.Error("the exec was left open after the stream failed")
	}
}
