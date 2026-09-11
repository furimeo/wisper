// Package runtime is everything sasayaki asks of the container engine.
//
// One boundary, one place. The reconcile loop, the terminal, the log feeds and the stats
// sampler all reach Docker through this package and nowhere else, so the decisions that
// make a customer's container safe to run are made once instead of at five call sites -
// and the one that matters most is spelled out at the top of limits.go: NanoCPUs is
// nano-CPUs, a hard ceiling, and not CPUShares multiplied by a thousand. The predecessor
// conflated the two and every workload got a relative scheduling weight where a limit was
// intended, so one busy container could take a whole machine while the panel showed it
// politely limited (design section 9).
//
// What this package guarantees about every container it creates:
//
//   - gVisor (runsc) unless the workload asked for runc or this node has no runsc
//     registered, and the runtime actually in effect is reported rather than implied;
//   - no-new-privileges, all capabilities dropped and a small named set added back,
//     the engine's seccomp profile and its masked /proc and /sys paths left in place;
//   - a hard CPU, memory, pids and file-descriptor ceiling from cgroups v2;
//   - a private network per tenant, with egress to private ranges and every cloud
//     metadata endpoint dropped while the public internet stays open (egress.go);
//   - mounts resolved from ids under the node's own state root, never from a path that
//     arrived over the network - which is what makes it impossible to ask for
//     /var/run/docker.sock (mounts.go).
//
// One file per concern, and the files that shape a container are deliberately separate
// from the ones that talk to the engine: containerconfig.go, hostconfig.go, hardening.go,
// mounts.go, ports.go and network.go are pure functions over a spec.Workload, so the
// security decisions above are unit-testable with no Docker anywhere. The engine
// interface in engine.go is the only thing a test has to stand in for.
package runtime

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"time"

	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// The contract with the reconcile loop, checked by the compiler.
//
// reconcile.Runtime is declared by its consumer, Go style, and implemented here. Asserting
// it costs nothing and is worth writing down: the loop is wired up in the composition root
// where a mismatch would surface as one confusing line about an interface, at daemon start
// rather than at build time.
var _ reconcile.Runtime = (*Docker)(nil)

// Docker is the engine connection, plus the small amount of state that has to outlive one
// call: which runtimes the engine offers, and which image pulls are in flight.
//
// Safe for concurrent use. The reconcile loop, a customer's terminal and the stats
// sampler all hold the same one.
type Docker struct {
	api      engine
	log      *slog.Logger
	stateDir string

	// dev is `sasayaki run --dev`: a developer's node rather than a customer's.
	//
	// It reduces isolation in exactly two ways, both of which are reported rather than
	// hidden. Everything runs under runc, because WSL2's kernel is not one gVisor
	// supports and a developer's node would otherwise fail to start a single container
	// (design section 13.5). And a packet filter that cannot be installed is a warning
	// instead of a refusal, because WSL2's iptables is frequently the wrong backend for
	// the running kernel. On a real node both of those are facts the panel is shown.
	dev bool

	// now is time.Now everywhere except in tests, which need to move the capability
	// cache and the image-pull cool-down without sleeping.
	now func() time.Time

	// run executes a host command with an argument vector. Only the firewall and the
	// quota tool use it, and it is a field so a test can prove which arguments they
	// would have used without needing iptables on the machine.
	run commandRunner

	// lifetime outlives any one caller's context. An image pull started by a reconcile
	// pass has to keep going after that pass returns - a cold two-gigabyte pull takes
	// minutes and a pass takes seconds - so the goroutine hangs off this instead, and
	// Close is what stops it.
	lifetime context.Context
	stop     context.CancelFunc

	capability capabilityCache
	pulls      pullTable
	quota      quotaState
	retention  retentionState
	egress     egressFiltered

	// Guards the tenant networks already verified in this process, so the firewall is
	// not re-read on every container created for a busy tenant.
	networks struct {
		sync.Mutex
		ready map[string]struct{}
	}
}

// Option adjusts a Docker at construction.
type Option func(*Docker)

// WithLogger replaces slog.Default. The daemon passes one that knows the node id.
func WithLogger(logger *slog.Logger) Option {
	return func(d *Docker) {
		if logger != nil {
			d.log = logger
		}
	}
}

// WithDevMode marks this as a developer's node: runc everywhere, and a packet filter
// that will not install is a warning rather than a refusal to start the workload.
//
// `sasayaki run --dev` sets it and nothing else should. Both effects are real reductions
// in isolation and both are reported - the runtime on every workload status, the
// filtering through Isolation - so a node in this mode cannot be mistaken for a safe one.
func WithDevMode(dev bool) Option {
	return func(d *Docker) { d.dev = dev }
}

