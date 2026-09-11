package build

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// The on-disk layout, and the check that stops an id from becoming a path.
//
// It matches what runtime/storage.go and edge/options.go already agree on, because there
// is one tree and three packages reading it: the builder writes releases, the edge serves
// through `current`, and the runtime mounts `current` read-only into an app that wants to
// post-process what a build produced. Changing a name here means moving customer data, so
// the names are constants in all three places rather than a string built at a call site.
const (
	sitesDirectory    = "sites"
	releasesDirectory = "releases"
	// currentRelease is the symlink swapped by Releases.Publish. It is a relative link, so
	// the whole state tree can be moved or bind-mounted somewhere else and still resolve.
	currentRelease = "current"

	// buildsDirectory holds one workspace per build: the checkout, and whatever the
	// package manager left behind that the next build can reuse.
	buildsDirectory = "builds"
	// checkoutDirectory is the source tree inside a workspace. A subdirectory rather than
	// the workspace root so that a future sibling - a log file, a cache the plan asks for -
	// cannot collide with a file the repository happens to contain.
	checkoutDirectory = "source"

	// stagingPrefix marks a release directory that is still being filled. A visitor never
	// sees one, because `current` cannot point at a name that starts with a dot and
	// pruning skips them; a crash leaves one behind and the next build of that workload
	// removes it.
	stagingPrefix = ".staging-"

	// directoryMode is every directory this package creates. Root only: the release tree
	// is read by the edge, which runs in this process, and by a container that gets it
	// bind-mounted. Nothing else on the host has any business traversing it.
	directoryMode os.FileMode = 0o755

	// maxIdentifier is the longest id that may become a path segment. Every id the panel
	// sends - a bigint, a slug - fits well inside it.
	maxIdentifier = 64
)

// checkIdentifier refuses anything that could climb out of the state tree.
//
// Narrow on purpose: letters, digits, dash, underscore and dot, not empty, not starting
// with a dot, at most 64 characters. A value that does not fit is a bug or an attack, and
// the answer to both is to refuse rather than to sanitise - sanitising maps two different
// ids onto one directory, which loses a customer's release instead of rejecting a
// request nobody sent.
func checkIdentifier(kind, value string) error {
	if value == "" {
		return fmt.Errorf("build: the %s is empty", kind)
	}
	if len(value) > maxIdentifier {
		return fmt.Errorf("build: the %s %q is longer than %d characters", kind, value, maxIdentifier)
	}
	if strings.HasPrefix(value, ".") {
		return fmt.Errorf("build: the %s %q starts with a dot, which would name a directory "+
			"relative to something other than itself", kind, value)
	}
	for _, character := range value {
		switch {
		case character >= 'a' && character <= 'z',
			character >= 'A' && character <= 'Z',
			character >= '0' && character <= '9',
			character == '-', character == '_', character == '.':
		default:
			return fmt.Errorf("build: the %s %q contains %q, and only letters, digits, '-', "+
				"'_' and '.' may reach a filesystem path", kind, value, string(character))
		}
	}
	return nil
}

// siteRoot is everything belonging to one static site: its releases and its symlink.
func siteRoot(stateDir, workloadID string) (string, error) {
	if err := checkIdentifier("workload id", workloadID); err != nil {
		return "", err
	}
	return filepath.Join(stateDir, sitesDirectory, workloadID), nil
}

// releaseRoot is where a site's releases sit beside each other.
func releaseRoot(stateDir, workloadID string) (string, error) {
	site, err := siteRoot(stateDir, workloadID)
	if err != nil {
		return "", err
	}
	return filepath.Join(site, releasesDirectory), nil
}

// releaseDir is one finished release.
func releaseDir(stateDir, workloadID, releaseID string) (string, error) {
	root, err := releaseRoot(stateDir, workloadID)
	if err != nil {
		return "", err
	}
	if err := checkIdentifier("release id", releaseID); err != nil {
		return "", err
	}
	return filepath.Join(root, releaseID), nil
}

// currentLink is the symlink the edge serves through.
func currentLink(stateDir, workloadID string) (string, error) {
	site, err := siteRoot(stateDir, workloadID)
	if err != nil {
		return "", err
	}
	return filepath.Join(site, currentRelease), nil
}

// workspaceRoot is where one workload's build workspaces accumulate until retention
// sweeps them.
func workspaceRoot(stateDir, workloadID string) (string, error) {
	if err := checkIdentifier("workload id", workloadID); err != nil {
		return "", err
	}
	return filepath.Join(stateDir, buildsDirectory, workloadID), nil
}

// workspaceDir is one build's workspace.
func workspaceDir(stateDir, workloadID, buildID string) (string, error) {
	root, err := workspaceRoot(stateDir, workloadID)
	if err != nil {
		return "", err
	}
	if err := checkIdentifier("build id", buildID); err != nil {
		return "", err
	}
	return filepath.Join(root, buildID), nil
}

// insideTree reports whether candidate resolves to root itself or to something under it.
//
// Used wherever a name from outside - a plan's output directory, an entry in a zip - is
// joined onto a directory this package owns. Lexical rather than symlink-resolving,
// because it is applied to the path before anything is created; the extractor additionally
// refuses to follow a link it did not make (fetcharchive.go).
func insideTree(root, candidate string) bool {
	relative, err := filepath.Rel(root, candidate)
	if err != nil {
		return false
	}
	if relative == "." {
		return true
	}
	return relative != ".." && !strings.HasPrefix(relative, ".."+string(filepath.Separator))
}

// resolveInside joins a caller-supplied relative path onto a root and refuses anything
// that leaves it.
//
// An empty relative path is the root, which is what an empty `subdirectory` and an empty
// `build_context` both mean. An absolute one is refused rather than being made relative:
// the panel never sends a filesystem path, so one that arrived is a mistake worth seeing.
func resolveInside(root, relative, what string) (string, error) {
	cleaned := strings.TrimSpace(relative)
	if cleaned == "" {
		return root, nil
	}
	cleaned = filepath.FromSlash(cleaned)
	if filepath.IsAbs(cleaned) || strings.HasPrefix(cleaned, string(filepath.Separator)) {
		return "", fmt.Errorf("build: the %s %q is an absolute path, and only a path relative "+
			"to the checkout can be built", what, relative)
	}
	joined := filepath.Join(root, cleaned)
	if !insideTree(root, joined) {
		return "", fmt.Errorf("build: the %s %q points outside the checkout", what, relative)
	}
	return joined, nil
}

// containerPath turns a host path under the workspace into the path the build container
// sees. The workspace is bind-mounted at workMount, so the two differ only by prefix, and
// the container always sees forward slashes even when the tests run on Windows.
func containerPath(workspace, host string) string {
	relative, err := filepath.Rel(workspace, host)
	if err != nil || relative == "." {
		return workMount
	}
	return workMount + "/" + filepath.ToSlash(relative)
}
