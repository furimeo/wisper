package files

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Extraction of archives that are not trying anything: the two formats, an unreadable
// file, and replacing what is already there. The hostile ones are in extract_test.go.

func TestExtractingSomethingThatIsNotAnArchive(t *testing.T) {
	test := newHarness(t)
	test.write(t, "notes.txt", "this is not a zip")

	events := test.extract(t, "notes.txt", "unpacked", false)
	wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_UNSUPPORTED_ARCHIVE)
}

func TestExtractingOverAnExistingFileNeedsOverwrite(t *testing.T) {
	test := newHarness(t)
	buildZip(t, filepath.Join(test.volume, "small.zip"), []zipEntry{
		{name: "config.json", content: "{\"new\": true}"},
	})
	test.write(t, "unpacked/config.json", "{\"old\": true}")

	wantError(t, test.extract(t, "small.zip", "unpacked", false),
		wisperpb.FileErrorCode_FILE_ERROR_CODE_ALREADY_EXISTS)
	kept, _ := os.ReadFile(filepath.Join(test.volume, "unpacked", "config.json"))
	if string(kept) != "{\"old\": true}" {
		t.Fatalf("the existing file was replaced: %q", kept)
	}

	wantDone(t, test.extract(t, "small.zip", "unpacked", true))
	replaced, _ := os.ReadFile(filepath.Join(test.volume, "unpacked", "config.json"))
	if string(replaced) != "{\"new\": true}" {
		t.Fatalf("the file was not replaced: %q", replaced)
	}
}

func TestATarGzIsExtractedToo(t *testing.T) {
	test := newHarness(t)
	var buffer bytes.Buffer
	compressor := gzip.NewWriter(&buffer)
	writer := tar.NewWriter(compressor)
	body := "hello from a tarball"
	if err := writer.WriteHeader(&tar.Header{
		Name: "release/index.html", Mode: 0o644, Size: int64(len(body)), Typeflag: tar.TypeReg,
	}); err != nil {
		t.Fatalf("write the header: %v", err)
	}
	if _, err := writer.Write([]byte(body)); err != nil {
		t.Fatalf("write the body: %v", err)
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("close the tar: %v", err)
	}
	if err := compressor.Close(); err != nil {
		t.Fatalf("close the gzip: %v", err)
	}
	test.write(t, "release.tar.gz", buffer.String())

	wantDone(t, test.extract(t, "release.tar.gz", "unpacked", false))
	content, err := os.ReadFile(filepath.Join(test.volume, "unpacked", "release", "index.html"))
	if err != nil {
		t.Fatalf("the entry was not extracted: %v", err)
	}
	if string(content) != body {
		t.Fatalf("the entry holds %q", content)
	}
}

func TestATarGzWithATraversingEntryIsRefused(t *testing.T) {
	test := newHarness(t)
	var buffer bytes.Buffer
	compressor := gzip.NewWriter(&buffer)
	writer := tar.NewWriter(compressor)
	body := "owned"
	if err := writer.WriteHeader(&tar.Header{
		Name: "../../escaped.txt", Mode: 0o644, Size: int64(len(body)), Typeflag: tar.TypeReg,
	}); err != nil {
		t.Fatalf("write the header: %v", err)
	}
	if _, err := writer.Write([]byte(body)); err != nil {
		t.Fatalf("write the body: %v", err)
	}
	writer.Close()
	compressor.Close()
	test.write(t, "hostile.tar.gz", buffer.String())

	wantError(t, test.extract(t, "hostile.tar.gz", "unpacked", false),
		wisperpb.FileErrorCode_FILE_ERROR_CODE_PATH_ESCAPES_ROOT)
	if _, err := os.Stat(filepath.Join(test.stateDir, "escaped.txt")); err == nil {
		t.Fatal("the tarball escaped the root")
	}
}

// randomish is content that does not compress, so a size limit is what refuses it rather
// than a ratio.
func randomish(size int) string {
	builder := strings.Builder{}
	builder.Grow(size)
	value := uint32(2166136261)
	for range size {
		value = value*16777619 + 1
		builder.WriteByte(byte(value >> 13))
	}
	return builder.String()
}
