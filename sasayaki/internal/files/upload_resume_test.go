package files

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The half of an upload that happens after the bytes: coming back to one, finishing one,
// and two of them arriving at once. The helpers these use are in upload_test.go, next to
// the chunk-level tests they were written for.

func TestAnUploadResumesAfterADisconnectAndARestart(t *testing.T) {
	test := newHarness(t)
	const chunkSize int64 = 8
	content := []byte(strings.Repeat("abcdefgh", 5)) // 40 bytes, five chunks
	upload := session("sess-resume", "photos/holiday.jpg", content, chunkSize)

	// Chunks 0 and 2 arrive. Chunk 1 was in flight when the lift doors closed, and chunks
	// 3 and 4 were never sent.
	test.sendChunk(t, upload, chunkOf(content, chunkSize, 0))
	test.sendChunk(t, upload, chunkOf(content, chunkSize, 2))

	// The daemon restarts. A new Host over the same database and the same disk is exactly
	// what comes back up, and it must know about this upload.
	restarted, err := New(Options{StateDir: test.stateDir, Store: test.store, Now: test.host.now})
	if err != nil {
		t.Fatalf("restart the file host: %v", err)
	}
	test.host = restarted

	state := test.resume(t, "sess-resume")
	if !state.GetKnown() {
		t.Fatal("the session was forgotten across a restart, and resume means nothing")
	}
	if state.GetReceivedBytes() != 16 {
		t.Fatalf("received_bytes = %d, want 16", state.GetReceivedBytes())
	}
	if len(state.GetReceived()) != 2 {
		t.Fatalf("received = %v, want two ranges with a hole between them", state.GetReceived())
	}
	if first := state.GetReceived()[0]; first.GetStart() != 0 || first.GetEndExclusive() != 8 {
		t.Errorf("the first range is [%d,%d), want [0,8)", first.GetStart(), first.GetEndExclusive())
	}
	if second := state.GetReceived()[1]; second.GetStart() != 16 || second.GetEndExclusive() != 24 {
		t.Errorf("the second range is [%d,%d), want [16,24)", second.GetStart(), second.GetEndExclusive())
	}

	// Completing now is refused: the file has a hole in it, and declaring it whole would
	// hand the customer a corrupt photo.
	wantError(t, test.complete(t, "sess-resume"), wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR)

	// The browser carries on from the first gap.
	if state.GetReceived()[0].GetEndExclusive() != 8 {
		t.Fatalf("the client would resume at %d", state.GetReceived()[0].GetEndExclusive())
	}
	for _, index := range []int64{1, 3, 4} {
		test.sendChunk(t, upload, chunkOf(content, chunkSize, index))
	}
	// And re-sends chunk 2, which it is not sure about. A retry must be harmless.
	test.sendChunk(t, upload, chunkOf(content, chunkSize, 2))

	wantDone(t, test.complete(t, "sess-resume"))
	written, err := os.ReadFile(filepath.Join(test.volume, "photos", "holiday.jpg"))
	if err != nil {
		t.Fatalf("the file was not written: %v", err)
	}
	if string(written) != string(content) {
		t.Fatalf("the reassembled file is wrong:\n got %q\nwant %q", written, content)
	}
}

func TestResumingASessionNobodyHasHeardOfIsAnsweredNotRefused(t *testing.T) {
	test := newHarness(t)
	state := test.resume(t, "sess-never-existed")
	if state.GetKnown() {
		t.Fatal("the node claimed to know a session it has never seen")
	}
}

func TestACorruptChunkIsRefusedRatherThanWritten(t *testing.T) {
	test := newHarness(t)
	content := []byte("the bytes that were meant to arrive")
	upload := session("sess-corrupt", "file.bin", content, 1<<20)

	chunk := chunkOf(content, 1<<20, 0)
	chunk.Data = []byte("the bytes that actually arrived...")
	events := test.sendChunk(t, upload, chunk)
	wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_CHECKSUM_MISMATCH)

	if state := test.resume(t, "sess-corrupt"); state.GetReceivedBytes() != 0 {
		t.Fatalf("a refused chunk was recorded as received: %d bytes", state.GetReceivedBytes())
	}
}

func TestAChunkThatDisagreesWithItsIndexIsRefused(t *testing.T) {
	test := newHarness(t)
	content := []byte(strings.Repeat("x", 16))
	upload := session("sess-offset", "file.bin", content, 8)

	chunk := chunkOf(content, 8, 1)
	// The attack this stops: a resumed upload whose client-supplied offset points at some
	// other part of the file.
	chunk.Offset = 0
	wantError(t, test.sendChunk(t, upload, chunk), wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR)
}

