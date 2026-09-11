package daemon

import (
	"context"
	"fmt"
	"io"
	"log/slog"

	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/backup"
	"github.com/furimeo/wisper/sasayaki/internal/build"
	"github.com/furimeo/wisper/sasayaki/internal/cron"
	"github.com/furimeo/wisper/sasayaki/internal/dbengine"
	"github.com/furimeo/wisper/sasayaki/internal/edge"
	"github.com/furimeo/wisper/sasayaki/internal/files"
	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/rpc"
	"github.com/furimeo/wisper/sasayaki/internal/runtime"
	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/stats"
	"github.com/furimeo/wisper/sasayaki/internal/terminal"
)

// node is one assembled daemon: every long-lived thing on this machine, and the handles
// that have to be given back when it stops.
type node struct {
	log      *slog.Logger
	settings settings

	store     *state.Store
	docker    *runtime.Docker
	engine    *client.Client
	link      *panel
	sampler   *stats.Sampler
	files     *files.Host
	builder   *build.Builder
	terminals *terminal.Host
	engines   *dbengine.Engines
	edge      *edge.Edge
	backups   *backup.Runner
	cron      *cron.Scheduler
	loop      *reconcile.Loop
	client    *rpc.Client

	// stopFeeds ends every log subscription still reading a container.
	stopFeeds context.CancelFunc
}

// open builds the whole node, in the order the dependencies force.
//
// It fails as a unit. A step that cannot complete releases everything the earlier ones
// opened, because a half-built daemon holding a SQLite handle and two Docker connections is
// one that systemd's Restart=always cannot cleanly start again.
func open(ctx context.Context, options settings, credential rpc.Credential,
	log *slog.Logger, errOut io.Writer) (*node, error) {

	n := &node{log: log, settings: options}
	assembled := false
	defer func() {
		if !assembled {
			n.close()
		}
	}()

	if err := n.openState(ctx); err != nil {
		return nil, err
	}
	if err := n.openEngines(ctx); err != nil {
		return nil, err
	}

	n.link = newPanel(credential.NodeID, log)
	if err := n.openWorkers(ctx); err != nil {
		return nil, err
	}
	if err := n.openControl(ctx, credential, errOut); err != nil {
		return nil, err
	}

	assembled = true
	return n, nil
}

// openState opens the node's memory. First, because everything else is written down in it.
func (n *node) openState(ctx context.Context) error {
	store, err := state.Open(ctx, state.Path(n.settings.stateDir))
	if err != nil {
		return err
	}
	n.store = store

	if quarantined := store.RecoveredFrom(); quarantined != "" {
		// Not a failure - the node rebuilds all of this from the next spec - but an operator
		// has to be told, because the evidence is on disk and nothing else will mention it.
		n.log.Error("the node's state database was unreadable and a new one was started; the "+
			"old file has been kept",
			slog.String("quarantined", quarantined),
			slog.String("meaning", "uploads, build history and certificate records from before "+
				"this point are gone; the panel will resend the spec and the node will reconverge"))
	}
	return nil
}

// openEngines opens the two connections to Docker.
//
// Two, and the reason is a boundary rather than an oversight. runtime.Docker owns a
// connection behind an interface that lists exactly what this daemon asks of a container
// engine, and that list deliberately excludes building an image, creating a database server
// and freezing a container - each of which is one other package's whole job. Those three
// share the second connection, opened here the same way runtime opens its own, rather than
// each opening a third and a fourth as their constructors would otherwise do.
func (n *node) openEngines(ctx context.Context) error {
	docker, err := runtime.New(ctx, n.settings.stateDir,
		runtime.WithLogger(n.log),
		runtime.WithDevMode(n.settings.dev))
	if err != nil {
		return err
	}
	n.docker = docker

	engine, err := client.NewClientWithOpts(client.FromEnv, client.WithAPIVersionNegotiation())
	if err != nil {
		return fmt.Errorf("daemon: prepare a Docker client: %w", err)
	}
	n.engine = engine
	return nil
}

