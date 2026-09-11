package backup

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// The on-disk layout, and the check that stops an id from becoming a path.
//
// `volumes` is not this package's name to choose: runtime/storage.go owns the live tree and
// build/layout.go already repeats the same constants for the same reason. There is one state
// directory and several packages reading it, and a name built at a call site is how two of
// them end up disagreeing about where a customer's data is.
//
// The four names that are this package's own all sit beside `volumes` rather than inside it.
// A rollback copy left under volumes/<workload>/ would be mounted by nothing but would count
// against the directory quota the runtime applies to that workload, so a restore would
// silently halve the customer's disk allowance.
const (
	volumesDirectory = "volumes"

	// backupsDirectory is the root of DESTINATION_KIND_LOCAL. `local_prefix` is resolved
	// underneath it and never above it, which is the whole reason the field is documented as
	// relative.
	backupsDirectory = "backups"

	// workDirectory holds the staged archive of a run in flight and the journal that lets an
	// interrupted upload carry on. Both are named after the backup or restore id, so a
	// daemon that comes back up can find them.
	workDirectory = "backup-work"

	// rollbackDirectory is what a restore moved out of the way. Kept after the restore
	// succeeds, not deleted: a restore is the moment somebody is most likely to have chosen
	// the wrong restore point.
	rollbackDirectory = "backup-rollback"

	// restoreDirectory is where a dry run lands and where a real restore assembles the tree
	// before it swings it into place.
	restoreDirectory = "backup-restore"

	// stagingPrefix marks a tree that is still being filled. A crash leaves one behind; the
	// next restore of that subject removes it, and nothing ever reads one.
	stagingPrefix = ".staging-"

	// privateMode is every directory this package creates: root only. A backup archive is a
	// verbatim copy of a customer's data, so the tree holding it is no more readable than the
	// volume it came from.
	privateMode os.FileMode = 0o700

	// volumeMode is the mode a restored volume root gets, matching runtime/storage.go. World
	// writable, and safe only because privateMode above means nothing on the host can
	// traverse into it: the alternative is guessing the uid inside the container and giving a
	// customer an application that cannot write to its own data directory.
	volumeMode os.FileMode = 0o777

	// maxIdentifier is the longest id that may become a path segment. Every id the panel
	// sends - a bigint, a slug - fits well inside it.
	maxIdentifier = 64
)

// checkIdentifier refuses anything that could climb out of the state tree.
//
// Narrow on purpose: letters, digits, dash, underscore and dot, not empty, not starting with
// a dot, at most 64 characters. A value that does not fit is a bug or an attack, and the
// answer to both is to refuse rather than to sanitise - sanitising maps two different ids
// onto one directory, which loses a customer's backup instead of rejecting a request nobody
// sent.
func checkIdentifier(kind, value string) error {
	if value == "" {
		return fmt.Errorf("backup: the %s is empty", kind)
	}
	if len(value) > maxIdentifier {
		return fmt.Errorf("backup: the %s %q is longer than %d characters", kind, value, maxIdentifier)
	}
	if strings.HasPrefix(value, ".") {
		return fmt.Errorf("backup: the %s %q starts with a dot, which would name a directory "+
			"relative to something other than itself", kind, value)
	}
	for _, character := range value {
		switch {
		case character >= 'a' && character <= 'z',
			character >= 'A' && character <= 'Z',
			character >= '0' && character <= '9',
			character == '-', character == '_', character == '.':
		default:
			return fmt.Errorf("backup: the %s %q contains %q, and only letters, digits, '-', "+
				"'_' and '.' may reach a filesystem path", kind, value, string(character))
		}
	}
	return nil
}

