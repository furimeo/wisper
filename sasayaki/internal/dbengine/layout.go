package dbengine

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Where a database server keeps its things on the node.
//
//	<state>/databases/<instance-id>/data       the server's own data directory
//	<state>/databases/<instance-id>/transfer   dumps on their way in or out
//
// The instance id is DatabaseEngineSpec.data_volume_id, which is the panel's row id for the
// server. An id and never a path: the panel sends no filesystem path anywhere in this
// contract, and the reason is the same one as for volumes (docs/contracts/node-spec.md
// section 3.5) - a path that arrives over the network and reaches a filesystem call is the
// bug class this platform exists to avoid. Rebuilding a node keeps customer data because the
// id does not change when anything is renamed.
//
// The transfer directory is a second bind mount and it exists so that a dump never passes
// through this process's memory. `pg_dump` writes a file the container can see, the host
// reads that file and streams it to whoever asked; a restore goes the other way. The
// alternative - piping a dump through the exec connection - would hold a customer's whole
// database in a byte slice, and the customers whose databases are worth backing up are
// exactly the ones for whom that is a machine-sized allocation.

const (
	// databasesDirectory is the subtree of the node's state root this package owns.
	databasesDirectory = "databases"

	// dataSubdirectory holds the server's data files. A subdirectory rather than the
	// instance directory itself, so the transfer area can sit beside it without the server
	// ever seeing it as part of its own data.
	dataSubdirectory = "data"

	// transferSubdirectory holds dumps in flight.
	transferSubdirectory = "transfer"

	// transferMount is where the transfer directory appears inside the container. Fixed, so
	// a dump command reads the same in a log wherever it ran.
	transferMount = "/wisper/transfer"

	// privateMode is every directory on the way down: root only. It is the real access
	// control on customer data, and it is what makes it safe for the two directories below
	// it to be world-writable.
	privateMode os.FileMode = 0o700

	// serverMode is the mode of the two directories the server itself writes into.
	//
	// World-writable, deliberately, and safe only because of privateMode above: nothing on
	// the host can traverse into them without already being root. The alternative is
	// guessing which uid is inside the container - 999 for the official PostgreSQL image,
	// something else for MySQL, something else again under user-namespace remapping - and
	// guessing wrong gives a server that will not initialise with an error blaming its own
	// data directory.
	serverMode os.FileMode = 0o777
)

// instancePaths are the two directories one server owns.
type instancePaths struct {
	// Data is the host path of the server's data directory.
	Data string
	// Transfer is the host path of the dump staging area.
	Transfer string
}

// pathsFor resolves one instance's directories under the node's state root.
//
// The id is checked before it is joined onto anything. It is a uuid everywhere the panel
// produces one, so the rule is narrow on purpose: letters, digits, dashes and underscores,
// no dot segment, nothing that could climb out of the tree.
func pathsFor(stateDir, instanceID string) (instancePaths, error) {
	if err := checkInstanceID(instanceID); err != nil {
		return instancePaths{}, err
	}
	root := filepath.Join(stateDir, databasesDirectory, instanceID)
	return instancePaths{
		Data:     filepath.Join(root, dataSubdirectory),
		Transfer: filepath.Join(root, transferSubdirectory),
	}, nil
}

// ensure creates both directories if they are not there.
//
// Made here rather than left to the engine to create as a missing bind source: an engine
// that creates it makes a root-owned directory with a mode of its own choosing, and on some
// versions creates a regular file instead - which turns a database's data directory into an
// empty file mounted over the place its data was going to go.
func (p instancePaths) ensure() error {
	for _, directory := range []string{p.Data, p.Transfer} {
		if err := os.MkdirAll(filepath.Dir(directory), privateMode); err != nil {
			return fmt.Errorf("dbengine: create %s: %w", filepath.Dir(directory), err)
		}
		if err := os.Mkdir(directory, serverMode); err != nil && !os.IsExist(err) {
			return fmt.Errorf("dbengine: create %s: %w", directory, err)
		}
		info, err := os.Stat(directory)
		if err != nil {
			return fmt.Errorf("dbengine: look at %s: %w", directory, err)
		}
		if !info.IsDir() {
			return fmt.Errorf("dbengine: %s is not a directory, so it cannot back a database server", directory)
		}
		// Mkdir's mode is masked by the process umask, which the daemon inherits from
		// systemd. Without this the directory comes out 0755 and a server that does not run
		// as root cannot write to it.
		if err := os.Chmod(directory, serverMode); err != nil {
			return fmt.Errorf("dbengine: set the mode of %s: %w", directory, err)
		}
	}
	return nil
}

// checkInstanceID refuses an id that could escape the state directory or confuse the engine.
func checkInstanceID(value string) error {
	if value == "" {
		return fmt.Errorf("dbengine: the engine instance has no id, so its data directory could " +
			"not be resolved; the panel sends database_engine.id as data_volume_id")
	}
	if len(value) > 64 {
		return fmt.Errorf("dbengine: the engine instance id %q is longer than 64 characters", value)
	}
	if strings.HasPrefix(value, ".") {
		return fmt.Errorf("dbengine: the engine instance id %q starts with a dot, which would name "+
			"a directory relative to something other than itself", value)
	}
	for _, character := range value {
		switch {
		case character >= 'a' && character <= 'z',
			character >= 'A' && character <= 'Z',
			character >= '0' && character <= '9',
			character == '-', character == '_':
		default:
			return fmt.Errorf("dbengine: the engine instance id %q contains %q, and only letters, "+
				"digits, '-' and '_' may reach a filesystem path or a container name",
				value, string(character))
		}
	}
	return nil
}

// transferName is a unique file name for one dump in flight.
//
// Random rather than derived from the database name, because two backups of one database can
// legitimately overlap - a scheduled one and an operator pressing the button - and the
// second must not write into the file the first is still reading.
func transferName(prefix, extension string) (string, error) {
	suffix := make([]byte, 8)
	if _, err := rand.Read(suffix); err != nil {
		return "", fmt.Errorf("dbengine: name a transfer file: %w", err)
	}
	return prefix + "-" + hex.EncodeToString(suffix) + extension, nil
}
