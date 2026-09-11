package state

import "errors"

// The conditions a caller has to be able to branch on.
//
// Five sentinels rather than one per table: what a caller does about "this build id is
// already in the table" does not depend on it being a build, and errors.Is with a wrapped
// message keeps the identifier in the text where a human reading a log needs it.
var (
	// ErrNotFound is returned for a row that is not there. It is wrapped with what was
	// looked up: fmt.Errorf("state: build %s: %w", id, ErrNotFound).
	ErrNotFound = errors.New("no such record")

	// ErrAlreadyExists is returned by the Begin* methods when a run with that id has
	// already been started. It is how a retried command becomes idempotent: the panel
	// resends a backup or a build after a dropped stream, and the second attempt must
	// find the first rather than start a duplicate (backup.proto, RunBackup.backup_id).
	ErrAlreadyExists = errors.New("record already exists")

	// ErrNoSpec means the panel has never sent this node a NodeSpec. Distinct from
	// ErrNotFound because it is the normal state of a node that has just been enrolled,
	// not a lookup that failed: the reconcile loop waits, and removes nothing.
	ErrNoSpec = errors.New("no spec has been received")

	// ErrSupersededGeneration means the spec offered is older than the one on disk.
	// Routine on a flapping tunnel, where a retried frame arrives after a newer one, and
	// answered with SpecApplied{accepted: false} rather than treated as a failure
	// (workload.proto, NodeSpec.generation).
	ErrSupersededGeneration = errors.New("generation is below the stored generation")

	// ErrUploadConflict means a session id was reopened describing a different file.
	// Either the browser reused an id or two uploads collided; both have to fail loudly,
	// because continuing would interleave two files' chunks into one.
	ErrUploadConflict = errors.New("upload session was redeclared with different parameters")
)
