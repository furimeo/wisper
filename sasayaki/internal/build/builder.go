package build

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"path/filepath"
	"sync"
	"time"

	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/rpc"
)

// Building a Builder, and refusing to build one that cannot finish a build.
//
// Every collaborator is required. A nil engine would not fail here; it would fail on the
// first deployment a customer triggers, minutes after the daemon came up clean, which is
// both the least useful moment to find out and the one where somebody is watching.

// The contract with the control stream, checked by the compiler. rpc.Builder is declared
// by its consumer, Go style, and implemented here; asserting it costs nothing and catches
// a signature drifting apart at build time rather than in the composition root.
var _ rpc.Builder = (*Builder)(nil)

const (
	// gitImage is where `git` comes from. Pinned to a version rather than :latest,
	// because "the clone step started behaving differently and nothing changed" is a bad
	// afternoon, and because an image that is re-resolved on every build is one an
	// attacker who takes the tag gets to run on every node.
	//
	// A container rather than a host binary for two reasons. sasayaki is one static file
	// whose only dependencies are a kernel and a Docker socket, and a stranger's
	// repository - its hooks, its submodule URLs, its enormous history - is exactly the
	// input that should not be handed to a process running as root on the host.
	gitImage = "alpine/git:2.54.0"

	// defaultTimeout applies when the panel sends none. Long enough for a cold Node
	// install on a small machine, short enough that a hung build does not hold a slot
	// until somebody notices.
	defaultTimeout = 30 * time.Minute

	// workMount is where the workspace appears inside every build container. A constant,
	// so a plan's commands are the same wherever they run and a customer reading a build
	// log sees a path that means something.
	workMount = "/workspace"
)

// Options is everything a Builder is made of.
type Options struct {
	// StateDir is /var/lib/wisper. Workspaces, releases and the `current` symlink all hang
	// off it, and nothing outside it is ever written.
	StateDir string

	// Engine is the Docker Engine API. Left nil, one is opened from the environment, which
	// is what the daemon wants and what a test replaces.
	Engine Engine

	// Store is the node's SQLite. It makes a resent command idempotent and carries the
	// retention policy.
	Store Store

	// Uploads resolves an archive source to the file the customer uploaded.
	Uploads Uploads

	// Logs is where build output goes while it happens. Implemented by *rpc.Client.
	Logs LogSink

	// Releases is the site tree. Left nil, one is opened on StateDir.
	Releases *Releases

	// Logger defaults to slog.Default. The daemon passes one that knows the node id.
	Logger *slog.Logger

	// Now defaults to time.Now, replaced in tests so a build can be placed at an exact
	// moment without anything having to sleep.
	Now func() time.Time

	// Dev is `sasayaki run --dev`: runc instead of runsc, because WSL2's kernel is not one
	// gVisor supports and a developer's node would otherwise fail to run a single build
	// (design section 13.5). It is a real reduction in isolation and it is logged as one.
	Dev bool
}

func (o Options) validate() error {
	missing := make([]string, 0, 3)
	if o.Store == nil {
		missing = append(missing, "Store")
	}
	if o.Uploads == nil {
		missing = append(missing, "Uploads")
	}
	if o.Logs == nil {
		missing = append(missing, "Logs")
	}
	if len(missing) > 0 {
		return fmt.Errorf("build.Options is missing %v: every build uses all of these, so a "+
			"missing one is a deployment that fails in front of a customer rather than a "+
			"daemon that refuses to start", missing)
	}
	if o.StateDir == "" {
		return errors.New("build: the state directory is empty, so no workspace could be resolved safely")
	}
	if !filepath.IsAbs(o.StateDir) {
		return fmt.Errorf("build: the state directory %q is not absolute, and a relative one "+
			"resolves against whatever directory the daemon happens to have been started in",
			o.StateDir)
	}
	return nil
}

