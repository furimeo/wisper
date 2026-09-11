package build

import (
	"archive/zip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// Unpacking an upload is the one place in this package where a stranger's bytes decide
// what filenames get created, so most of these tests are about names that must be refused.

// zipEntry is one thing in a crafted archive.
type zipEntry struct {
	Name    string
	Body    string
	Symlink bool
}

func craftedZip(t *testing.T, entries ...zipEntry) (string, string) {
	t.Helper()

	path := filepath.Join(t.TempDir(), "crafted.zip")
	file, err := os.Create(path)
	if err != nil {
		t.Fatalf("create the archive: %v", err)
	}
	archive := zip.NewWriter(file)
	for _, entry := range entries {
		header := &zip.FileHeader{Name: entry.Name, Method: zip.Deflate}
		if entry.Symlink {
			header.SetMode(os.ModeSymlink | 0o777)
		} else {
			header.SetMode(0o644)
		}
		writer, err := archive.CreateHeader(header)
		if err != nil {
			t.Fatalf("add %s: %v", entry.Name, err)
		}
		if _, err := writer.Write([]byte(entry.Body)); err != nil {
			t.Fatalf("write %s: %v", entry.Name, err)
		}
	}
	if err := archive.Close(); err != nil {
		t.Fatalf("close the archive: %v", err)
	}
	if err := file.Close(); err != nil {
		t.Fatalf("close the file: %v", err)
	}

	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read the archive: %v", err)
	}
	digest := sha256.Sum256(raw)
	return path, hex.EncodeToString(digest[:])
}

func TestExtractRefusesAnEntryThatClimbsOutOfTheCheckout(t *testing.T) {
	root := t.TempDir()
	checkout := filepath.Join(root, "checkout")
	if err := os.Mkdir(checkout, 0o755); err != nil {
		t.Fatalf("create the checkout: %v", err)
	}

	for _, name := range []string{
		"../escaped.txt",
		"a/../../escaped.txt",
		`..\escaped.txt`,
		"/etc/cron.d/wisper",
	} {
		path, _ := craftedZip(t, zipEntry{Name: name, Body: "x"})
		_, err := extract(context.Background(), path, checkout)
		if err == nil && name != "/etc/cron.d/wisper" {
			t.Fatalf("the entry %q was unpacked", name)
		}
		if _, err := os.Stat(filepath.Join(root, "escaped.txt")); err == nil {
			t.Fatalf("the entry %q wrote outside the checkout", name)
		}
	}
}

func TestExtractRefusesASymlinkPointingOutOfTheCheckout(t *testing.T) {
	checkout := t.TempDir()

	for _, target := range []string{"/etc", "../../secrets", "/var/run/docker.sock"} {
		path, _ := craftedZip(t, zipEntry{Name: "link", Body: target, Symlink: true})
		if _, err := extract(context.Background(), path, checkout); err == nil {
			t.Fatalf("a link to %q was unpacked; the release would serve it", target)
		}
		_ = os.RemoveAll(filepath.Join(checkout, "link"))
	}
}

func TestExtractKeepsASymlinkInsideTheCheckout(t *testing.T) {
	requireSymlinkSwap(t)
	checkout := t.TempDir()

	path, _ := craftedZip(t,
		zipEntry{Name: "real/page.html", Body: "hello"},
		zipEntry{Name: "alias.html", Body: "real/page.html", Symlink: true},
	)
	if _, err := extract(context.Background(), path, checkout); err != nil {
		t.Fatalf("a link inside the tree must be kept: %v", err)
	}
	target, err := os.Readlink(filepath.Join(checkout, "alias.html"))
	if err != nil {
		t.Fatalf("read the link: %v", err)
	}
	if filepath.ToSlash(target) != "real/page.html" {
		t.Fatalf("the link points at %q", target)
	}
}

func TestExtractRefusesTheSameNameTwice(t *testing.T) {
	checkout := t.TempDir()

	path, _ := craftedZip(t,
		zipEntry{Name: "index.html", Body: "first"},
		zipEntry{Name: "index.html", Body: "second"},
	)
	if _, err := extract(context.Background(), path, checkout); err == nil {
		t.Fatal("a duplicated entry was accepted; the second copy silently wins")
	}
}

