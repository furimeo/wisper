package files

import (
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Browsing: the operations a customer uses before they change anything.

func TestListingPutsDirectoriesFirstAndHidesDotfiles(t *testing.T) {
	test := newHarness(t)
	test.write(t, "zebra.txt", "z")
	test.write(t, "apple.txt", "a")
	test.write(t, ".env", "SECRET=1")
	test.mkdir(t, "src")
	test.mkdir(t, ".git")

	events := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_List{List: &wisperpb.ListDirectory{}},
	})
	listing := only(t, events).GetListing()
	if listing == nil {
		t.Fatalf("expected a listing, got %v", events)
	}

	got := names(listing)
	want := []string{"src", "apple.txt", "zebra.txt"}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("listing = %v, want %v", got, want)
	}
	if listing.GetTotalEntries() != 3 {
		t.Errorf("total_entries = %d, want 3", listing.GetTotalEntries())
	}

	withHidden := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_List{List: &wisperpb.ListDirectory{IncludeHidden: true}},
	})
	if got := len(only(t, withHidden).GetListing().GetEntries()); got != 5 {
		t.Errorf("include_hidden showed %d entries, want 5", got)
	}
}

func TestListingPagesWithAStableCursor(t *testing.T) {
	test := newHarness(t)
	for _, name := range []string{"a", "b", "c", "d", "e"} {
		test.write(t, name+".txt", name)
	}

	seen := make([]string, 0, 5)
	cursor := ""
	for pages := 0; pages < 5; pages++ {
		events := test.run(t, &wisperpb.FileRequest{
			Op: &wisperpb.FileRequest_List{List: &wisperpb.ListDirectory{PageSize: 2, Cursor: cursor}},
		})
		listing := only(t, events).GetListing()
		seen = append(seen, names(listing)...)
		if listing.GetTotalEntries() != 5 {
			t.Fatalf("total_entries = %d on every page, want 5", listing.GetTotalEntries())
		}
		cursor = listing.GetNextCursor()
		if cursor == "" {
			break
		}
	}

	want := "a.txt,b.txt,c.txt,d.txt,e.txt"
	if strings.Join(seen, ",") != want {
		t.Fatalf("paging produced %v, want %s", seen, want)
	}
}

func TestListingRefusesACursorItDidNotIssue(t *testing.T) {
	test := newHarness(t)
	events := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_List{List: &wisperpb.ListDirectory{Cursor: "not-a-cursor!"}},
	})
	wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR)
}

func TestStatDescribesTheRootItself(t *testing.T) {
	test := newHarness(t)
	events := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Stat{Stat: &wisperpb.StatPath{Path: "/"}},
	})
	info := only(t, events).GetInfo()
	if info == nil || !info.GetDirectory() {
		t.Fatalf("the root did not describe itself as a directory: %v", events)
	}
	if info.GetName() != "api / data" {
		t.Errorf("the root is named %q, and the breadcrumb shows the label the panel published",
			info.GetName())
	}
}

func TestStatOfAMissingPathIsNotFound(t *testing.T) {
	test := newHarness(t)
	events := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Stat{Stat: &wisperpb.StatPath{Path: "nothing/here.txt"}},
	})
	wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_NOT_FOUND)
}

func TestReadingAFileArrivesInChunksThatEndWithLast(t *testing.T) {
	test := newHarness(t)
	content := strings.Repeat("wisper", 5000) // 30,000 bytes
	test.write(t, "big.log", content)
	test.host.limits.DownloadChunkBytes = 4096

	events := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Read{Read: &wisperpb.ReadFile{Path: "big.log"}},
	})

	var assembled strings.Builder
	for index, event := range events {
		chunk := event.GetChunk()
		if chunk == nil {
			t.Fatalf("event %d is a %T, and a read produces only chunks", index, event.GetResult())
		}
		if got := digestOf(chunk.GetData()); got != chunk.GetSha256() {
			t.Fatalf("chunk %d carries the wrong checksum", index)
		}
		if int64(assembled.Len()) != chunk.GetOffset() {
			t.Fatalf("chunk %d claims offset %d, and %d bytes have arrived",
				index, chunk.GetOffset(), assembled.Len())
		}
		assembled.Write(chunk.GetData())
		if chunk.GetLast() != (index == len(events)-1) {
			t.Fatalf("chunk %d has last = %v, and only the final chunk ends a transfer",
				index, chunk.GetLast())
		}
	}
	if assembled.String() != content {
		t.Fatalf("the download reassembled to %d bytes, want %d", assembled.Len(), len(content))
	}
}

func TestReadingARangeIsWhatTheEditorOpens(t *testing.T) {
	test := newHarness(t)
	test.write(t, "log.txt", "0123456789")

	events := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Read{Read: &wisperpb.ReadFile{Path: "log.txt", Offset: 3, MaxBytes: 4}},
	})
	chunk := only(t, events).GetChunk()
	if chunk == nil || string(chunk.GetData()) != "3456" {
		t.Fatalf("a ranged read produced %v", events)
	}
	if !chunk.GetLast() {
		t.Error("a ranged read that returned everything it was asked for did not end the transfer")
	}
}

func TestReadingAnEmptyFileStillEndsTheTransfer(t *testing.T) {
	test := newHarness(t)
	test.write(t, "empty.txt", "")

	events := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Read{Read: &wisperpb.ReadFile{Path: "empty.txt"}},
	})
	chunk := only(t, events).GetChunk()
	if chunk == nil || !chunk.GetLast() || len(chunk.GetData()) != 0 {
		t.Fatalf("an empty file produced %v, and a customer would wait forever", events)
	}
}

func TestReadingADirectoryIsRefusedWithAUsefulCode(t *testing.T) {
	test := newHarness(t)
	test.mkdir(t, "src")

	events := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Read{Read: &wisperpb.ReadFile{Path: "src"}},
	})
	wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_IS_A_DIRECTORY)
}
