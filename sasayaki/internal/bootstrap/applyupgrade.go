package bootstrap

import (
	"context"
	"fmt"
	"io"
	"os"
	"strings"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// upgradeSource is where the new binary comes from: a URL the panel published, or a file
// an operator carried onto a machine with no route to it.
type upgradeSource struct {
	url       string
	sha256    string
	localPath string
}

// upgradeIntent is one upgrade as asked for.
type upgradeIntent struct {
	source upgradeSource

	// targetVersion is what the panel says it is sending, for the log line before the
	// binary has been run. Empty when an operator is installing a file by hand.
	targetVersion string

	// force replaces the binary even when it is the version already running. Without it,
	// an upgrade to the running version is a no-op rather than a pointless restart.
	force bool

	// restart is false for an operator who wants the file swapped and the service left
	// alone until a maintenance window.
	restart bool

	// supervise is whether this process can watch what happens next.
	//
	// It cannot when the daemon is upgrading itself: `systemctl restart` on the unit this
	// process lives in kills this process, so there is nobody left to notice that the new
	// binary did not come back. In that case the marker is left in place and the rollback
	// is systemd's job, through the OnFailure= unit (see unit.go). Run from a terminal,
	// this process is outside the service's control group and can do the watching itself,
	// which is both faster and more precise.
	supervise bool
}

// upgradeSteps is everything an upgrade touches, with each dependency as a field so a test
// can drive a broken one.
type upgradeSteps struct {
	layout  layout
	systemd systemd
	watch   restartWatch

	// fetch downloads a binary. probe asks a binary what version it is.
	fetch func(ctx context.Context, url string) ([]byte, error)
	probe func(ctx context.Context, path string) (string, error)

	now func() time.Time
	out io.Writer
}

func newUpgradeSteps(l layout, sd systemd, out io.Writer) upgradeSteps {
	return upgradeSteps{
		layout:  l,
		systemd: sd,
		watch:   newRestartWatch(sd, unitName),
		fetch:   downloadBinary,
		probe:   probeVersion,
		now:     time.Now,
		out:     out,
	}
}

// apply replaces this node's binary, or explains why it did not.
//
// The order is the design's, and every step of it exists because of the step after it
// (design section 7.5):
//
//  1. stage the new binary next to the old one and verify its checksum - so a bad
//     download costs nothing;
//  2. run it and read its version - so a binary for the wrong architecture is caught
//     before it becomes the one systemd tries to start;
//  3. record an upgrade marker - so whatever is still alive afterwards knows a rollback is
//     legitimate;
//  4. keep a copy of the old binary and rename the new one into place - so there is
//     something to roll back to, and so no moment exists in which the path does not exist;
//  5. restart, and watch it stay up - so "the file was replaced" is not mistaken for
//     "the upgrade worked".
func (s upgradeSteps) apply(ctx context.Context, intent upgradeIntent) (*wisperpb.UpgradeResult, error) {
	if err := s.layout.validate(); err != nil {
		return nil, err
	}

	running := s.currentVersion(ctx)
	staged, err := s.stage(ctx, intent.source)
	if err != nil {
		return nil, err
	}
	// From here on, failing means removing the staged file. It is next to the live binary
	// and leaving it there would have the next upgrade wondering what it is.
	defer os.Remove(staged.path)

	incoming, err := s.probe(ctx, staged.path)
	if err != nil {
		return nil, fmt.Errorf("the downloaded binary is not one this machine can run: %w.\n"+
			"Its checksum was correct, so this is the wrong build rather than a corrupt "+
			"one. Nothing has been replaced", err)
	}
	fmt.Fprintf(s.out, "Staged sasayaki %s (%s, sha256 %s)\n", incoming,
		formatBytes(staged.size), staged.sha256)

	// Not fatal: the checksum already proved these are the bytes the panel published, so a
	// disagreement here is the panel's release directory being inconsistent with what was
	// built rather than an attack. Worth saying out loud, because the version is what the
	// panel decides whether to offer another upgrade against.
	if !sameVersion(intent.targetVersion, incoming) {
		fmt.Fprintf(s.out, "warning: the panel called this %s and the binary calls itself "+
			"%s\n", intent.targetVersion, incoming)
	}

	if incoming == running && !intent.force {
		return &wisperpb.UpgradeResult{
			PreviousVersion: running,
			NewVersion:      running,
			Detail: fmt.Sprintf("This node is already running %s. Nothing was replaced; "+
				"pass --force to reinstall the same version.", running),
		}, nil
	}

	marker := upgradeMarker{
		PreviousVersion: running,
		NewVersion:      incoming,
		Target:          s.layout.BinaryPath,
		PreviousBinary:  s.layout.previousBinaryPath(),
		StartedAt:       s.now().UTC(),
	}
	if err := writeUpgradeMarker(s.layout.ConfigPath, marker); err != nil {
		return nil, err
	}

	if err := s.keepPrevious(); err != nil {
		clearMarkerQuietly(s.layout.ConfigPath)
		return nil, err
	}
	if err := os.Rename(staged.path, s.layout.BinaryPath); err != nil {
		clearMarkerQuietly(s.layout.ConfigPath)
		return nil, fmt.Errorf("install %s: %w", s.layout.BinaryPath, err)
	}
	fmt.Fprintf(s.out, "Installed %s\n", s.layout.BinaryPath)

	return s.restartOnto(ctx, intent, marker)
}

// restartOnto brings the service up on the binary that is now in place.
func (s upgradeSteps) restartOnto(ctx context.Context, intent upgradeIntent,
	marker upgradeMarker) (*wisperpb.UpgradeResult, error) {

	result := &wisperpb.UpgradeResult{
		PreviousVersion: marker.PreviousVersion,
		NewVersion:      marker.NewVersion,
	}

	if !intent.restart || !s.serviceRuns(ctx) {
		reason := "the restart was not asked for"
		if intent.restart {
			reason = "there is no " + unitName + " running here to restart"
		}
		clearMarkerQuietly(s.layout.ConfigPath)
		result.Detail = fmt.Sprintf("%s is in place and %s, so it will be used the next time "+
			"the daemon starts.", s.layout.BinaryPath, reason)
		fmt.Fprintln(s.out, result.Detail)
		return result, nil
	}

	if !intent.supervise {
		// The restart is about to kill this process, so the marker stays and the OnFailure
		// unit is what notices a binary that does not come back.
		fmt.Fprintf(s.out, "Restarting %s onto %s\n", unitName, marker.NewVersion)
		if err := s.systemd.restart(ctx, unitName); err != nil {
			// The swap already happened, so this is not a clean failure: say so plainly and
			// leave the marker, because the node is now one restart away from the new
			// binary and systemd will get there on its own if anything else stops it.
			result.Detail = fmt.Sprintf("%s is in place but the restart could not be "+
				"requested: %v", s.layout.BinaryPath, err)
			return result, nil
		}
		result.Detail = fmt.Sprintf("Restarting onto %s. If it does not come back, systemd "+
			"starts %s and this node returns to %s.", marker.NewVersion, rollbackUnitName,
			marker.PreviousVersion)
		return result, nil
	}

	fmt.Fprintf(s.out, "Restarting %s and watching it for %s\n", unitName, s.watch.settle)
	restartErr := s.systemd.restart(ctx, unitName)
	if restartErr == nil {
		restartErr = s.watch.healthy(ctx)
	}
	if restartErr == nil {
		clearMarkerQuietly(s.layout.ConfigPath)
		result.Detail = fmt.Sprintf("Upgraded from %s to %s and %s is up.",
			orUnknown(marker.PreviousVersion), marker.NewVersion, unitName)
		fmt.Fprintln(s.out, result.Detail)
		return result, nil
	}

	fmt.Fprintf(s.out, "%s did not come back: %v\nRolling back to %s.\n",
		unitName, restartErr, orUnknown(marker.PreviousVersion))
	return s.rollBack(ctx, marker, restartErr.Error())
}

// currentVersion is what is installed now. An empty answer is normal on a machine where
// nothing has been installed yet, and it is not worth an error: the point of asking is to
// have something to put in previous_version.
func (s upgradeSteps) currentVersion(ctx context.Context) string {
	if !exists(s.layout.BinaryPath) {
		return ""
	}
	version, err := s.probe(ctx, s.layout.BinaryPath)
	if err != nil {
		return ""
	}
	return version
}

// keepPrevious copies the live binary aside. A copy rather than a rename, so the live path
// never stops existing even for the microsecond between two renames.
func (s upgradeSteps) keepPrevious() error {
	if !exists(s.layout.BinaryPath) {
		return nil
	}
	return copyFile(s.layout.BinaryPath, s.layout.previousBinaryPath(), 0o755)
}

// serviceRuns reports whether there is a systemd service to restart at all. An offline
// install, a container and a WSL distribution without systemd all answer no, and all three
// are places where replacing the binary is still the right thing to have done.
func (s upgradeSteps) serviceRuns(ctx context.Context) bool {
	if s.systemd.booted != nil && !s.systemd.booted() {
		return false
	}
	switch s.systemd.state(ctx, unitName) {
	case unitAbsent:
		return false
	default:
		return true
	}
}

// sameVersion compares what the panel called a build with what the build calls itself,
// ignoring the leading "v" that a git tag has and a release file name does not. An empty
// expectation matches anything: an operator installing a file by hand has not promised a
// version.
func sameVersion(expected, actual string) bool {
	if expected == "" {
		return true
	}
	return strings.TrimPrefix(expected, "v") == strings.TrimPrefix(actual, "v")
}

// clearMarkerQuietly removes the marker on a path where failing to remove it is not worth
// replacing the error the caller is already reporting. A marker that outlives its upgrade
// expires on its own after upgradeWindow.
func clearMarkerQuietly(configPath string) {
	_ = clearUpgradeMarker(configPath)
}
