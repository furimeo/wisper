package files

import (
	"context"
	"strconv"
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The discipline the panel depends on: one request, exactly one terminal event, and a
// cancellation that stops the work rather than being noticed after it finishes.

// terminal reports whether an event ends its operation. A chunk does only when it is the
// last one, which is what makes a download many events and one ending.
func terminal(event *wisperpb.FileEvent) bool {
	switch result := event.GetResult().(type) {
	case *wisperpb.FileEvent_Chunk:
		return result.Chunk.GetLast()
	case *wisperpb.FileEvent_Listing, *wisperpb.FileEvent_Info, *wisperpb.FileEvent_Ack,
		*wisperpb.FileEvent_State, *wisperpb.FileEvent_Size, *wisperpb.FileEvent_Done,
		*wisperpb.FileEvent_Error:
		return true
	default:
		return false
	}
}

// Every operation the panel can send, answered once. A new field in the oneof that nobody
// implemented shows up here as a request with no ending.
func TestEveryOperationEndsWithExactlyOneTerminalEvent(t *testing.T) {
	requests := map[string]*wisperpb.FileRequest{
		"list":    {Op: &wisperpb.FileRequest_List{List: &wisperpb.ListDirectory{}}},
		"stat":    {Op: &wisperpb.FileRequest_Stat{Stat: &wisperpb.StatPath{Path: "notes.txt"}}},
		"read":    {Op: &wisperpb.FileRequest_Read{Read: &wisperpb.ReadFile{Path: "notes.txt"}}},
		"mkdir":   {Op: &wisperpb.FileRequest_CreateDirectory{CreateDirectory: &wisperpb.CreateDirectory{Path: "made"}}},
		"move":    {Op: &wisperpb.FileRequest_Move{Move: &wisperpb.MovePath{From: "notes.txt", To: "moved.txt"}}},
		"delete":  {Op: &wisperpb.FileRequest_Delete{Delete: &wisperpb.DeletePath{Path: "moved.txt"}}},
		"chmod":   {Op: &wisperpb.FileRequest_ChangeMode{ChangeMode: &wisperpb.ChangeMode{Path: "tree", Mode: 0o755}}},
		"measure": {Op: &wisperpb.FileRequest_Measure{Measure: &wisperpb.MeasureDirectory{Path: "tree"}}},
		"resume":  {Op: &wisperpb.FileRequest_Resume{Resume: &wisperpb.ResumeUpload{SessionId: "sess-none"}}},
		"abort":   {Op: &wisperpb.FileRequest_Abort{Abort: &wisperpb.AbortUpload{SessionId: "sess-none"}}},
		"complete": {Op: &wisperpb.FileRequest_Complete{
			Complete: &wisperpb.CompleteUpload{SessionId: "sess-none"},
		}},
		"write": {Op: &wisperpb.FileRequest_Write{Write: &wisperpb.WriteChunk{
			Session: session("sess-answers", "uploaded.txt", []byte("hi"), 1<<20),
			Chunk:   chunkOf([]byte("hi"), 1<<20, 0),
		}}},
		"archive": {Op: &wisperpb.FileRequest_Archive{Archive: &wisperpb.CreateArchive{
			Paths: []string{"tree"}, Destination: "tree.zip",
			Format: wisperpb.ArchiveFormat_ARCHIVE_FORMAT_ZIP,
		}}},
		"extract": {Op: &wisperpb.FileRequest_Extract{Extract: &wisperpb.ExtractArchive{
			ArchivePath: "tree.zip", DestinationDirectory: "unpacked",
		}}},
		// Not an operation at all, and still answered rather than dropped.
		"nothing": {},
	}

	for name, request := range requests {
		t.Run(name, func(t *testing.T) {
			test := newHarness(t)
			test.write(t, "notes.txt", "hello")
			test.write(t, "tree/inner.txt", "x")

			events := test.run(t, request)
			endings := 0
			for index, event := range events {
				if terminal(event) {
					endings++
					if index != len(events)-1 {
						t.Fatalf("event %d ends the operation and %d more followed it",
							index, len(events)-1-index)
					}
				}
			}
			if endings != 1 {
				t.Fatalf("%d of %d events end the operation, want exactly one",
					endings, len(events))
			}
		})
	}
}

// Cancellation is routed by the stream, which cancels the context. Anything that can run
// long has to notice, or a customer who closed a download keeps a node's disk busy.
func TestCancellingADownloadStopsIt(t *testing.T) {
	test := newHarness(t)
	test.write(t, "big.log", strings.Repeat("wisper", 20000))
	test.host.limits.DownloadChunkBytes = 1024

	ctx, cancel := context.WithCancel(t.Context())
	sink := &cancelling{cancel: cancel, after: 3}
	request := &wisperpb.FileRequest{
		RequestId: "req-cancel",
		RootId:    volumeRootID,
		Op:        &wisperpb.FileRequest_Read{Read: &wisperpb.ReadFile{Path: "big.log"}},
	}
	if err := test.host.Handle(ctx, request, sink); err != nil {
		t.Fatalf("the host could not answer: %v", err)
	}

	if len(sink.events) > 6 {
		t.Fatalf("the download sent %d chunks after being cancelled at 3", len(sink.events))
	}
	wantError(t, sink.events, wisperpb.FileErrorCode_FILE_ERROR_CODE_CANCELLED)
}

func TestCancellingAMeasurementStopsIt(t *testing.T) {
	test := newHarness(t)
	for index := range 30 {
		test.write(t, "tree/f"+strconv.Itoa(index)+".txt", "12345")
	}

	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	sink := &recorder{}
	request := &wisperpb.FileRequest{
		RequestId: "req-cancel",
		RootId:    volumeRootID,
		Op:        &wisperpb.FileRequest_Measure{Measure: &wisperpb.MeasureDirectory{Path: "tree"}},
	}
	if err := test.host.Handle(ctx, request, sink); err != nil {
		t.Fatalf("the host could not answer: %v", err)
	}
	wantError(t, sink.events, wisperpb.FileErrorCode_FILE_ERROR_CODE_CANCELLED)
}

// A cancellation routed here instead of being answered by the stream is a bug in the
// wiring, and it is said out loud rather than absorbed.
func TestACancelRoutedToTheFileHostIsRefused(t *testing.T) {
	test := newHarness(t)
	events := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Cancel{Cancel: &wisperpb.CancelRequest{CancelledRequestId: "req-1"}},
	})
	wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR)
}

// A host cannot be built without the two things every request needs.
func TestNewRefusesAHostThatCouldAnswerNothing(t *testing.T) {
	if _, err := New(Options{StateDir: "/var/lib/wisper"}); err == nil {
		t.Error("a file host with no store was accepted")
	}
	if _, err := New(Options{Store: &recordingStore{}}); err == nil {
		t.Error("a file host with no state directory was accepted")
	}
	if _, err := New(Options{Store: &recordingStore{}, StateDir: "relative/path"}); err == nil {
		t.Error("a relative state directory was accepted, and it resolves against the working " +
			"directory the daemon happened to start in")
	}
}

// cancelling is an event sink that pulls the plug partway through, the way a customer
// closing a tab does.
type cancelling struct {
	cancel context.CancelFunc
	after  int
	events []*wisperpb.FileEvent
}

func (c *cancelling) Send(event *wisperpb.FileEvent) error {
	c.events = append(c.events, event)
	if len(c.events) == c.after {
		c.cancel()
	}
	return nil
}

// recordingStore is only ever nil-checked: New must refuse a missing state directory
// before it looks at anything else.
type recordingStore struct{ Store }