// WithClock replaces time.Now, for tests that need the capability cache to expire.
func WithClock(clock func() time.Time) Option {
	return func(d *Docker) {
		if clock != nil {
			d.now = clock
		}
	}
}

// WithCommandRunner replaces exec.CommandContext for the firewall and the quota tool.
func WithCommandRunner(runner commandRunner) Option {
	return func(d *Docker) {
		if runner != nil {
			d.run = runner
		}
	}
}

// withEngine substitutes the Docker API. Unexported: the only caller is a test in this
// package, and an exported seam here would be an invitation to reach past the boundary
// this package exists to draw.
func withEngine(api engine) Option {
	return func(d *Docker) { d.api = api }
}

// New opens the engine connection and asks it what it can do.
//
// The Info call is not optional and not deferred. Which OCI runtimes are registered
// decides whether this node can honour the isolation the panel asked for, and finding
// that out lazily would mean the first container of the day is the one that discovers
// gVisor is missing - by which time it is already running with less isolation than
// anybody was told.
//
// stateDir is the node's state root, /var/lib/wisper. Every volume, site release and
// quota this package touches is resolved under it, and nothing outside it is ever a
// mount source.
func New(ctx context.Context, stateDir string, options ...Option) (*Docker, error) {
	if strings.TrimSpace(stateDir) == "" {
		return nil, errors.New("runtime: the state directory is empty, so no mount could be resolved safely")
	}

	lifetime, stop := context.WithCancel(context.WithoutCancel(ctx))
	docker := &Docker{
		log:      slog.Default(),
		stateDir: stateDir,
		now:      time.Now,
		run:      execRunner,
		lifetime: lifetime,
		stop:     stop,
	}
	docker.pulls.entries = make(map[string]*pull)
	docker.networks.ready = make(map[string]struct{})
	// True until something proves otherwise: a node that has created no tenant network
	// yet has filtered everything it was asked to.
	docker.egress.Store(true)
	for _, option := range options {
		option(docker)
	}

	if docker.api == nil {
		api, err := client.NewClientWithOpts(client.FromEnv, client.WithAPIVersionNegotiation())
		if err != nil {
			stop()
			return nil, fmt.Errorf("runtime: prepare a Docker client: %w", err)
		}
		docker.api = api
	}

	if _, err := docker.capabilities(ctx); err != nil {
		stop()
		docker.api.Close()
		return nil, err
	}
	return docker, nil
}

// Close releases the engine connection and abandons any image pull still running.
//
// Nothing is tidied up on the engine. sasayaki is crash-only: a container it created is
// meant to outlive it, and a daemon that stopped customers' workloads on the way out
// would make an upgrade an outage (AGENTS.md section 4.4).
func (d *Docker) Close() error {
	d.stop()
	if err := d.api.Close(); err != nil {
		return fmt.Errorf("runtime: close the Docker connection: %w", err)
	}
	return nil
}

// Reachable reports whether the engine is answering.
//
// The caller that matters is the heartbeat: a node whose Docker has gone away is
// degraded, not dead, and it must say so rather than going quiet or - far worse -
// concluding that the containers it can no longer see have ceased to exist
// (AGENTS.md section 4.5).
func (d *Docker) Reachable(ctx context.Context) error {
	if _, err := d.api.Ping(ctx, client.PingOptions{}); err != nil {
		return fmt.Errorf("runtime: the Docker engine did not answer: %w", err)
	}
	return nil
}

// runtimeFor is the runtime a workload will really get on this node.
//
// Three inputs and one honest answer. The workload asks for runsc or runc; the node may
// not have runsc registered with the engine; and --dev overrides both. Whatever comes out
// is what goes on the container and what is reported back, because a node quietly running
// with less isolation than the panel believes is the one failure mode gVisor was adopted
// to prevent (design section 7.2).
func (d *Docker) runtimeFor(ctx context.Context, wanted spec.Runtime) spec.Runtime {
	if wanted == spec.RuntimeRunc || d.dev {
		return spec.RuntimeRunc
	}
	capability, err := d.capabilities(ctx)
	if err != nil || !capability.hasRunsc {
		return spec.RuntimeRunc
	}
	return spec.RuntimeRunsc
}

// runtimeName is what the Engine API calls the runtime. An empty string means the
// engine's own default, which is runc on every installation this daemon supports; it is
// sent explicitly anyway so that `docker inspect` shows the decision rather than an
// absence somebody has to interpret.
func runtimeName(value spec.Runtime) string {
	if value == spec.RuntimeRunsc {
		return "runsc"
	}
	return "runc"
}