// openWorkers builds everything that does work on this node.
func (n *node) openWorkers(ctx context.Context) error {
	var err error

	n.sampler, err = stats.New(ctx, stats.Options{
		Engine:   n.docker,
		Specs:    n.store,
		Uplink:   n.link,
		StateDir: n.settings.stateDir,
		Logger:   n.log,
	})
	if err != nil {
		return err
	}

	// One Releases, handed to both the builder that writes a release and the reconcile loop
	// that publishes one. Two would be two pieces of code moving the same `current` symlink.
	releases, err := build.NewReleases(n.settings.stateDir, n.log)
	if err != nil {
		return err
	}

	n.files, err = files.New(files.Options{
		StateDir: n.settings.stateDir,
		Store:    n.store,
		Logger:   n.log,
	})
	if err != nil {
		return err
	}

	n.builder, err = build.New(build.Options{
		StateDir: n.settings.stateDir,
		Engine:   n.engine,
		Store:    n.store,
		Uploads:  n.files,
		Logs:     n.link,
		Releases: releases,
		Logger:   n.log,
		Dev:      n.settings.dev,
	})
	if err != nil {
		return err
	}

	n.terminals, err = terminal.New(terminal.Options{
		Engine: terminal.Docker(n.docker),
		Logger: n.log,
	})
	if err != nil {
		return err
	}

	n.engines, err = dbengine.New(dbengine.Options{
		StateDir: n.settings.stateDir,
		Engine:   n.engine,
		Commands: n.docker,
		Store:    n.store,
		Logger:   n.log,
	})
	if err != nil {
		return err
	}

	if err := n.openEdge(); err != nil {
		return err
	}

	n.backups, err = backup.New(backup.Options{
		StateDir:  n.settings.stateDir,
		Store:     n.store,
		Workloads: frozenWorkloads{containers: n.docker, engine: n.engine},
		Databases: databaseDumps{engines: n.engines},
		Logger:    n.log,
	})
	if err != nil {
		return err
	}

	// The customer's own schedules. It reads the spec off the disk rather than being handed
	// one, so it keeps firing through a panel that cannot be reached - which is the entire
	// reason cron lives in the NodeSpec instead of the panel's job queue.
	n.cron, err = cron.New(cron.Options{
		Containers: n.docker,
		Store:      n.store,
		Logger:     n.log,
	})
	if err != nil {
		return err
	}

	n.loop, err = reconcile.New(n.reconcileOptions(releases))
	return err
}

// openEdge builds the embedded Caddy. Nothing listens until serve starts it.
func (n *node) openEdge() error {
	backends, err := edge.NewDockerBackends(n.engine)
	if err != nil {
		return err
	}
	n.edge, err = edge.New(edge.Options{
		StateDir: n.settings.stateDir,
		Backends: backends,
		Store:    n.store,
		Logger:   n.log,
	})
	return err
}

// reconcileOptions is the loop's wiring, and the one place --dev changes the daemon's
// relationship with systemd.
//
// A negative interval disables the watchdog ticker and a notify that does nothing disables
// the readiness message. Both are needed: `sasayaki run --dev` is started by a developer
// from a Makefile target, where there is no NOTIFY_SOCKET to write to and no unit waiting
// to be told the node is ready (docs/contracts/sasayaki-commands.md).
func (n *node) reconcileOptions(releases *build.Releases) reconcile.Options {
	options := reconcile.Options{
		Runtime:   n.docker,
		Sites:     releases,
		Edge:      n.edge,
		Store:     n.store,
		Databases: n.engines,
		Reporter:  n.link,
		Logger:    n.log,
	}
	if n.settings.dev {
		options.WatchdogInterval = -1
		options.Notify = func(string) error { return nil }
	}
	return options
}

// close gives back every handle the daemon holds, in the reverse of the order they were
// taken.
//
// It is not cleanup in the sense the crash-only rule forbids: nothing here has to happen for
// the node to be correct after being killed. Not one line of it touches a customer's
// container, a database server or a release directory - those are meant to outlive the
// daemon, and stopping them on the way out would make every upgrade an outage
// (AGENTS.md section 4.4).
//
// Every failure is logged rather than returned. The process is going away, and replacing the
// reason it is going away with "and closing a file handle also failed" helps nobody.
// Fields are checked for nil because open fails as a unit and calls this on its way out,
// at which point half of them were never built.
func (n *node) close() {
	if n.stopFeeds != nil {
		n.stopFeeds()
	}
	if n.client != nil {
		n.release("the panel connection", n.client.Close())
	}
	if n.sampler != nil {
		n.release("the stats buffer", n.sampler.Close())
	}
	// Deliberately not dbengine.Engines.Close: it would close the connection below, which
	// three packages share and this is the one place that owns.
	if n.engine != nil {
		n.release("the shared Docker connection", n.engine.Close())
	}
	if n.docker != nil {
		n.release("the container runtime", n.docker.Close())
	}
	if n.store != nil {
		n.release("the state database", n.store.Close())
	}
}

func (n *node) release(what string, err error) {
	if err != nil {
		n.log.Warn("could not release "+what, slog.String("error", err.Error()))
	}
}
