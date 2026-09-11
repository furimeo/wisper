package files

import (
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Two things happening at once, which is the normal case rather than the exotic one: a
// browser opens six connections, and a customer with two tabs is not doing anything wrong.

// Two customers - or one customer with two tabs - uploading different files to the same
// path at the same time. Exactly one may win, the loser has to be told, and the file must
// be one of the two rather than a mixture of both.
func TestTwoConcurrentUploadsToOnePathDoNotInterleave(t *testing.T) {
	test := newHarness(t)
	first := []byte(strings.Repeat("A", 4096))
	second := []byte(strings.Repeat("B", 4096))

	uploads := []*wisperpb.UploadSession{
		session("sess-first", "contested.bin", first, 1024),
		session("sess-second", "contested.bin", second, 1024),
	}
	contents := [][]byte{first, second}

	var waiting sync.WaitGroup
	outcomes := make([]*wisperpb.FileEvent, len(uploads))
	problems := make([]error, len(uploads))
	for index, upload := range uploads {
		waiting.Add(1)
		go func() {
			defer waiting.Done()
			for chunk := range chunkCount(contents[index], 1024) {
				if _, err := test.perform(t.Context(), writeRequest(upload, chunkOf(contents[index], 1024, chunk))); err != nil {
					problems[index] = err
					return
				}
			}
			events, err := test.perform(t.Context(), completeRequest(upload.GetSessionId()))
			if err != nil {
				problems[index] = err
				return
			}
			outcomes[index] = events[len(events)-1]
		}()
	}
	waiting.Wait()
	for _, problem := range problems {
		if problem != nil {
			t.Fatalf("an upload could not be answered: %v", problem)
		}
	}

	succeeded := 0
	for _, outcome := range outcomes {
		switch {
		case outcome.GetDone() != nil:
			succeeded++
		case outcome.GetError().GetCode() == wisperpb.FileErrorCode_FILE_ERROR_CODE_ALREADY_EXISTS:
		default:
			t.Fatalf("an upload ended as %v, and the only two outcomes are done and already-exists", outcome)
		}
	}
	if succeeded != 1 {
		t.Fatalf("%d of two uploads to one path succeeded, want exactly one", succeeded)
	}

	written, err := os.ReadFile(filepath.Join(test.volume, "contested.bin"))
	if err != nil {
		t.Fatalf("neither upload produced a file: %v", err)
	}
	if string(written) != string(first) && string(written) != string(second) {
		t.Fatal("the file is a mixture of two uploads, which is the failure this test exists for")
	}
	assertVolumeHasNoPartials(t, test)
}

// One session, its chunks sent in parallel the way a browser with six connections does.
func TestTheChunksOfOneSessionMayArriveInParallel(t *testing.T) {
	test := newHarness(t)
	const chunkSize int64 = 512
	content := []byte(strings.Repeat("wisper--", 1024)) // 8192 bytes, sixteen chunks
	upload := session("sess-parallel", "parallel.bin", content, chunkSize)

	var waiting sync.WaitGroup
	problems := make([]error, chunkCount(content, chunkSize))
	for index := range chunkCount(content, chunkSize) {
		waiting.Add(1)
		go func() {
			defer waiting.Done()
			_, problems[index] = test.perform(t.Context(), writeRequest(upload, chunkOf(content, chunkSize, index)))
		}()
	}
	waiting.Wait()
	for _, problem := range problems {
		if problem != nil {
			t.Fatalf("a chunk could not be answered: %v", problem)
		}
	}

	state := test.resume(t, "sess-parallel")
	if state.GetReceivedBytes() != int64(len(content)) {
		t.Fatalf("received_bytes = %d after every chunk, want %d",
			state.GetReceivedBytes(), len(content))
	}
	if len(state.GetReceived()) != 1 {
		t.Fatalf("received = %v, and a complete file is one coalesced range", state.GetReceived())
	}

	wantDone(t, test.complete(t, "sess-parallel"))
	written, err := os.ReadFile(filepath.Join(test.volume, "parallel.bin"))
	if err != nil {
		t.Fatalf("the file was not written: %v", err)
	}
	if string(written) != string(content) {
		t.Fatal("chunks that arrived in parallel reassembled into the wrong file")
	}
}
