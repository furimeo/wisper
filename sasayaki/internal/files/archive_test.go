package files

import (
	"archive/tar"
	"archive/zip"
	"compress/gzip"
	"errors"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Packaging a directory on the node, which is how a customer on a phone gets a folder off
// the platform at all.

func TestArchivingADirectoryProducesAReadableZip(t *testing.T) {
	test := newHarness(t)
	test.write(t, "site/index.html", "<h1>hello</h1>")
	test.write(t, "site/assets/app.css", "body{}")
	test.mkdir(t, "site/empty")

	done := wantDone(t, test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Archive{Archive: &wisperpb.CreateArchive{
			Paths:       []string{"site"},
			Destination: "site.zip",
			Format:      wisperpb.ArchiveFormat_ARCHIVE_FORMAT_ZIP,
		}},
	}))
	if done.GetAffected() == 0 {
		t.Error("the archive reported no entries")
	}

	names := zipNames(t, filepath.Join(test.volume, "site.zip"))
	want := []string{"site/", "site/assets/", "site/assets/app.css", "site/empty/", "site/index.html"}
	if strings.Join(names, ",") != strings.Join(want, ",") {
		t.Fatalf("the zip holds %v, want %v", names, want)
	}
}

func TestArchivingAsTarGz(t *testing.T) {
	test := newHarness(t)
	test.write(t, "data/one.txt", "first")
	test.write(t, "data/two.txt", "second")

	wantDone(t, test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Archive{Archive: &wisperpb.CreateArchive{
			Paths:       []string{"data"},
			Destination: "data.tar.gz",
			Format:      wisperpb.ArchiveFormat_ARCHIVE_FORMAT_TAR_GZ,
		}},
	}))

	file, err := os.Open(filepath.Join(test.volume, "data.tar.gz"))
	if err != nil {
		t.Fatalf("open the archive: %v", err)
	}
	defer file.Close()
	compressor, err := gzip.NewReader(file)
	if err != nil {
		t.Fatalf("the archive is not a gzip: %v", err)
	}
	reader := tar.NewReader(compressor)

	found := map[string]string{}
	for {
		header, err := reader.Next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			t.Fatalf("read the tar: %v", err)
		}
		if header.Typeflag != tar.TypeReg {
			continue
		}
		content, err := io.ReadAll(reader)
		if err != nil {
			t.Fatalf("read an entry: %v", err)
		}
		found[header.Name] = string(content)
	}
	if found["data/one.txt"] != "first" || found["data/two.txt"] != "second" {
		t.Fatalf("the tar holds %v", found)
	}
}

func TestAnArchiveCannotBeWrittenInsideWhatItArchives(t *testing.T) {
	test := newHarness(t)
	test.write(t, "site/index.html", "x")

	events := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Archive{Archive: &wisperpb.CreateArchive{
			Paths:       []string{"site"},
			Destination: "site/backup.zip",
			Format:      wisperpb.ArchiveFormat_ARCHIVE_FORMAT_ZIP,
		}},
	})
	wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR)
}

func TestAnUnnamedArchiveFormatIsRefused(t *testing.T) {
	test := newHarness(t)
	test.write(t, "a.txt", "x")

	events := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Archive{Archive: &wisperpb.CreateArchive{
			Paths:       []string{"a.txt"},
			Destination: "out.bin",
		}},
	})
	wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_UNSUPPORTED_ARCHIVE)
	if _, err := os.Stat(filepath.Join(test.volume, "out.bin")); !os.IsNotExist(err) {
		t.Error("a refused archive left a file behind")
	}
}

func TestAFailedArchiveLeavesNoPartialFile(t *testing.T) {
	test := newHarness(t)

	events := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Archive{Archive: &wisperpb.CreateArchive{
			Paths:       []string{"does-not-exist"},
			Destination: "out.zip",
			Format:      wisperpb.ArchiveFormat_ARCHIVE_FORMAT_ZIP,
		}},
	})
	wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_NOT_FOUND)

	entries, err := os.ReadDir(test.volume)
	if err != nil {
		t.Fatalf("read the volume: %v", err)
	}
	for _, entry := range entries {
		t.Errorf("the failed archive left %q behind", entry.Name())
	}
}

// The round trip is the real test of both halves: what came out of the archiver has to go
// back in through the extractor and produce the tree it started as.
func TestArchivingAndExtractingRoundTrips(t *testing.T) {
	test := newHarness(t)
	test.write(t, "project/main.go", "package main")
	test.write(t, "project/internal/deep/file.txt", "deep")
	test.mkdir(t, "project/empty")

	wantDone(t, test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Archive{Archive: &wisperpb.CreateArchive{
			Paths:       []string{"project"},
			Destination: "project.zip",
			Format:      wisperpb.ArchiveFormat_ARCHIVE_FORMAT_ZIP,
		}},
	}))
	wantDone(t, test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Extract{Extract: &wisperpb.ExtractArchive{
			ArchivePath:          "project.zip",
			DestinationDirectory: "restored",
		}},
	}))

	content, err := os.ReadFile(filepath.Join(test.volume, "restored", "project", "internal", "deep", "file.txt"))
	if err != nil {
		t.Fatalf("the deep file did not survive the round trip: %v", err)
	}
	if string(content) != "deep" {
		t.Fatalf("the deep file holds %q", content)
	}
	if info, err := os.Stat(filepath.Join(test.volume, "restored", "project", "empty")); err != nil || !info.IsDir() {
		t.Fatalf("the empty directory did not survive the round trip: %v", err)
	}
}

func zipNames(t *testing.T, path string) []string {
	t.Helper()
	reader, err := zip.OpenReader(path)
	if err != nil {
		t.Fatalf("open %s as a zip: %v", path, err)
	}
	defer reader.Close()

	names := make([]string, 0, len(reader.File))
	for _, file := range reader.File {
		names = append(names, file.Name)
	}
	sort.Strings(names)
	return names
}
