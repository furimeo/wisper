package daemon

import (
	"context"
	"io"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/bootstrap"
	"github.com/furimeo/wisper/sasayaki/internal/rpc"
)

// Everything the panel can ask this node to do, in one place.
//
// rpc.Handlers is eleven fields and it refuses a nil one at construction, because a command
// the panel can send and nobody answers is a stub with extra steps. Six of them are a
// package handed over whole - the builder, the backup runner, the database layer, the file
// host, the terminal host, the self-upgrade - and the other five are the small types in
// this package that had nowhere else to live: the two halves of the handshake, the spec
// receiver, the drain-aware view of the reconcile loop, and the log subscriptions.
//
// This is also the last step of construction, and it has to be: the client needs all eleven
// before it will exist, and three of the eleven were built with a shim standing in for the
// client. Attaching it is what makes the graph whole (panel.go).

// openControl builds the connection to the panel and attaches it to the shim every other
// package was handed in its place.
func (n *node) openControl(ctx context.Context, credential rpc.Credential, errOut io.Writer) error {
	// Derived from the daemon's own lifetime rather than from a control stream. A log feed
	// built on the stream that asked for it would stop every time the tunnel blinked, and
	// the customer would watch a pane that quietly went still (logs.go).
	feeds, stopFeeds := context.WithCancel(ctx)
	n.stopFeeds = stopFeeds

	steering := &reconciler{loop: n.loop}

	client, err := rpc.New(credential, rpc.Handlers{
		Hello: nodeDescription{
			generations:   n.store,
			engine:        n.docker,
			capacity:      n.sampler,
			log:           n.log,
			stateDir:      n.settings.stateDir,
			panelEndpoint: credential.Panel,
			preflight:     bootstrap.Preflight,
		},
		Heartbeat: &nodeHeartbeat{
			convergence: n.store,
			sampler:     n.sampler,
			drain:       steering,
			log:         n.log,
		},
		Spec:      storedSpec{store: n.store, loop: n.loop, now: time.Now},
		Reconcile: steering,
		Terminals: n.terminals,
		Builds:    n.builder,
		Backups:   n.backups,
		Databases: n.engines,
		Upgrades: bootstrap.SelfUpgrade{
			// The binary path is left at its default on purpose: it is where the installer
			// put the daemon and what the systemd unit executes, and a node upgrading itself
			// has to replace that file rather than whatever `go run` happened to compile.
			ConfigPath: n.settings.configPath,
			StateDir:   n.settings.stateDir,
			Output:     errOut,
		},
		Logs:  newLogFeeds(feeds, containerLogs(n.docker), n.link, n.log),
		Files: n.files,
	}, rpc.WithLogger(n.log))
	if err != nil {
		return err
	}

	n.client = client
	n.link.attach(client)
	return nil
}
