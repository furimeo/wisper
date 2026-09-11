package build

import (
	"os"
	"path/filepath"
	"testing"
)

// .dockerignore matching, which decides how much of a repository crosses the socket.

func parsed(t *testing.T, contents string) *ignoreList {
	t.Helper()
	root := t.TempDir()
	if contents != "" {
		if err := os.WriteFile(filepath.Join(root, ".dockerignore"), []byte(contents), 0o644); err != nil {
			t.Fatalf("write the .dockerignore: %v", err)
		}
	}
	list, err := readIgnoreList(root)
	if err != nil {
		t.Fatalf("read the .dockerignore: %v", err)
	}
	return list
}

func TestNoDockerignoreExcludesNothing(t *testing.T) {
	list := parsed(t, "")
	if list.excludes("node_modules", true) || list.excludes("main.go", false) {
		t.Fatal("a context with no .dockerignore excluded something")
	}
	// The nil list is what a caller with nothing parsed passes.
	var empty *ignoreList
	if empty.excludes("anything", false) {
		t.Fatal("the nil list excluded something")
	}
}

func TestDockerignoreMatchesNamesSegmentBySegment(t *testing.T) {
	list := parsed(t, "node_modules\n*.log\nbuild/output\n")

	cases := map[string]bool{
		"node_modules":  true,
		"debug.log":     true,
		"logs/a.log":    false, // *.log matches one segment, and this is two
		"build/output":  true,
		"build/keep":    false,
		"main.go":       false,
		"node_modules2": false,
	}
	for name, want := range cases {
		if got := list.excludes(name, false); got != want {
			t.Fatalf("%q was excluded=%v, wanted %v", name, got, want)
		}
	}
}

func TestDockerignoreDoubleStarSpansAnyDepth(t *testing.T) {
	list := parsed(t, "**/*.tmp\ndocs/**\n")

	cases := map[string]bool{
		"a.tmp":        true,
		"x/y/z.tmp":    true,
		"docs/readme":  true,
		"docs/a/b/c":   true,
		"docsy/readme": false,
		"src/main.go":  false,
	}
	for name, want := range cases {
		if got := list.excludes(name, false); got != want {
			t.Fatalf("%q was excluded=%v, wanted %v", name, got, want)
		}
	}
}

func TestDockerignoreLastMatchWins(t *testing.T) {
	list := parsed(t, "*.log\n!keep.log\n")

	if !list.excludes("debug.log", false) {
		t.Fatal("*.log did not exclude debug.log")
	}
	if list.excludes("keep.log", false) {
		t.Fatal("the exception did not put keep.log back")
	}
}

// An excluded directory holding an exception has to be walked into, or the file the
// customer asked to keep never reaches the engine.
func TestDockerignoreDescendsIntoADirectoryHoldingAnException(t *testing.T) {
	list := parsed(t, "assets\n!assets/logo.svg\n")

	if list.excludes("assets", true) {
		t.Fatal("the walk would skip assets and lose the exception inside it")
	}
	if !list.excludes("assets/other.png", false) {
		t.Fatal("a file inside an excluded directory was not excluded")
	}
	if list.excludes("assets/logo.svg", false) {
		t.Fatal("the exception did not apply")
	}
}

func TestDockerignoreIgnoresCommentsAndBlankLines(t *testing.T) {
	list := parsed(t, "# a comment\n\n   \nnode_modules\n#!not-an-exception\n")

	if !list.excludes("node_modules", true) {
		t.Fatal("the one real pattern was lost")
	}
	if list.excludes("#", false) || list.excludes("a comment", false) {
		t.Fatal("a comment became a pattern")
	}
}

func TestDockerignoreTreatsLeadingAndTrailingSlashesAsNoise(t *testing.T) {
	list := parsed(t, "/build/\n./dist\n")

	if !list.excludes("build", true) {
		t.Fatal("/build/ did not exclude build")
	}
	if !list.excludes("dist", true) {
		t.Fatal("./dist did not exclude dist")
	}
}

func TestDockerignoreAcceptsWindowsSeparators(t *testing.T) {
	list := parsed(t, `build\output`+"\n")

	if !list.excludes("build/output", true) {
		t.Fatal("a pattern written with backslashes did not match")
	}
}
