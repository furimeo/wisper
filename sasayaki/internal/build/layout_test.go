package build

import (
	"path/filepath"
	"strings"
	"testing"
)

// The check that keeps an id from becoming a path. Everything in this package that reaches
// the filesystem goes through it, so it is worth more tests than its size suggests.

func TestCheckIdentifierAcceptsWhatThePanelSends(t *testing.T) {
	for _, value := range []string{"42", "1234567890", "my-site", "my_site", "v1.2.3", "a"} {
		if err := checkIdentifier("id", value); err != nil {
			t.Fatalf("%q was refused: %v", value, err)
		}
	}
}

func TestCheckIdentifierRefusesAnythingThatCouldBecomeAPath(t *testing.T) {
	refused := []string{
		"",
		".",
		"..",
		"../etc",
		".hidden",
		"a/b",
		`a\b`,
		"a b",
		"a;rm -rf /",
		"a\x00b",
		"a$b",
		strings.Repeat("x", maxIdentifier+1),
	}
	for _, value := range refused {
		if err := checkIdentifier("id", value); err == nil {
			t.Fatalf("%q was accepted", value)
		}
	}
}

func TestResolveInsideStaysInsideTheCheckout(t *testing.T) {
	root := filepath.Join(t.TempDir(), "checkout")

	if got, err := resolveInside(root, "", "subdirectory"); err != nil || got != root {
		t.Fatalf("an empty subdirectory must be the root: %q %v", got, err)
	}
	if got, err := resolveInside(root, "  ", "subdirectory"); err != nil || got != root {
		t.Fatalf("a blank subdirectory must be the root: %q %v", got, err)
	}
	if got, err := resolveInside(root, "apps/web", "subdirectory"); err != nil {
		t.Fatalf("a monorepo subdirectory was refused: %v", err)
	} else if got != filepath.Join(root, "apps", "web") {
		t.Fatalf("apps/web resolved to %q", got)
	}
	if got, err := resolveInside(root, "apps/../web", "subdirectory"); err != nil {
		t.Fatalf("a path that cleans to something inside was refused: %v", err)
	} else if got != filepath.Join(root, "web") {
		t.Fatalf("apps/../web resolved to %q", got)
	}

	for _, escape := range []string{"..", "../..", "apps/../../etc", "/etc"} {
		if _, err := resolveInside(root, escape, "subdirectory"); err == nil {
			t.Fatalf("%q was accepted", escape)
		}
	}
}

func TestInsideTreeIsNotFooledByAPrefix(t *testing.T) {
	root := filepath.Join(string(filepath.Separator), "var", "lib", "wisper", "sites", "42")

	if !insideTree(root, root) {
		t.Fatal("the root is not inside itself")
	}
	if !insideTree(root, filepath.Join(root, "releases", "7")) {
		t.Fatal("a child is not inside the root")
	}
	// The classic: a sibling whose name starts with the root's.
	if insideTree(root, root+"-evil") {
		t.Fatal("a sibling with a shared prefix was treated as being inside")
	}
	if insideTree(root, filepath.Join(root, "..", "43")) {
		t.Fatal("a path climbing out was treated as being inside")
	}
}

func TestContainerPathIsAlwaysAUnixPath(t *testing.T) {
	workspace := filepath.Join(string(filepath.Separator), "var", "lib", "wisper", "builds", "42", "77")

	if got := containerPath(workspace, workspace); got != workMount {
		t.Fatalf("the workspace root is %q inside the container", got)
	}
	checkout := filepath.Join(workspace, checkoutDirectory, "apps", "web")
	got := containerPath(workspace, checkout)
	if got != workMount+"/source/apps/web" {
		t.Fatalf("a nested path is %q inside the container", got)
	}
	if strings.Contains(got, `\`) {
		t.Fatalf("a Windows separator reached a container path: %q", got)
	}
}

func TestLayoutPathsAreWhatTheEdgeAndTheRuntimeExpect(t *testing.T) {
	root := filepath.Join(string(filepath.Separator), "var", "lib", "wisper")

	site, err := siteRoot(root, "42")
	if err != nil {
		t.Fatalf("site root: %v", err)
	}
	if site != filepath.Join(root, "sites", "42") {
		t.Fatalf("the site root moved to %q; edge/options.go reads the old one", site)
	}

	release, err := releaseDir(root, "42", "77")
	if err != nil {
		t.Fatalf("release dir: %v", err)
	}
	if release != filepath.Join(root, "sites", "42", "releases", "77") {
		t.Fatalf("the release directory moved to %q", release)
	}

	link, err := currentLink(root, "42")
	if err != nil {
		t.Fatalf("current link: %v", err)
	}
	if link != filepath.Join(root, "sites", "42", "current") {
		t.Fatalf("the current link moved to %q; runtime/storage.go mounts the old one", link)
	}

	space, err := workspaceDir(root, "42", "77")
	if err != nil {
		t.Fatalf("workspace dir: %v", err)
	}
	if space != filepath.Join(root, "builds", "42", "77") {
		t.Fatalf("the workspace moved to %q", space)
	}
}
