package files

import (
	"archive/zip"
	"io/fs"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Extraction, which is where a hostile file gets its turn.
//
// Three attacks, three tests: an entry path that climbs out (zip slip), an entry that
// expands to more than the disk holds (a zip bomb), and a symlink entry whose target
// escapes even though its own name does not.

// zipEntry is one thing to put in a test archive.
type zipEntry struct {
	name    string
	content string
	mode    fs.FileMode
	symlink bool
}

// buildZip writes an archive by hand, because the interesting inputs are ones no archiver
// would produce.
func buildZip(t *testing.T, path string, entries []zipEntry) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatalf("create %s: %v", filepath.Dir(path), err)
	}
	file, err := os.Create(path)
	if err != nil {
		t.Fatalf("create %s: %v", path, err)
	}
	defer file.Close()

	writer := zip.NewWriter(file)
	for _, entry := range entries {
		header := &zip.FileHeader{Name: entry.name, Method: zip.Deflate}
		mode := entry.mode
		if mode == 0 {
			mode = 0o644
		}
		if entry.symlink {
			mode |= fs.ModeSymlink
		}
		header.SetMode(mode)
		target, err := writer.CreateHeader(header)
		if err != nil {
			t.Fatalf("add %q: %v", entry.name, err)
		}
		if _, err := target.Write([]byte(entry.content)); err != nil {
			t.Fatalf("write %q: %v", entry.name, err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("finish %s: %v", path, err)
	}
}

func (h *harness) extract(t *testing.T, archive, destination string, overwrite bool) []*wisperpb.FileEvent {
	t.Helper()
	return h.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Extract{Extract: &wisperpb.ExtractArchive{
			ArchivePath:          archive,
			DestinationDirectory: destination,
			Overwrite:            overwrite,
		}},
	})
}

func TestZipSlipIsRefusedAndWritesNothingOutside(t *testing.T) {
	test := newHarness(t)
	buildZip(t, filepath.Join(test.volume, "hostile.zip"), []zipEntry{
		{name: "harmless.txt", content: "fine"},
		{name: "../../../escaped.txt", content: "owned"},
	})

	events := test.extract(t, "hostile.zip", "unpacked", false)
	wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_PATH_ESCAPES_ROOT)

	// Not above the volume, not above the state directory, nowhere.
	for _, candidate := range []string{
		filepath.Join(test.stateDir, "escaped.txt"),
		filepath.Join(test.stateDir, "volumes", "escaped.txt"),
		filepath.Join(test.volume, "escaped.txt"),
		filepath.Join(filepath.Dir(test.stateDir), "escaped.txt"),
	} {
		if _, err := os.Stat(candidate); err == nil {
			t.Fatalf("the archive wrote %s", candidate)
		}
	}
}

func TestAnAbsoluteEntryPathIsContained(t *testing.T) {
	test := newHarness(t)
	buildZip(t, filepath.Join(test.volume, "absolute.zip"), []zipEntry{
		{name: "/etc/cron.d/backdoor", content: "* * * * * root sh"},
	})

	wantDone(t, test.extract(t, "absolute.zip", "unpacked", false))

	// The leading slash is stripped and the entry lands inside the destination, which is
	// the only place any entry may land.
	if _, err := os.Stat(filepath.Join(test.volume, "unpacked", "etc", "cron.d", "backdoor")); err != nil {
		t.Fatalf("the entry did not land inside the destination: %v", err)
	}
}

// A zip bomb: a small archive whose contents expand out of all proportion to it. The
// refusal comes from the ratio between the two, so it fires long before the disk does.
func TestAZipBombIsRefused(t *testing.T) {
	test := newHarness(t)
	// A megabyte of zeroes deflates to about a kilobyte: a ratio of roughly a thousand,
	// against a limit of five hundred.
	buildZip(t, filepath.Join(test.volume, "bomb.zip"), []zipEntry{
		{name: "payload.bin", content: strings.Repeat("\x00", 1<<20)},
	})

	events := test.extract(t, "bomb.zip", "unpacked", false)
	wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_TOO_LARGE)

	// Nothing of it is left on the disk it was aimed at.
	if entries, err := os.ReadDir(filepath.Join(test.volume, "unpacked")); err == nil && len(entries) > 0 {
		t.Fatalf("the refused bomb left %d entries behind", len(entries))
	}
}

func TestAnExtractionStopsAtTheNodesOwnCeiling(t *testing.T) {
	test := newHarness(t)
	// Incompressible, so the ratio guard cannot be what refuses it: this is the absolute
	// ceiling doing the work.
	buildZip(t, filepath.Join(test.volume, "large.zip"), []zipEntry{
		{name: "payload.bin", content: randomish(64 << 10)},
	})
	test.host.limits.MaxExtractBytes = 4096

	events := test.extract(t, "large.zip", "unpacked", false)
	wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_TOO_LARGE)
}

func TestAnArchiveWithTooManyEntriesIsRefused(t *testing.T) {
	test := newHarness(t)
	entries := make([]zipEntry, 0, 50)
	for index := range 50 {
		entries = append(entries, zipEntry{name: "file" + strconv.Itoa(index) + ".txt", content: "x"})
	}
	buildZip(t, filepath.Join(test.volume, "many.zip"), entries)
	test.host.limits.MaxArchiveEntries = 10

	events := test.extract(t, "many.zip", "unpacked", false)
	wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_TOO_LARGE)
}

func TestASymlinkEntryPointingOutOfTheRootIsRefused(t *testing.T) {
	test := newHarness(t)
	buildZip(t, filepath.Join(test.volume, "linked.zip"), []zipEntry{
		{name: "escape", content: "../../../../etc", symlink: true},
	})

	events := test.extract(t, "linked.zip", "unpacked", false)
	wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_PATH_ESCAPES_ROOT)
	if _, err := os.Lstat(filepath.Join(test.volume, "unpacked", "escape")); err == nil {
		t.Fatal("the escaping link was created anyway")
	}
}

func TestAnAbsoluteSymlinkEntryIsRefused(t *testing.T) {
	test := newHarness(t)
	buildZip(t, filepath.Join(test.volume, "linked.zip"), []zipEntry{
		{name: "escape", content: "/etc/shadow", symlink: true},
	})

	events := test.extract(t, "linked.zip", "unpacked", false)
	wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_PATH_ESCAPES_ROOT)
}

// A relative link that stays inside is kept, because a node_modules tree is largely links
// and an extraction that dropped them produces an application that does not start.
func TestASymlinkEntryThatStaysInsideIsRecreated(t *testing.T) {
	test := newHarness(t)
	buildZip(t, filepath.Join(test.volume, "linked.zip"), []zipEntry{
		{name: "packages/real/index.js", content: "module.exports = 1"},
		{name: "packages/.bin/tool", content: "../real/index.js", symlink: true},
	})

	events := test.extract(t, "linked.zip", "unpacked", false)
	if failed := events[len(events)-1].GetError(); failed != nil {
		if strings.Contains(failed.GetDetail(), "create the link") {
			t.Skipf("this host does not allow creating symbolic links: %s", failed.GetDetail())
		}
		t.Fatalf("the extraction failed: %s", failed.GetDetail())
	}

	link := filepath.Join(test.volume, "unpacked", "packages", ".bin", "tool")
	info, err := os.Lstat(link)
	if err != nil {
		t.Fatalf("the link was not created: %v", err)
	}
	if info.Mode()&fs.ModeSymlink == 0 {
		t.Fatal("the entry was extracted as a regular file rather than as a link")
	}
}
