package files

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Where a file root actually is on the node, and the check that stops an id from becoming
// a path.
//
// The names below are the same ones internal/runtime and internal/build hold as their own
// constants, because there is one tree and three packages reading it: the runtime creates
// and mounts a volume, the builder fills a site's releases, and this package is what a
// customer browses. Changing a name here means moving customer data (design section 11.1),
// so it is a constant in each of the three rather than a string built at a call site.
//
//	<state>/volumes/<workload-id>/<volume-id>   a customer's disk
//	<state>/sites/<workload-id>/releases        what the last few builds produced, read-only
//	<state>/uploads/staging                     archives the panel pushes before a build
//	<state>/uploads/parts/<session-id>          chunks in flight, inside no root at all
const (
	volumesDirectory  = "volumes"
	sitesDirectory    = "sites"
	releasesDirectory = "releases"

	uploadsDirectory = "uploads"
	stagingDirectory = "staging"
	partsDirectory   = "parts"

	// nodeDirectoryMode is every directory this package creates for the node's own use:
	// root only. The staging root and the parts directory hold other customers' archives
	// while they are in flight, so nothing else on the host may traverse them.
	nodeDirectoryMode os.FileMode = 0o700

	// newDirectoryMode is what "new folder" creates inside a customer's root. Readable and
	// traversable by the container's user, which is the whole point of the directory.
	newDirectoryMode os.FileMode = 0o755

	// newFileMode is what an upload lands as. Not executable: a file that arrived over the
	// network and became runnable without anybody saying so is a surprise nobody wants,
	// and the file manager has a chmod for the cases where it is meant.
	newFileMode os.FileMode = 0o644

	// maxIdentifier is the longest id that may become a path segment.
	maxIdentifier = 64
)

// checkIdentifier refuses anything that could climb out of the state tree.
//
// Narrow on purpose: letters, digits, dash, underscore and dot, not empty, not starting
// with a dot, at most 64 characters. Applied to every id that reaches a path - a workload,
// a volume, an upload session minted by a browser - because sanitising instead of refusing
// maps two different ids onto one directory, and that loses a customer's file rather than
// rejecting a request nobody legitimately sent.
func checkIdentifier(kind, value string) error {
	if value == "" {
		return fmt.Errorf("files: the %s is empty", kind)
	}
	if len(value) > maxIdentifier {
		return fmt.Errorf("files: the %s %q is longer than %d characters", kind, value, maxIdentifier)
	}
	if strings.HasPrefix(value, ".") {
		return fmt.Errorf("files: the %s %q starts with a dot, which would name a directory "+
			"relative to something other than itself", kind, value)
	}
	for _, character := range value {
		switch {
		case character >= 'a' && character <= 'z',
			character >= 'A' && character <= 'Z',
			character >= '0' && character <= '9',
			character == '-', character == '_', character == '.':
		default:
			return fmt.Errorf("files: the %s %q contains %q, and only letters, digits, '-', "+
				"'_' and '.' may reach a filesystem path", kind, value, string(character))
		}
	}
	return nil
}

// volumeDirectory is one volume of one workload: what the customer thinks of as their disk.
func volumeDirectory(stateDir, workloadID, volumeID string) (string, error) {
	if err := checkIdentifier("workload id", workloadID); err != nil {
		return "", err
	}
	if err := checkIdentifier("volume id", volumeID); err != nil {
		return "", err
	}
	return filepath.Join(stateDir, volumesDirectory, workloadID, volumeID), nil
}

// siteReleasesDirectory is a static site's releases tree.
//
// The releases rather than the `current` symlink: a customer looking at a site root wants
// to see what each build produced, and `current` is a link, which this package refuses to
// traverse. It is never writable, so nothing here creates it - the builder does.
func siteReleasesDirectory(stateDir, workloadID string) (string, error) {
	if err := checkIdentifier("site workload id", workloadID); err != nil {
		return "", err
	}
	return filepath.Join(stateDir, sitesDirectory, workloadID, releasesDirectory), nil
}

// stagingRoot is the node's own root, where the panel pushes an archive before it asks for
// a build. Not a customer's directory, which is why it is the one root this package
// creates on demand.
func stagingRoot(stateDir string) string {
	return filepath.Join(stateDir, uploadsDirectory, stagingDirectory)
}

// partsRoot holds the bytes of uploads in flight, and is deliberately inside no file root:
// a half-uploaded file must not appear in a listing, must not be served by the edge and
// must not be swept into a backup.
func partsRoot(stateDir string) string {
	return filepath.Join(stateDir, uploadsDirectory, partsDirectory)
}

// partsRelativePath is what goes in state.UploadSession.StagingPath: the parts file's path
// relative to the state directory, so a restarted daemon and the sweeper can both find the
// bytes without re-deriving a naming scheme that may since have changed.
func partsRelativePath(sessionID string) string {
	return filepath.ToSlash(filepath.Join(uploadsDirectory, partsDirectory, sessionID))
}
