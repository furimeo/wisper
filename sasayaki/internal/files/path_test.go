package files

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

func TestCleanPathAcceptsWhatAFileManagerSends(t *testing.T) {
	cases := map[string]string{
		"":                     "",
		"/":                    "",
		".":                    "",
		"notes.txt":            "notes.txt",
		"/notes.txt":           "notes.txt",
		"a/b/c.txt":            "a/b/c.txt",
		"a//b///c.txt":         "a/b/c.txt",
		"a/./b.txt":            "a/b.txt",
		"trailing/":            "trailing",
		"a file with spaces":   "a file with spaces",
		"weird-but-legal-#$%&": "weird-but-legal-#$%&",
	}
	for raw, want := range cases {
		got, failed := cleanPath(raw)
		if failed != nil {
			t.Errorf("cleanPath(%q) was refused: %s", raw, failed.detail)
			continue
		}
		if got != want {
			t.Errorf("cleanPath(%q) = %q, want %q", raw, got, want)
		}
	}
}

func TestCleanPathRefusesEveryShapeOfTraversal(t *testing.T) {
	refused := []string{
		"..",
		"../etc/passwd",
		"a/../../etc/passwd",
		"a/..",
		"legitimate/../../../../root/.ssh/authorized_keys",
		"with\x00nul",
		strings.Repeat("a/", 3000),
	}
	for _, raw := range refused {
		if _, failed := cleanPath(raw); failed == nil {
			t.Errorf("cleanPath(%q) was accepted, and every one of these is a way out of a root", raw)
		} else if failed.code != wisperpb.FileErrorCode_FILE_ERROR_CODE_PATH_ESCAPES_ROOT {
			t.Errorf("cleanPath(%q) was refused with %s, and the panel records only "+
				"PATH_ESCAPES_ROOT in the audit log", raw, failed.code)
		}
	}
}

// A path that is absolute on the node is not treated as an escape - it is one element too
// many at the front, and stripping it leaves something still inside the root. What matters
// is that it cannot name a host path, which the next test proves against a real tree.
func TestCleanPathTreatsALeadingSlashAsTheRoot(t *testing.T) {
	got, failed := cleanPath("/etc/passwd")
	if failed != nil {
		t.Fatalf("refused: %s", failed.detail)
	}
	if got != "etc/passwd" {
		t.Fatalf("cleanPath(\"/etc/passwd\") = %q, want %q inside the root", got, "etc/passwd")
	}
}

func TestCleanPathRefusesAnOverlongName(t *testing.T) {
	if _, failed := cleanPath(strings.Repeat("n", maxNameLength+1)); failed == nil {
		t.Fatal("a name longer than NAME_MAX was accepted")
	}
}

func TestParentPathSplitsAtTheLastSlash(t *testing.T) {
	cases := []struct{ in, parent, name string }{
		{"a/b/c.txt", "a/b", "c.txt"},
		{"c.txt", "", "c.txt"},
		{"", "", ""},
	}
	for _, want := range cases {
		parent, name := parentPath(want.in)
		if parent != want.parent || name != want.name {
			t.Errorf("parentPath(%q) = (%q, %q), want (%q, %q)",
				want.in, parent, name, want.parent, want.name)
		}
	}
}

func TestInsideSlashTreeKnowsAPrefixFromANeighbour(t *testing.T) {
	if !insideSlashTree("a/b", "a/b/c") {
		t.Error("a/b/c is inside a/b")
	}
	if !insideSlashTree("a/b", "a/b") {
		t.Error("a path is inside itself, which is what stops a move into itself")
	}
	if insideSlashTree("a/b", "a/bc") {
		t.Error("a/bc is a neighbour of a/b, not a child of it")
	}
	if !insideSlashTree("", "anything") {
		t.Error("everything is inside the root")
	}
}

// The one place this package depends on a string in the standard library: os.Root reports
// an escape through an error nobody exported, and classify matches its text so the panel
// records PATH_ESCAPES_ROOT rather than a generic I/O failure.
//
// Pinned here so a Go release that renames it fails this test rather than silently
// downgrading every traversal in the audit log to IO_ERROR.
func TestOsRootStillReportsAnEscapeWithTheMessageWeMatch(t *testing.T) {
	outer := t.TempDir()
	inner := filepath.Join(outer, "inside")
	if err := os.Mkdir(inner, 0o755); err != nil {
		t.Fatalf("create %s: %v", inner, err)
	}
	root, err := os.OpenRoot(inner)
	if err != nil {
		t.Fatalf("open %s as a root: %v", inner, err)
	}
	defer root.Close()

	_, err = root.Open(filepath.Join("..", "outside.txt"))
	if err == nil {
		t.Fatal("os.Root opened a path above its own directory")
	}
	if !escapedRoot(err) {
		t.Fatalf("os.Root reported an escape as %q, and classify matches %q; "+
			"update pathEscapesMessage", err, pathEscapesMessage)
	}
	if codeFor(err) != wisperpb.FileErrorCode_FILE_ERROR_CODE_PATH_ESCAPES_ROOT {
		t.Fatalf("an escape classified as %s", codeFor(err))
	}
}