func TestAChunkPastTheDeclaredSizeIsRefused(t *testing.T) {
	test := newHarness(t)
	content := []byte(strings.Repeat("x", 16))
	upload := session("sess-toolong", "file.bin", content, 8)

	beyond := &wisperpb.FileChunk{
		Offset:     16,
		ChunkIndex: 2,
		Data:       []byte("12345678"),
		Sha256:     digestOf([]byte("12345678")),
	}
	wantError(t, test.sendChunk(t, upload, beyond), wisperpb.FileErrorCode_FILE_ERROR_CODE_TOO_LARGE)
}

func TestCompletingWithTheWrongContentHashLeavesNoFile(t *testing.T) {
	test := newHarness(t)
	content := []byte("what the browser sent")
	upload := session("sess-mismatch", "file.txt", content, 1<<20)
	// The browser declared a different file: the one it read a second time after the user
	// edited it, for instance.
	upload.ContentSha256 = digestOf([]byte("what the browser thought it sent"))

	test.sendChunk(t, upload, chunkOf(content, 1<<20, 0))
	wantError(t, test.complete(t, "sess-mismatch"), wisperpb.FileErrorCode_FILE_ERROR_CODE_CHECKSUM_MISMATCH)

	if _, err := os.Stat(filepath.Join(test.volume, "file.txt")); !os.IsNotExist(err) {
		t.Fatalf("a file that failed its checksum was left in place: %v", err)
	}
	// Nothing is left behind: no session, no parts, and no half-written temporary.
	if state := test.resume(t, "sess-mismatch"); state.GetKnown() {
		t.Error("the session survived a checksum failure, and the customer cannot start again cleanly")
	}
	assertVolumeHasNoPartials(t, test)
}

func TestAnUploadOntoAnExistingFileNeedsOverwrite(t *testing.T) {
	test := newHarness(t)
	test.write(t, "notes.txt", "what was already there")
	content := []byte("what is being uploaded")

	upload := session("sess-clash", "notes.txt", content, 1<<20)
	test.sendChunk(t, upload, chunkOf(content, 1<<20, 0))
	wantError(t, test.complete(t, "sess-clash"), wisperpb.FileErrorCode_FILE_ERROR_CODE_ALREADY_EXISTS)

	kept, _ := os.ReadFile(filepath.Join(test.volume, "notes.txt"))
	if string(kept) != "what was already there" {
		t.Fatalf("the existing file was replaced anyway: %q", kept)
	}
	assertVolumeHasNoPartials(t, test)
}

func TestAbortingAnUploadRemovesItsParts(t *testing.T) {
	test := newHarness(t)
	content := []byte(strings.Repeat("x", 2048))
	upload := session("sess-abort", "abandoned.bin", content, 1024)
	test.sendChunk(t, upload, chunkOf(content, 1024, 0))

	parts := filepath.Join(test.stateDir, "uploads", "parts", "sess-abort")
	if _, err := os.Stat(parts); err != nil {
		t.Fatalf("the parts file was not written: %v", err)
	}

	wantDone(t, test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Abort{Abort: &wisperpb.AbortUpload{SessionId: "sess-abort"}},
	}))
	if _, err := os.Stat(parts); !os.IsNotExist(err) {
		t.Fatalf("the parts file survived the abort: %v", err)
	}
	if state := test.resume(t, "sess-abort"); state.GetKnown() {
		t.Error("the session survived the abort")
	}
}

func TestAnUploadBiggerThanTheQuotaIsRefusedBeforeAnyByteIsWritten(t *testing.T) {
	test := newHarness(t)
	test.write(t, "already-there.bin", strings.Repeat("x", 900))

	// Republish the volume with a quota it cannot fit under.
	spec := specWithRoots()
	spec.Generation = 8
	spec.FileRoots[1].QuotaBytes = 1000
	if err := test.store.SaveSpec(t.Context(), spec, "quota", noon); err != nil {
		t.Fatalf("save the spec: %v", err)
	}

	content := []byte(strings.Repeat("y", 500))
	upload := session("sess-quota", "too-big.bin", content, 1<<20)
	events := test.sendChunk(t, upload, chunkOf(content, 1<<20, 0))
	wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_QUOTA_EXCEEDED)

	if _, err := os.Stat(filepath.Join(test.stateDir, "uploads", "parts", "sess-quota")); !os.IsNotExist(err) {
		t.Fatal("a refused upload still wrote its parts file")
	}
}

// assertVolumeHasNoPartials proves the volume holds no half-written temporary of this
// package's own making. A customer who lists their folder after a failed upload must see
// what was there before and nothing else.
func assertVolumeHasNoPartials(t *testing.T, test *harness) {
	t.Helper()
	err := filepath.WalkDir(test.volume, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if strings.HasPrefix(entry.Name(), ".wisper-") {
			t.Errorf("a partial file was left behind: %s", path)
		}
		return nil
	})
	if err != nil {
		t.Fatalf("walk the volume: %v", err)
	}
}
