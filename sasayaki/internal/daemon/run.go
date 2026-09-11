// Package daemon is the composition root of a running node.
//
// Every other package under internal/ knows only the interfaces it declared for itself.
// This one knows all of them, and that asymmetry is deliberate: the wiring is the only
// place allowed to see the whole machine, so a change to how the reconcile loop reaches
// the edge is an edit here rather than an import somewhere that should not have one.
//
// What it assembles, in the order the dependencies force:
//
//	state      the SQLite the node reconverges from, opened first because everything
//	           else is written down in it
//	runtime    the container engine, plus a second Engine API connection shared by the
//	           three packages that need the raw client (engine.go)
//	stats      the measuring loop, whose readings the heartbeat carries
//	build      releases and the builder, which the reconcile loop publishes through
//	files      the web file manager, which is also where an uploaded zip comes from
//	terminal   interactive sessions
//	dbengine   the shared database servers
//	edge       the embedded Caddy
//	backup     snapshots and restores
//	reconcile  the fifteen-second loop
//	rpc        the connection to the panel, built last because it needs all of the above
//
// There is one cycle in that list and it is unavoidable: the panel client needs the
// handlers, and three of the handlers need the panel client. It is resolved in exactly
// one place, by the late-bound shim in panel.go, rather than by making every package
// tolerate a nil collaborator.
//
// Nothing here does cleanup that the node's correctness depends on. sasayaki is
// crash-only: being killed has to be indistinguishable from stopping, so shutdown
// releases handles and takes the listeners down and touches no customer's container
// (AGENTS.md section 4.4).
package daemon

import (
	"context"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"path/filepath"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/bootstrap"
	"github.com/furimeo/wisper/sasayaki/internal/rpc"
	"github.com/furimeo/wisper/sasayaki/internal/version"
)

// settings is what `sasayaki run` was asked for.
type settings struct {
	// configPath is the node credential, /etc/wisper/node.json.
	configPath string

	// stateDir is absolute by the time it is in here. It has to be: six packages refuse a
	// relative state directory, because one resolves against whatever directory the
	// process happened to be started in - and `make run-dev` passes ./var/dev.
	stateDir string

	// dev is a developer's node: no systemd notifications, runc instead of runsc, and
	// debug logging. It is what makes the daemon runnable inside WSL2, whose kernel is not
	// one gVisor supports (design section 13.5). Every reduction in isolation it causes is
	// reported to the panel rather than hidden.
	dev bool
}

// Run is `sasayaki run`: connect to the panel and reconcile until the context ends.
func Run(ctx context.Context, args []string, out, errOut io.Writer) error {
	options, err := parse(args, out)
	if err != nil {
		return err
	}

	credential, err := rpc.LoadCredential(options.configPath)
	if err != nil {
		return fmt.Errorf("%w\n\nA node runs against a panel it has enrolled with. "+
			"Run `sasayaki install --token-file <path>` first", err)
	}

	log := logger(errOut, options.dev).With(slog.String("node", credential.NodeID))
	// Anything that was handed no logger of its own - the Docker client, a dependency that
	// reaches for the package default - writes to the same journal as the rest of the
	// daemon rather than to a stream nobody is reading.
	slog.SetDefault(log)

	log.Info("starting",
		slog.String("version", version.Short()),
		slog.String("state_dir", options.stateDir),
		slog.String("config", options.configPath),
		slog.String("panel", credential.Panel),
		slog.Bool("dev", options.dev))

	node, err := open(ctx, options, credential, log, errOut)
	if err != nil {
		return err
	}
	defer node.close()

	return node.serve(ctx)
}

// parse reads the flags the Makefile's run-dev target and the systemd unit both pass.
//
// The error from Parse is returned unchanged, because main recognises flag.ErrHelp and
// exits 0 without printing anything further (docs/contracts/sasayaki-commands.md).
func parse(args []string, out io.Writer) (settings, error) {
	flags := flag.NewFlagSet("run", flag.ContinueOnError)
	flags.SetOutput(out)

	configPath := flags.String("config", bootstrap.DefaultConfigPath,
		"the node credential written at enrolment")
	stateDir := flags.String("state-dir", bootstrap.DefaultStateDir,
		"where SQLite, specs, sites, volumes and certificates live")
	dev := flags.Bool("dev", false,
		"a developer's node: no systemd notifications, runc instead of runsc, debug logging")

	if err := flags.Parse(args); err != nil {
		return settings{}, err
	}
	if flags.NArg() > 0 {
		return settings{}, fmt.Errorf("run takes no arguments, and %q is not a flag", flags.Arg(0))
	}
	if strings.TrimSpace(*stateDir) == "" {
		// Refused rather than resolved. An empty path becomes the working directory, and a
		// daemon that put a customer's volumes wherever systemd happened to start it is one
		// nobody would find the data of afterwards.
		return settings{}, fmt.Errorf("--state-dir is empty: it is where every volume, release " +
			"and certificate on this node lives, so it has to name a directory")
	}

	// Resolved here, once, rather than in each of the six packages that would otherwise
	// refuse it. A relative --state-dir is a legitimate thing to pass a daemon you are
	// developing against; a relative path stored inside a container mount specification is
	// not (runtime/mounts.go).
	absolute, err := filepath.Abs(*stateDir)
	if err != nil {
		return settings{}, fmt.Errorf("resolve the state directory %q: %w", *stateDir, err)
	}

	return settings{configPath: *configPath, stateDir: absolute, dev: *dev}, nil
}

// logger is the daemon's log.
//
// To errOut, which under systemd is the journal: it is the one destination that is still
// being read after an upgrade's restart has killed the process writing to it. Debug level
// under --dev, where somebody is watching, and info otherwise - a node writing a line per
// container per pass fills a disk this daemon is supposed to be protecting.
func logger(errOut io.Writer, dev bool) *slog.Logger {
	level := slog.LevelInfo
	if dev {
		level = slog.LevelDebug
	}
	return slog.New(slog.NewTextHandler(errOut, &slog.HandlerOptions{Level: level}))
}
