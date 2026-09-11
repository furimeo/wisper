package bootstrap

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Where a node keeps its things. These are the paths every other part of wisper assumes:
// the panel's generated installer writes the binary to DefaultBinaryPath, the systemd
// unit points at DefaultConfigPath and DefaultStateDir, and docs/contracts says so.
const (
	// DefaultConfigPath is the node credential, 0600 and root-owned.
	DefaultConfigPath = "/etc/wisper/node.json"

	// DefaultStateDir holds SQLite, specs, sites, volumes and certificates. It is the
	// one directory uninstall never touches: everything a customer owns is under it.
	DefaultStateDir = "/var/lib/wisper"

	// DefaultBinaryPath is where install puts the daemon and what the unit executes.
	DefaultBinaryPath = "/usr/local/bin/sasayaki"

	// unitName is the systemd unit. Fixed, because uninstall has to be able to find a
	// unit installed by a version of this binary that no longer exists.
	unitName = "sasayaki.service"

	// unitDirectory is where a locally installed unit belongs - /etc, not /lib, because
	// this one is administrator-installed rather than shipped by a distribution package.
	unitDirectory = "/etc/systemd/system"

	// nodeKeyFileName sits beside node.json and holds the Ed25519 identity. Separate
	// from the credential because it outlives it: the credential can be reissued, the
	// key is what the panel recorded as this machine's public half.
	nodeKeyFileName = "node.key"

	// previousBinarySuffix is the binary an upgrade rolls back to. One generation, kept
	// beside the live binary so the swap is a rename on one filesystem.
	previousBinarySuffix = ".previous"
)

// layout is every path one installation uses.
//
// It is a struct rather than a set of constants read directly because that is what makes
// the installer testable: a test puts an entire installation under t.TempDir() and can
// then prove the thing the design demands - that a failing doctor leaves the directory
// empty (design section 7.2), and that uninstall does not touch the state directory
// (section 7.7). Assertions about "nothing was written" are only worth making when the
// code under test could have written.
type layout struct {
	// ConfigPath is node.json.
	ConfigPath string

	// StateDir is /var/lib/wisper.
	StateDir string

	// BinaryPath is the installed daemon.
	BinaryPath string

	// UnitDir is the directory the systemd unit is written into.
	UnitDir string
}

func defaultLayout() layout {
	return layout{
		ConfigPath: DefaultConfigPath,
		StateDir:   DefaultStateDir,
		BinaryPath: DefaultBinaryPath,
		UnitDir:    unitDirectory,
	}
}

// keyPath is the Ed25519 private key, beside the credential.
func (l layout) keyPath() string {
	return filepath.Join(filepath.Dir(l.ConfigPath), nodeKeyFileName)
}

// configDir is /etc/wisper: created 0700, because both files in it are secrets.
func (l layout) configDir() string {
	return filepath.Dir(l.ConfigPath)
}

// unitPath is the full path of sasayaki.service.
func (l layout) unitPath() string {
	return filepath.Join(l.UnitDir, unitName)
}

// previousBinaryPath is the binary an upgrade can go back to.
func (l layout) previousBinaryPath() string {
	return l.BinaryPath + previousBinarySuffix
}

// validate refuses a layout that would write somewhere unexpected. Every path has to be
// absolute: a relative one resolves against whatever directory the process happens to be
// in, and an installer that writes ./etc/wisper/node.json into an administrator's home
// directory has done something worse than fail.
func (l layout) validate() error {
	for name, path := range map[string]string{
		"config path": l.ConfigPath,
		"state dir":   l.StateDir,
		"binary path": l.BinaryPath,
		"unit dir":    l.UnitDir,
	} {
		if path == "" {
			return fmt.Errorf("%s is empty", name)
		}
		if !filepath.IsAbs(path) {
			return fmt.Errorf("%s %q is not absolute", name, path)
		}
		// Every one of these ends up in a systemd unit, where a space separates arguments
		// and a quote starts a new token. Refusing them here means the unit renderer never
		// has to escape anything, and an installation that would have produced a unit
		// systemd silently misreads fails at the point somebody can still fix it.
		if strings.ContainsAny(path, " \t\n\"'") {
			return fmt.Errorf("%s %q contains whitespace or a quote, which a systemd unit "+
				"cannot carry unambiguously", name, path)
		}
	}
	return nil
}

// exists is the question every step of install and uninstall asks. A path that cannot be
// stated for a reason other than absence is reported as present, because acting as if
// something is not there when the answer was really "permission denied" is how an
// installer overwrites a file it was not allowed to look at.
func exists(path string) bool {
	_, err := os.Lstat(path)
	return !os.IsNotExist(err)
}
