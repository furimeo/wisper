package bootstrap

import (
	"context"
	"errors"
	"io"
	"os"

	"github.com/furimeo/wisper/sasayaki/internal/rpc"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// SelfUpgrade is the daemon replacing its own binary because the panel told it to.
//
// It is the `rpc.Upgrader` the composition root injects, and it exists as a type rather
// than a function only because the daemon has to tell it where things are when it is
// running with non-default paths.
//
// One thing separates it from the `upgrade` subcommand, and it decides the whole design:
// this code runs inside sasayaki.service, so the restart it asks for kills the process
// asking. There is nobody left to see whether the new binary came back. The watching is
// therefore delegated to systemd, which is the one thing on the machine guaranteed to
// still be alive: sasayaki.service has a start limit and names sasayaki-rollback.service
// in OnFailure=, so a binary that crash-loops is put back automatically (design section
// 7.5, and see unit.go).
type SelfUpgrade struct {
	// BinaryPath is the installed daemon. Empty means /usr/local/bin/sasayaki.
	BinaryPath string

	// ConfigPath is the node credential; the upgrade marker is written beside it. Empty
	// means /etc/wisper/node.json.
	ConfigPath string

	// StateDir is only used to make the layout complete. Empty means /var/lib/wisper.
	StateDir string

	// Output is where progress goes. Empty means stderr, which under systemd is the
	// journal - the right place for a running service, and the only place these lines can
	// still be read after the restart has killed the process writing them.
	Output io.Writer
}

// The daemon injects this as its rpc.Upgrader. Asserted here so a change to that interface
// is a compile error in the package that has to satisfy it, rather than in the composition
// root that only passes it along.
var _ rpc.Upgrader = SelfUpgrade{}

// Upgrade verifies and installs the binary the panel published.
//
// It returns a result the panel can show, but usually it does not return at all: the
// restart happens a few milliseconds after the last line below, and this process is one of
// the things it stops. `rpc.Upgrader` documents that, and the panel learns the upgrade
// worked from the version in the next NodeHello rather than from an answer to the command.
func (s SelfUpgrade) Upgrade(ctx context.Context, request *wisperpb.UpgradeNode) (*wisperpb.UpgradeResult, error) {
	if request.GetDownloadUrl() == "" {
		return nil, errors.New("the panel sent an upgrade with no download URL")
	}
	if normaliseChecksum(request.GetSha256()) == "" {
		return nil, errors.New("the panel sent an upgrade with no SHA-256. A binary nobody " +
			"verifies is a way to run anything on this node as root, so it is refused here " +
			"rather than trusted")
	}

	steps := newUpgradeSteps(s.layout(), hostSystemd(), s.output())
	return steps.apply(ctx, upgradeIntent{
		source: upgradeSource{
			url:    request.GetDownloadUrl(),
			sha256: request.GetSha256(),
		},
		targetVersion: request.GetTargetVersion(),
		restart:       true,
		// The restart kills this process. Watching it is systemd's job, through the unit
		// named in OnFailure=.
		supervise: false,
	})
}

func (s SelfUpgrade) layout() layout {
	installed := defaultLayout()
	if s.BinaryPath != "" {
		installed.BinaryPath = s.BinaryPath
	}
	if s.ConfigPath != "" {
		installed.ConfigPath = s.ConfigPath
	}
	if s.StateDir != "" {
		installed.StateDir = s.StateDir
	}
	return installed
}

func (s SelfUpgrade) output() io.Writer {
	if s.Output != nil {
		return s.Output
	}
	return os.Stderr
}
