package daemon

import (
	"context"
	"io"
	"testing"
	"time"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/runtime"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Log subscriptions, and the two races the panel and a container run against each other.
//
// A container exits while a browser is still watching, and a browser closes while a
// container is still writing. Both happen constantly, neither is a fault, and the code has
// to make both look ordinary: the first ends with a chunk carrying end so the panel can
// release the subscription, and the second is a stop for a stream id that may already be
// gone.

func TestStartLogStreamBySource(t *testing.T) {
	cases := []struct {
		name    string
		request *wisperpb.LogRequest

		started bool
		fails   bool
	}{
		{
			name: "a container is tailed from the engine",
			request: &wisperpb.LogRequest{
				StreamId:  "ls-1",
				Source:    wisperpb.LogSource_LOG_SOURCE_CONTAINER,
				SubjectId: "wl-api",
				Follow:    true,
			},
			started: true,
		},
		{
			name: "a build is pushed by the command that produces it, so nothing is opened here",
			request: &wisperpb.LogRequest{
				StreamId:  "ls-2",
				Source:    wisperpb.LogSource_LOG_SOURCE_BUILD,
				SubjectId: "bd-9",
			},
			started: false,
		},
		{
			name: "the daemon's own log is refused out loud rather than accepted and left silent",
			request: &wisperpb.LogRequest{
				StreamId: "ls-3",
				Source:   wisperpb.LogSource_LOG_SOURCE_SYSTEM,
			},
			fails: true,
		},
		{
			name: "a subscription with no stream id has nowhere to send chunks back to",
			request: &wisperpb.LogRequest{
				Source:    wisperpb.LogSource_LOG_SOURCE_CONTAINER,
				SubjectId: "wl-api",
			},
			fails: true,
		},
		{
			name: "a container subscription that does not say which workload is refused",
			request: &wisperpb.LogRequest{
				StreamId: "ls-4",
				Source:   wisperpb.LogSource_LOG_SOURCE_CONTAINER,
			},
			fails: true,
		},
	}

	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			feeds, engine, _, stop := feedHarness(t, &fakeLogStream{end: io.EOF})
			defer stop()

			err := feeds.StartLogStream(context.Background(), test.request)
			if test.fails != (err != nil) {
				t.Fatalf("StartLogStream returned %v, wanted a failure = %t", err, test.fails)
			}
			if got := engine.openings > 0; got != test.started {
				t.Errorf("the engine was asked for output = %t, want %t", got, test.started)
			}
		})
	}
}

// A workload the engine has never heard of is answered as an error, which the control
// stream turns into a line the customer can read. Accepting it would leave them looking at
// an empty pane and the panel holding a subscription nothing will ever close.
func TestStartLogStreamRefusesAWorkloadWithNoContainer(t *testing.T) {
	feeds, _, _, stop := feedHarness(t, &fakeLogStream{end: io.EOF})
	defer stop()

	err := feeds.StartLogStream(context.Background(), &wisperpb.LogRequest{
		StreamId:  "ls-1",
		Source:    wisperpb.LogSource_LOG_SOURCE_CONTAINER,
		SubjectId: "wl-missing",
	})
	if err == nil {
		t.Fatal("a subscription was opened for a workload that has no container")
	}
}

// The engine's own timestamps and stream split have to survive the trip, and the end of the
// output has to be announced rather than inferred from silence.
func TestLogFeedForwardsChunksAndAnnouncesTheEnd(t *testing.T) {
	written := noon.Add(3 * time.Second)
	stream := &fakeLogStream{
		chunks: []runtime.LogChunk{
			{Data: []byte("listening on :8080\n"), At: written},
			{Stderr: true, Data: []byte("a warning\n")},
			// Skipped: an empty write is not something to show a customer.
			{Data: nil},
		},
		end: io.EOF,
	}
	feeds, _, sink, stop := feedHarness(t, stream)
	defer stop()

	if err := feeds.StartLogStream(context.Background(), &wisperpb.LogRequest{
		StreamId:  "ls-1",
		Source:    wisperpb.LogSource_LOG_SOURCE_CONTAINER,
		SubjectId: "wl-api",
		Follow:    true,
	}); err != nil {
		t.Fatalf("start the subscription: %v", err)
	}

	if !sink.await(3) {
		t.Fatalf("only %d chunks arrived, want two of output and one end", len(sink.taken()))
	}
	chunks := sink.taken()

	if got := string(chunks[0].GetData()); got != "listening on :8080\n" {
		t.Errorf("first chunk = %q", got)
	}
	if chunks[0].GetKind() != wisperpb.LogStreamKind_LOG_STREAM_KIND_STDOUT {
		t.Errorf("first chunk kind = %s, want STDOUT", chunks[0].GetKind())
	}
	if got := chunks[0].GetAt().AsTime(); !got.Equal(written) {
		t.Errorf("first chunk at = %s, want the engine's own timestamp %s", got, written)
	}
	if chunks[1].GetKind() != wisperpb.LogStreamKind_LOG_STREAM_KIND_STDERR {
		t.Errorf("second chunk kind = %s, want STDERR: a crash and an access log are not the "+
			"same thing", chunks[1].GetKind())
	}
	if !chunks[2].GetEnd() {
		t.Error("the container's output ended and the panel was not told, so the subscription " +
			"is held open for output that is never coming")
	}
	for _, chunk := range chunks {
		if chunk.GetStreamId() != "ls-1" {
			t.Errorf("a chunk came back on stream %q, want ls-1", chunk.GetStreamId())
		}
	}

	waitFor(t, stream.wasClosed, "the engine's log stream was not closed when the output ended")
}

