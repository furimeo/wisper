package backup

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"io"
	"maps"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// The archive format itself: what goes in comes out, and what should never have been in the
// archive never reaches the filesystem.

func TestAVolumeSurvivesTheRoundTrip(t *testing.T) {
	root := stateRoot(t)
	source := filepath.Join(root, "source")
	files := map[string]string{
		"app.db":                 "the customer's data",
		"logs/today.log":         "a line\nand another\n",
		"deeply/nested/file.txt": "still here",
		"empty/":                 "",
	}
	writeTree(t, source, files)

	var archive bytes.Buffer
	compressed := gzip.NewWriter(&archive)
	stats, err := writeVolumeArchive(source, compressed)
	if err != nil {
		t.Fatalf("write the archive: %v", err)
	}
	if err := compressed.Close(); err != nil {
		t.Fatalf("finish compressing: %v", err)
	}
	if stats.Files != 3 {
		t.Errorf("the archive holds %d files, want 3", stats.Files)
	}

	target := filepath.Join(root, "target")
	if err := os.MkdirAll(target, 0o755); err != nil {
		t.Fatalf("create the target: %v", err)
	}
	if _, err := extractVolumeArchive(&archive, target); err != nil {
		t.Fatalf("extract: %v", err)
	}

	want := map[string]string{
		"app.db":                 "the customer's data",
		"logs/today.log":         "a line\nand another\n",
		"deeply/nested/file.txt": "still here",
	}
	if got := readTree(t, target); !maps.Equal(got, want) {
		t.Errorf("extracted %v, want %v", got, want)
	}
	if _, err := os.Stat(filepath.Join(target, "empty")); err != nil {
		t.Errorf("the empty directory did not survive: %v", err)
	}
}

// tarWith builds a gzipped tar from entries given as name -> contents, with no validation at
// all, which is the point: this is the archive a bug or an attacker would produce.
func tarWith(t *testing.T, entries [][2]string) io.Reader {
	t.Helper()
	var out bytes.Buffer
	compressed := gzip.NewWriter(&out)
	archive := tar.NewWriter(compressed)
	for _, entry := range entries {
		header := &tar.Header{
			Typeflag: tar.TypeReg,
			Name:     entry[0],
			Mode:     0o644,
			Size:     int64(len(entry[1])),
		}
		if err := archive.WriteHeader(header); err != nil {
			t.Fatalf("write the header for %s: %v", entry[0], err)
		}
		if _, err := io.WriteString(archive, entry[1]); err != nil {
			t.Fatalf("write %s: %v", entry[0], err)
		}
	}
	if err := archive.Close(); err != nil {
		t.Fatalf("close the archive: %v", err)
	}
	if err := compressed.Close(); err != nil {
		t.Fatalf("finish compressing: %v", err)
	}
	return &out
}

// Path traversal is a build failure rather than a bug report (AGENTS.md section 5), so the
// extractor is tested against the shapes it has to refuse rather than trusted to.
func TestExtractingRefusesAnEntryThatLeavesTheVolume(t *testing.T) {
	root := stateRoot(t)

	for name, entry := range map[string]string{
		"a parent directory":  "../escaped.txt",
		"several of them":     "a/b/../../../escaped.txt",
		"an absolute path":    "/etc/cron.d/escaped",
		"a windows separator": `..\escaped.txt`,
	} {
		t.Run(name, func(t *testing.T) {
			target := filepath.Join(root, strings.ReplaceAll(name, " ", "-"))
			if err := os.MkdirAll(target, 0o755); err != nil {
				t.Fatalf("create the target: %v", err)
			}

			_, err := extractVolumeArchive(tarWith(t, [][2]string{{entry, "owned"}}), target)
			if err == nil {
				t.Fatalf("the extractor accepted %q", entry)
			}

			// And nothing was written outside the directory being restored into.
			if _, err := os.Stat(filepath.Join(root, "escaped.txt")); !os.IsNotExist(err) {
				t.Errorf("%q reached the filesystem", entry)
			}
		})
	}
}

// A staged archive is only reused when it still hashes to what the manifest says. The
// alternative - trusting the manifest - is a file of the right length full of the wrong bytes
// becoming somebody's only backup.
func TestAStagedArchiveIsReusedOnlyWhileItStillHashesRight(t *testing.T) {
	root := stateRoot(t)

	archive, err := stageArchive(root, "b1", volumeExtension, func(out io.Writer) error {
		_, err := io.WriteString(out, "the bytes of an archive")
		return err
	})
	if err != nil {
		t.Fatalf("stage the archive: %v", err)
	}

	found, ok, err := loadStagedArchive(root, "b1", volumeExtension)
	if err != nil || !ok {
		t.Fatalf("an intact archive was not recognised: ok=%v err=%v", ok, err)
	}
	if found.SHA256 != archive.SHA256 || found.Size != archive.Size {
		t.Errorf("recognised it as %+v, staged %+v", found, archive)
	}

	contents, err := os.ReadFile(archive.Path)
	if err != nil {
		t.Fatalf("read the staged archive: %v", err)
	}
	contents[len(contents)/2] ^= 0xff
	if err := os.WriteFile(archive.Path, contents, 0o600); err != nil {
		t.Fatalf("corrupt the staged archive: %v", err)
	}

	_, ok, err = loadStagedArchive(root, "b1", volumeExtension)
	if err != nil {
		t.Fatalf("look at the corrupted archive: %v", err)
	}
	if ok {
		t.Error("a corrupted staged archive was offered for reuse")
	}
}

// The two files a staged archive consists of go together. A manifest left behind would be
// picked up by a later run with the same id and matched against an archive that is not there.
func TestDiscardingAStagedArchiveTakesItsManifestToo(t *testing.T) {
	root := stateRoot(t)
	if _, err := stageArchive(root, "b1", volumeExtension, func(out io.Writer) error {
		_, err := io.WriteString(out, "bytes")
		return err
	}); err != nil {
		t.Fatalf("stage the archive: %v", err)
	}
	if err := discardStaged(root, "b1", volumeExtension); err != nil {
		t.Fatalf("discard: %v", err)
	}

	entries, err := os.ReadDir(filepath.Join(root, workDirectory))
	if err != nil {
		t.Fatalf("read the work directory: %v", err)
	}
	if len(entries) != 0 {
		names := make([]string, 0, len(entries))
		for _, entry := range entries {
			names = append(names, entry.Name())
		}
		t.Errorf("the work directory still holds %v", names)
	}
}
