package runtime

import (
	"fmt"
	"os"
	"path/filepath"
)

// The storage layout, which had to be decided before a line of code was written because
// changing it afterwards means moving customer data (design section 11.1).
//
//	<state>/volumes/<workload-id>/<volume-id>   a customer's disk, quota-managed, backed up
//	<state>/sites/<workload-id>/current         the symlink a static site is served from
//
// Ids, never names. A customer renaming a service must not move a directory, and the
// panel deliberately sends no filesystem path at all: it names a volume by id and the
// node resolves it here (docs/contracts/node-spec.md section 3.5). That is what makes it
// impossible for a spec to ask for /var/run/docker.sock - there is no field in which to
// ask, and checkIdentifier refuses anything that could climb out of the tree by hand.
const (
	volumesDirectory = "volumes"
	sitesDirectory   = "sites"

	// currentRelease is the symlink the build package moves to publish a release.
	// Mounted rather than the release directory itself, so an app serving a site sees
	// the new files the moment the symlink swings.
	currentRelease = "current"

	// privateMode is every directory this package creates on the way down: root only.
	// It is the real access control on a volume, which is why the volume itself can be
	// world-writable without being reachable by anything on the host.
	privateMode os.FileMode = 0o700

	// volumeMode is the mount root a container writes into.
	//
	// World-writable, deliberately, and safe only because of privateMode above: nothing
	// on the host can traverse into it without being root already. The alternative is
	// guessing which uid will be inside the container - the image's own user, or a
	// remapped root when the daemon runs with --userns-remap, and the node cannot know
	// which - and guessing wrong gives a customer an application that starts and then
	// cannot write to its own data directory.
	volumeMode os.FileMode = 0o777
)

// volumePath is where one volume of one workload lives.
func (d *Docker) volumePath(workloadID, volumeID string) (string, error) {
	if err := checkIdentifier("workload id", workloadID); err != nil {
		return "", err
	}
	if err := checkIdentifier("volume id", volumeID); err != nil {
		return "", err
	}
	return filepath.Join(d.stateDir, volumesDirectory, workloadID, volumeID), nil
}

// sitePath is the `current` symlink of a static site.
//
// The build package owns what is behind it - releases/<deploy-id>/ and the atomic swap -
// and this package only ever mounts it, read-only, into an app that wants to serve or
// post-process what a build produced.
func (d *Docker) sitePath(workloadID string) (string, error) {
	if err := checkIdentifier("site workload id", workloadID); err != nil {
		return "", err
	}
	return filepath.Join(d.stateDir, sitesDirectory, workloadID, currentRelease), nil
}

// ensureVolume creates a volume directory if it is not there yet.
//
// Creating it here rather than letting Docker create the bind source is not a detail. A
// missing bind source makes the engine create a root-owned directory with whatever mode
// it likes, at a path this package would then have to trust it got right - and on some
// engine versions it creates a file instead, which turns a customer's data directory into
// an empty regular file mounted over their application's working directory.
func ensureVolume(path string) error {
	info, err := os.Stat(path)
	switch {
	case err == nil && info.IsDir():
		return nil
	case err == nil:
		return fmt.Errorf("runtime: %s is not a directory, so it cannot back a volume", path)
	case !os.IsNotExist(err):
		return fmt.Errorf("runtime: look at %s: %w", path, err)
	}

	if err := os.MkdirAll(filepath.Dir(path), privateMode); err != nil {
		return fmt.Errorf("runtime: create %s: %w", filepath.Dir(path), err)
	}
	if err := os.Mkdir(path, volumeMode); err != nil && !os.IsExist(err) {
		return fmt.Errorf("runtime: create %s: %w", path, err)
	}
	// Mkdir's mode is masked by the process umask, and the daemon inherits systemd's.
	// Without this the directory comes out 0755 and a container that does not run as
	// root cannot write to its own volume.
	if err := os.Chmod(path, volumeMode); err != nil {
		return fmt.Errorf("runtime: set the mode of %s: %w", path, err)
	}
	return nil
}