// Builder runs one build at a time per workload. Safe for concurrent use across workloads.
type Builder struct {
	engine   Engine
	store    Store
	uploads  Uploads
	sink     LogSink
	releases *Releases
	stateDir string
	log      *slog.Logger
	now      func() time.Time
	dev      bool

	// running serialises builds of one workload. Two of them would fight over the same
	// workspace and produce two releases from one checkout, and the second would be
	// whichever finished last rather than whichever the customer asked for.
	running sync.Map

	// isolation is what the engine said about itself, asked once and remembered. A build
	// is not a fifteen-second reconcile pass, so there is no cache to expire: a node that
	// gains runsc has been upgraded, and an upgrade restarts the daemon.
	isolation struct {
		sync.Mutex
		runtime string
		asked   bool
	}
}

// New opens a builder. Nothing is created on disk until a build runs.
func New(options Options) (*Builder, error) {
	if err := options.validate(); err != nil {
		return nil, err
	}

	builder := &Builder{
		engine:   options.Engine,
		store:    options.Store,
		uploads:  options.Uploads,
		sink:     options.Logs,
		releases: options.Releases,
		stateDir: options.StateDir,
		log:      options.Logger,
		now:      options.Now,
		dev:      options.Dev,
	}
	if builder.log == nil {
		builder.log = slog.Default()
	}
	if builder.now == nil {
		builder.now = time.Now
	}
	if builder.releases == nil {
		releases, err := NewReleases(options.StateDir, builder.log)
		if err != nil {
			return nil, err
		}
		builder.releases = releases
	}
	if builder.engine == nil {
		api, err := client.NewClientWithOpts(client.FromEnv, client.WithAPIVersionNegotiation())
		if err != nil {
			return nil, fmt.Errorf("build: prepare a Docker client: %w", err)
		}
		builder.engine = api
	}
	return builder, nil
}

// Sites is the release tree, for the reconcile loop to publish and roll back through.
//
// Handed out rather than duplicated: there is one `current` symlink per site and one piece
// of code that moves it, so a build and a reconcile pass racing over the same site cannot
// disagree about what "published" means.
func (b *Builder) Sites() *Releases { return b.releases }

// lockWorkload takes the per-workload build lock, waiting for the context rather than
// forever: a build queued behind one that is stuck must fail with its own timeout instead
// of holding the command goroutine until the daemon restarts.
func (b *Builder) lockWorkload(ctx context.Context, workloadID string) (func(), error) {
	value, _ := b.running.LoadOrStore(workloadID, make(chan struct{}, 1))
	gate, ok := value.(chan struct{})
	if !ok {
		return nil, fmt.Errorf("build: the lock for workload %s is not a lock", workloadID)
	}

	select {
	case gate <- struct{}{}:
		return func() { <-gate }, nil
	case <-ctx.Done():
		return nil, fmt.Errorf("build: waited for the build of workload %s that is already "+
			"running: %w", workloadID, ctx.Err())
	}
}

// buildRuntime is the OCI runtime a build container gets.
//
// gVisor when the engine has it, because a build runs somebody else's code with a network
// connection and a package manager that executes install scripts - which is the same
// threat a workload poses, arriving through a different door. runc when the node has no
// runsc, or under --dev, and in both cases what was really used is written to the build
// log rather than implied.
func (b *Builder) buildRuntime(ctx context.Context) string {
	b.isolation.Lock()
	defer b.isolation.Unlock()

	if b.isolation.asked {
		return b.isolation.runtime
	}
	b.isolation.runtime = "runc"
	if !b.dev {
		info, err := b.engine.Info(ctx, client.InfoOptions{})
		if err != nil {
			// Not fatal and not cached: the engine may answer next time, and refusing to
			// build because a capability query failed would turn a hiccup into an outage.
			b.log.Warn("could not ask the engine which runtimes it has; building under runc",
				slog.String("error", err.Error()))
			return b.isolation.runtime
		}
		if _, present := info.Info.Runtimes["runsc"]; present {
			b.isolation.runtime = "runsc"
		}
	}
	b.isolation.asked = true

	if b.isolation.runtime != "runsc" {
		b.log.Warn("builds on this node run under runc, not gVisor",
			slog.Bool("dev", b.dev))
	}
	return b.isolation.runtime
}