// checkKey applies checkIdentifier to every segment of an object key.
//
// A key is not a filesystem path at an S3 destination, and it is exactly one at a local
// destination. Checking both the same way means the local case cannot be talked out of the
// backups directory by a prefix of "../../etc", and the S3 case cannot be given a key with
// an empty segment that half the object stores normalise away and half do not.
func checkKey(key string) error {
	if key == "" {
		return fmt.Errorf("backup: the object key is empty")
	}
	for _, segment := range strings.Split(key, "/") {
		if err := checkIdentifier("object key segment", segment); err != nil {
			return err
		}
	}
	return nil
}

// volumePath is where one volume of one workload lives, as runtime/storage.go writes it.
func volumePath(stateDir, workloadID, volumeID string) (string, error) {
	if err := checkIdentifier("workload id", workloadID); err != nil {
		return "", err
	}
	if err := checkIdentifier("volume id", volumeID); err != nil {
		return "", err
	}
	return filepath.Join(stateDir, volumesDirectory, workloadID, volumeID), nil
}

// workPath is a file in the staging directory, named after the run that owns it.
func workPath(stateDir, runID, suffix string) (string, error) {
	if err := checkIdentifier("run id", runID); err != nil {
		return "", err
	}
	return filepath.Join(stateDir, workDirectory, runID+suffix), nil
}

// localObjectPath resolves an object key under the node's own backup directory.
func localObjectPath(stateDir, key string) (string, error) {
	if err := checkKey(key); err != nil {
		return "", err
	}
	return filepath.Join(stateDir, backupsDirectory, filepath.FromSlash(key)), nil
}

// localRoot is where DESTINATION_KIND_LOCAL keeps everything.
func localRoot(stateDir string) string {
	return filepath.Join(stateDir, backupsDirectory)
}

// rollbackPath is where a restore puts what it moved out of the way.
func rollbackPath(stateDir, workloadID, subjectID, restoreID string) (string, error) {
	return sideTree(stateDir, rollbackDirectory, workloadID, subjectID, restoreID)
}

// dryRunPath is where a rehearsal restores to, beside the live data rather than over it.
func dryRunPath(stateDir, workloadID, subjectID, restoreID string) (string, error) {
	return sideTree(stateDir, restoreDirectory, workloadID, subjectID, restoreID)
}

// stagingPath is where a real restore assembles the tree before the swap.
//
// Under the same parent as the dry-run tree, so the final move is a rename within one
// filesystem. A rename across devices fails, and discovering that after the live directory
// has already been swung aside is the worst moment to discover it.
func stagingPath(stateDir, workloadID, subjectID, restoreID string) (string, error) {
	sibling, err := sideTree(stateDir, restoreDirectory, workloadID, subjectID, restoreID)
	if err != nil {
		return "", err
	}
	// Built from the validated sibling rather than joined by hand, so the dot prefix is added
	// after every segment has been through checkIdentifier rather than smuggled past it.
	return filepath.Join(filepath.Dir(sibling), stagingPrefix+restoreID), nil
}

// sideTree is the shared shape of the three trees that sit beside `volumes`.
//
// A workload id of "" is allowed and becomes "detached": a managed database has no workload,
// and a rollback dump of one still has to be filed somewhere a human can find it.
func sideTree(stateDir, root, workloadID, subjectID, runID string) (string, error) {
	owner := workloadID
	if owner == "" {
		owner = "detached"
	}
	if err := checkIdentifier("workload id", owner); err != nil {
		return "", err
	}
	if err := checkIdentifier("subject id", subjectID); err != nil {
		return "", err
	}
	if err := checkIdentifier("run id", runID); err != nil {
		return "", err
	}
	return filepath.Join(stateDir, root, owner, subjectID, runID), nil
}

// insideTree reports whether candidate resolves to root itself or to something under it.
//
// Lexical rather than symlink-resolving, because it is applied before anything is created.
// The tar extractor additionally refuses to follow a link it did not make, which is the
// other half of the same defence.
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

// makeDirectory creates a directory and every parent, root-only all the way down.
func makeDirectory(path string) error {
	if err := os.MkdirAll(path, privateMode); err != nil {
		return fmt.Errorf("backup: create %s: %w", path, err)
	}
	return nil
}