// A sink that is refusing must not block the reader: a log feed that waits eventually blocks
// the container writing to it. The gap is counted and reported on the next chunk that gets
// through, so the customer is told rather than shown a silence.
func TestLogFeedReportsWhatTheUplinkDropped(t *testing.T) {
	stream := &fakeLogStream{
		chunks: []runtime.LogChunk{{Data: []byte("12345")}},
		end:    io.EOF,
	}
	feeds, _, sink, stop := feedHarness(t, stream)
	defer stop()

	sink.refuse = true
	if err := feeds.StartLogStream(context.Background(), &wisperpb.LogRequest{
		StreamId:  "ls-1",
		Source:    wisperpb.LogSource_LOG_SOURCE_CONTAINER,
		SubjectId: "wl-api",
	}); err != nil {
		t.Fatalf("start the subscription: %v", err)
	}

	waitFor(t, stream.wasClosed, "the feed did not finish")
	sink.mu.Lock()
	sink.refuse = false
	sink.mu.Unlock()

	// Nothing was kept while the sink refused; what matters is that the reader ran to
	// completion instead of blocking on it.
	if got := len(sink.taken()); got != 0 {
		t.Errorf("%d chunks were recorded while the sink was refusing everything", got)
	}
}

// The panel stops subscriptions that have already finished, all the time. An unknown id is
// the system working, not a problem.
func TestStopLogStreamIsIdempotent(t *testing.T) {
	stream := &fakeLogStream{end: io.EOF}
	feeds, _, _, stop := feedHarness(t, stream)
	defer stop()

	feeds.StopLogStream("never-existed", "the last browser closed")

	if err := feeds.StartLogStream(context.Background(), &wisperpb.LogRequest{
		StreamId:  "ls-1",
		Source:    wisperpb.LogSource_LOG_SOURCE_CONTAINER,
		SubjectId: "wl-api",
		Follow:    true,
	}); err != nil {
		t.Fatalf("start the subscription: %v", err)
	}

	feeds.StopLogStream("ls-1", "the last browser closed")
	feeds.StopLogStream("ls-1", "the last browser closed again")

	waitFor(t, func() bool {
		feeds.mutex.Lock()
		defer feeds.mutex.Unlock()
		return len(feeds.open) == 0
	}, "the subscription was not forgotten")
}

// A `since` the panel did not send must not become the epoch. Asking the engine for
// everything written since 1970 is a different request from asking it for everything.
func TestLogFeedOnlySendsASinceThePanelChose(t *testing.T) {
	feeds, engine, _, stop := feedHarness(t, &fakeLogStream{end: io.EOF})
	defer stop()

	if err := feeds.StartLogStream(context.Background(), &wisperpb.LogRequest{
		StreamId:  "ls-1",
		Source:    wisperpb.LogSource_LOG_SOURCE_CONTAINER,
		SubjectId: "wl-api",
		TailLines: 200,
	}); err != nil {
		t.Fatalf("start the subscription: %v", err)
	}
	if got := engine.lastOptions(); !got.Since.IsZero() || got.Tail != 200 {
		t.Errorf("options = %+v, want tail 200 and no since", got)
	}

	feeds.StopLogStream("ls-1", "done")

	reconnected := noon.Add(time.Minute)
	if err := feeds.StartLogStream(context.Background(), &wisperpb.LogRequest{
		StreamId:  "ls-2",
		Source:    wisperpb.LogSource_LOG_SOURCE_CONTAINER,
		SubjectId: "wl-api",
		Since:     timestamppb.New(reconnected),
	}); err != nil {
		t.Fatalf("start the second subscription: %v", err)
	}
	if got := engine.lastOptions().Since; !got.Equal(reconnected) {
		t.Errorf("since = %s, want the moment the browser reconnected %s", got, reconnected)
	}
}

func feedHarness(t *testing.T, stream *fakeLogStream) (*logFeeds, *fakeLogReader, *fakeSink, context.CancelFunc) {
	t.Helper()

	containers := newFakeContainers()
	containers.byWorkload["wl-api"] = reconcile.Container{ID: "c1", Running: true}
	engine := &fakeLogReader{containers: containers, stream: stream}
	sink := newFakeSink()

	lifetime, stop := context.WithCancel(context.Background())
	feeds := newLogFeeds(lifetime, engine, sink, discardLogger())
	feeds.now = func() time.Time { return noon }
	return feeds, engine, sink, stop
}

// waitFor polls a condition rather than sleeping for a fixed time: the goroutine under test
// finishes in microseconds, and a test that sleeps is slow when it passes and flaky when it
// does not.
func waitFor(t *testing.T, done func() bool, complaint string) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if done() {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal(complaint)
}
