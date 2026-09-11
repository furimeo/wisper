package files

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Resumable uploads, which is the half of this package a customer on mobile data notices.
//
// The property under test everywhere below is the same one: what is on disk and what the
// database says arrived never disagree. Every failure mode of a phone on a train - a
// dropped chunk, a duplicated chunk, chunks out of order, the daemon restarting in the
// middle - has to end either in the whole correct file or in a refusal, and never in a
// file that looks right and is not.

// session is the description that travels on every chunk.
func session(id, path string, content []byte, chunkSize int64) *wisperpb.UploadSession {
	return &wisperpb.UploadSession{
		SessionId:     id,
		Path:          path,
		TotalBytes:    int64(len(content)),
		ContentSha256: digestOf(content),
		ChunkSize:     chunkSize,
	}
}

// chunkOf cuts the index'th chunk out of the content, the way a browser would.
func chunkOf(content []byte, chunkSize int64, index int64) *wisperpb.FileChunk {
	start := index * chunkSize
	end := min(start+chunkSize, int64(len(content)))
	data := content[start:end]
	return &wisperpb.FileChunk{
		Offset:     start,
		ChunkIndex: index,
		Data:       data,
		Last:       end == int64(len(content)),
		Sha256:     digestOf(data),
	}
}

func chunkCount(content []byte, chunkSize int64) int64 {
	return (int64(len(content)) + chunkSize - 1) / chunkSize
}

func writeRequest(upload *wisperpb.UploadSession, chunk *wisperpb.FileChunk) *wisperpb.FileRequest {
	return &wisperpb.FileRequest{
		RequestId: "chunk-" + upload.GetSessionId(),
		Op: &wisperpb.FileRequest_Write{Write: &wisperpb.WriteChunk{
			Session: upload,
			Chunk:   chunk,
		}},
	}
}

func completeRequest(sessionID string) *wisperpb.FileRequest {
	return &wisperpb.FileRequest{
		RequestId: "complete-" + sessionID,
		Op:        &wisperpb.FileRequest_Complete{Complete: &wisperpb.CompleteUpload{SessionId: sessionID}},
	}
}

// sendChunk pushes one chunk and returns the events, so a test can look at the ack or at
// the refusal.
func (h *harness) sendChunk(t *testing.T, upload *wisperpb.UploadSession, chunk *wisperpb.FileChunk) []*wisperpb.FileEvent {
	t.Helper()
	return h.run(t, writeRequest(upload, chunk))
}

func (h *harness) complete(t *testing.T, sessionID string) []*wisperpb.FileEvent {
	t.Helper()
	return h.run(t, completeRequest(sessionID))
}

func (h *harness) resume(t *testing.T, sessionID string) *wisperpb.UploadState {
	t.Helper()
	events := h.run(t, &wisperpb.FileRequest{
		RequestId: "resume-" + sessionID,
		Op:        &wisperpb.FileRequest_Resume{Resume: &wisperpb.ResumeUpload{SessionId: sessionID}},
	})
	state := only(t, events).GetState()
	if state == nil {
		t.Fatalf("expected an UploadState, got %v", events)
	}
	return state
}

func TestASingleChunkUploadIsHowTheEditorSaves(t *testing.T) {
	test := newHarness(t)
	content := []byte("export const answer = 42\n")
	upload := session("sess-editor", "src/config.ts", content, 1<<20)

	ack := only(t, test.sendChunk(t, upload, chunkOf(content, 1<<20, 0))).GetAck()
	if ack == nil {
		t.Fatal("the chunk was not acknowledged")
	}
	if ack.GetReceivedBytes() != int64(len(content)) || ack.GetNextOffset() != int64(len(content)) {
		t.Fatalf("ack says %d received and next at %d, want %d and %d",
			ack.GetReceivedBytes(), ack.GetNextOffset(), len(content), len(content))
	}
	wantDone(t, test.complete(t, "sess-editor"))

	written, err := os.ReadFile(filepath.Join(test.volume, "src", "config.ts"))
	if err != nil {
		t.Fatalf("the file was not written: %v", err)
	}
	if string(written) != string(content) {
		t.Fatalf("the file holds %q", written)
	}
}

func TestAnEmptyFileCanBeUploaded(t *testing.T) {
	test := newHarness(t)
	content := []byte{}
	upload := session("sess-empty", "empty.txt", content, 1<<20)

	if ack := only(t, test.sendChunk(t, upload, chunkOf(content, 1<<20, 0))).GetAck(); ack == nil {
		t.Fatal("an empty chunk of an empty file was not acknowledged")
	}
	wantDone(t, test.complete(t, "sess-empty"))
	if _, err := os.Stat(filepath.Join(test.volume, "empty.txt")); err != nil {
		t.Fatalf("the empty file was not created: %v", err)
	}
}

// The test the design calls for by name: a connection that dropped in the middle, and a
// browser that comes back and asks what arrived. The daemon is restarted at the same time,
// because the session lives in SQLite precisely so that it can be.

func TestAnOverlargeChunkSizeIsRefused(t *testing.T) {
	test := newHarness(t)
	content := []byte("small file, absurd chunk size")
	upload := session("sess-huge-chunks", "file.bin", content, 1<<40)

	events := test.sendChunk(t, upload, chunkOf(content, 1<<40, 0))
	wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_TOO_LARGE)
}