func TestFetchArchiveRefusesAnArchiveThatIsNotWhatWasUploaded(t *testing.T) {
	h := newHarness(t)
	path, digest := craftedZip(t, zipEntry{Name: "index.html", Body: "hello"})
	h.Uploads.files["session-1"] = path

	space, err := h.Builder.prepareWorkspace("42", "77", false)
	if err != nil {
		t.Fatalf("prepare the workspace: %v", err)
	}
	log := newBuildLog(h.Sink, "stream-1", "77", h.now)

	wrong := strings.Repeat("0", len(digest))
	_, err = h.Builder.fetchArchive(context.Background(), space,
		archiveSource("session-1", wrong), log)
	if err == nil {
		t.Fatal("an archive whose hash does not match the deployment was unpacked")
	}

	if _, err := h.Builder.fetchArchive(context.Background(), space,
		archiveSource("session-1", digest), log); err != nil {
		t.Fatalf("the matching archive must unpack: %v", err)
	}
	if got := readTree(t, space.Checkout); !reflect.DeepEqual(got, map[string]string{"index.html": "hello"}) {
		t.Fatalf("the checkout is not the archive: %v", got)
	}
}

func TestFetchArchiveRefusesAnArchiveWithNoDigest(t *testing.T) {
	h := newHarness(t)
	path, _ := craftedZip(t, zipEntry{Name: "index.html", Body: "hello"})
	h.Uploads.files["session-1"] = path

	space, err := h.Builder.prepareWorkspace("42", "77", false)
	if err != nil {
		t.Fatalf("prepare the workspace: %v", err)
	}
	log := newBuildLog(h.Sink, "stream-1", "77", h.now)

	if _, err := h.Builder.fetchArchive(context.Background(), space,
		archiveSource("session-1", ""), log); err == nil {
		t.Fatal("an archive with no recorded hash was accepted")
	}
}

func TestFetchArchiveEmptiesTheCheckoutFirst(t *testing.T) {
	h := newHarness(t)
	path, digest := craftedZip(t, zipEntry{Name: "index.html", Body: "new"})
	h.Uploads.files["session-1"] = path

	space, err := h.Builder.prepareWorkspace("42", "77", false)
	if err != nil {
		t.Fatalf("prepare the workspace: %v", err)
	}
	// What a previous build left. A zip carries no notion of which files it tracks, so
	// this must not survive into the release.
	writeTree(t, space.Checkout, map[string]string{"stale.html": "deleted last week"})

	log := newBuildLog(h.Sink, "stream-1", "77", h.now)
	if _, err := h.Builder.fetchArchive(context.Background(), space,
		archiveSource("session-1", digest), log); err != nil {
		t.Fatalf("fetch: %v", err)
	}
	if got := readTree(t, space.Checkout); !reflect.DeepEqual(got, map[string]string{"index.html": "new"}) {
		t.Fatalf("a previous build's files survived into the checkout: %v", got)
	}
}

func TestEntryPathNormalisesWithoutEscaping(t *testing.T) {
	root := filepath.Join(t.TempDir(), "checkout")

	ok := map[string]string{
		"index.html":     "index.html",
		"./index.html":   "index.html",
		"a/b/../c.txt":   filepath.Join("a", "c.txt"),
		"/absolute.txt":  "absolute.txt",
		`nested\win.txt`: filepath.Join("nested", "win.txt"),
	}
	for name, want := range ok {
		got, err := entryPath(root, name)
		if err != nil {
			t.Fatalf("%q was refused: %v", name, err)
		}
		if got != filepath.Join(root, want) {
			t.Fatalf("%q resolved to %q, wanted %q", name, got, filepath.Join(root, want))
		}
	}

	for _, name := range []string{"..", "../x", "a/../../x", ""} {
		if _, err := entryPath(root, name); err == nil {
			t.Fatalf("%q was accepted", name)
		}
	}
}
