package build

import (
	"strings"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Build output on the way to the browser: whole lines, the right stream, and an honest
// account of what was dropped.

func testClock() func() time.Time {
	at := time.Date(2026, 9, 11, 10, 0, 0, 0, time.UTC)
	return func() time.Time {
		at = at.Add(time.Millisecond)
		return at
	}
}

func TestLogEmitsWholeLinesEvenWhenTheEngineCutsThem(t *testing.T) {
	sink := &fakeSink{}
	log := newBuildLog(sink, "stream-1", "77", testClock())

	// One line arriving in three reads, which is exactly what a follow on a chatty
	// container looks like.
	log.stdout().Write([]byte("Compil"))
	log.stdout().Write([]byte("ing sr"))
	log.stdout().Write([]byte("c/main.ts\nnext line\n"))

	lines := sink.lines()
	if len(lines) != 2 {
		t.Fatalf("expected two whole lines, got %v", lines)
	}
	if lines[0] != "Compiling src/main.ts\n" || lines[1] != "next line\n" {
		t.Fatalf("the lines were not reassembled: %v", lines)
	}
}

func TestLogHoldsAPartialLineUntilItIsFlushed(t *testing.T) {
	sink := &fakeSink{}
	log := newBuildLog(sink, "stream-1", "77", testClock())

	log.stdout().Write([]byte("no newline yet"))
	if len(sink.lines()) != 0 {
		t.Fatalf("a partial line was sent early: %v", sink.lines())
	}

	// A stage boundary flushes, so a command that exited without a trailing newline still
	// has its last words delivered before the next stage's first line.
	log.flush()
	if got := sink.text(); got != "no newline yet" {
		t.Fatalf("the held line was not flushed: %q", got)
	}
}

func TestLogFlushesACarriageReturnSoAProgressBarGetsThrough(t *testing.T) {
	sink := &fakeSink{}
	log := newBuildLog(sink, "stream-1", "77", testClock())

	log.stdout().Write([]byte("[====      ] 40%\r"))
	if got := sink.text(); !strings.Contains(got, "40%") {
		t.Fatalf("a progress update never reached the customer: %q", got)
	}
}

func TestLogDoesNotHoldAnUnterminatedLineForever(t *testing.T) {
	sink := &fakeSink{}
	log := newBuildLog(sink, "stream-1", "77", testClock())

	log.stdout().Write([]byte(strings.Repeat("x", heldLineLimit+10)))
	if sink.text() == "" {
		t.Fatal("a build tool that never writes a newline stopped the log entirely")
	}
}

func TestLogKeepsStdoutAndStderrApart(t *testing.T) {
	sink := &fakeSink{}
	log := newBuildLog(sink, "stream-1", "77", testClock())

	// A half-written stderr line must not be completed by the next stdout one: the crash
	// and the access log are two different things.
	log.stderr().Write([]byte("error: "))
	log.stdout().Write([]byte("still working\n"))
	log.stderr().Write([]byte("no such file\n"))

	var out, errs []string
	for _, chunk := range sink.sent {
		if chunk.GetKind() == wisperpb.LogStreamKind_LOG_STREAM_KIND_STDERR {
			errs = append(errs, string(chunk.GetData()))
			continue
		}
		out = append(out, string(chunk.GetData()))
	}
	if strings.Join(out, "") != "still working\n" {
		t.Fatalf("stdout was not itself: %v", out)
	}
	if strings.Join(errs, "") != "error: no such file\n" {
		t.Fatalf("stderr was not itself: %v", errs)
	}
}

func TestLogSplitsALineTooBigForOneChunk(t *testing.T) {
	sink := &fakeSink{}
	log := newBuildLog(sink, "stream-1", "77", testClock())

	log.stdout().Write([]byte(strings.Repeat("y", chunkLimit*2+5) + "\n"))
	for _, chunk := range sink.sent {
		if len(chunk.GetData()) > chunkLimit {
			t.Fatalf("a chunk of %d bytes was sent, past the %d limit",
				len(chunk.GetData()), chunkLimit)
		}
	}
	if len(sink.sent) < 3 {
		t.Fatalf("expected the line to be split, got %d chunk(s)", len(sink.sent))
	}
}

func TestLogReportsWhatTheQueueRefused(t *testing.T) {
	sink := &fakeSink{Refuse: true}
	log := newBuildLog(sink, "stream-1", "77", testClock())

	log.stdout().Write([]byte("first line\n"))
	log.stdout().Write([]byte("second line\n"))

	// The panel came back.
	sink.Refuse = false
	log.stdout().Write([]byte("third line\n"))

	if len(sink.sent) != 1 {
		t.Fatalf("expected only the chunk that got through, got %d", len(sink.sent))
	}
	dropped := sink.sent[0].GetDroppedBytes()
	if dropped != int64(len("first line\n")+len("second line\n")) {
		t.Fatalf("the gap was reported as %d bytes", dropped)
	}
}

func TestLogEndsTheSubscriptionOnce(t *testing.T) {
	sink := &fakeSink{}
	log := newBuildLog(sink, "stream-1", "77", testClock())

	log.stdout().Write([]byte("trailing, no newline"))
	log.end()
	log.end()

	ends := 0
	for _, chunk := range sink.sent {
		if chunk.GetEnd() {
			ends++
		}
	}
	if ends != 1 {
		t.Fatalf("expected exactly one end frame, got %d", ends)
	}
	if !strings.Contains(sink.text(), "trailing, no newline") {
		t.Fatalf("ending the stream lost what was held: %q", sink.text())
	}
}

func TestLogWithNoStreamIdSendsNothing(t *testing.T) {
	sink := &fakeSink{}
	log := newBuildLog(sink, "", "77", testClock())

	log.say("the panel did not ask to watch this one")
	log.end()

	if len(sink.sent) != 0 {
		t.Fatalf("chunks were pushed for a subscription nobody opened: %v", sink.lines())
	}
}

func TestLogTagsEveryChunkForTheRightBuild(t *testing.T) {
	sink := &fakeSink{}
	log := newBuildLog(sink, "stream-9", "77", testClock())

	log.say("hello")
	if len(sink.sent) == 0 {
		t.Fatal("nothing was sent")
	}
	chunk := sink.sent[0]
	if chunk.GetStreamId() != "stream-9" || chunk.GetSubjectId() != "77" {
		t.Fatalf("a chunk went out under stream %q subject %q",
			chunk.GetStreamId(), chunk.GetSubjectId())
	}
	if chunk.GetSource() != wisperpb.LogSource_LOG_SOURCE_BUILD {
		t.Fatalf("a build's output was tagged as %s", chunk.GetSource())
	}
	if chunk.GetAt() == nil {
		t.Fatal("a chunk went out with no timestamp")
	}
}
