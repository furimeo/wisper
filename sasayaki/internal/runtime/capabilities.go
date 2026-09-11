package runtime

import (
	"context"
	"fmt"
	"log/slog"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/moby/moby/client"
)

// How long the engine's own description of itself is trusted before it is asked again.
//
// It is asked again at all because gVisor can be installed on a running node: an operator
// edits /etc/docker/daemon.json, restarts Docker and reasonably expects the next
// container to be isolated, without also having to restart sasayaki. It is cached at all
// because the answer is consulted on every container created and every status reported,
// and an /info call per workload per pass is a needless conversation with the engine.
const capabilityTTL = 2 * time.Minute

// capability is what this node's engine can do, as far as it affects a container.
type capability struct {
	// hasRunsc is whether the engine has a runtime called "runsc" registered. Not
	// whether the binary is on $PATH: the engine is the one that has to find it, and an
	// installation that put the binary down and forgot the daemon.json entry looks
	// correct from every angle except this one (bootstrap/check_isolation.go).
	hasRunsc bool

	// cgroupVersion is "1" or "2" as the engine sees it. Only cgroups v2 enforces the
	// pids and memory ceilings the way the spec means them, and the engine's view can
	// differ from the kernel's when it is itself containerised.
	cgroupVersion string

	// userNamespaces is whether the daemon runs with --userns-remap. Root inside a
	// container is then nobody on the host, which is the difference between a container
	// escape being a nuisance and being a compromise. Reported, not required: turning it
	// on is a daemon-wide decision that renumbers every existing volume.
	userNamespaces bool

	// seccomp is whether the engine reports a seccomp profile at all. A daemon started
	// with --seccomp-profile=unconfined removes a whole layer from every container on
	// the machine and nothing else would say so.
	seccomp bool

	// initBinary is whether the engine has tini to run as pid 1. Asking for an init the
	// daemon does not have makes every container fail to create, so it is checked rather
	// than assumed.
	initBinary bool

	// runtimes is every OCI runtime the engine offers, sorted, for the log line that
	// explains a fallback.
	runtimes []string

	at time.Time
}

// capabilityCache holds one capability behind a mutex and a TTL.
type capabilityCache struct {
	sync.Mutex
	value  capability
	loaded bool
}

// capabilities returns the engine's description of itself, refreshing it when it is
// older than capabilityTTL.
//
// A refresh that fails does not throw the previous answer away. The engine going
// unreachable for thirty seconds must not turn every container on the node into one the
// daemon believes should run under runc: that would be drift invented by an outage, and
// the next pass would rebuild every container on the machine.
func (d *Docker) capabilities(ctx context.Context) (capability, error) {
	d.capability.Lock()
	defer d.capability.Unlock()

	if d.capability.loaded && d.now().Sub(d.capability.value.at) < capabilityTTL {
		return d.capability.value, nil
	}

	info, err := d.api.Info(ctx, client.InfoOptions{})
	if err != nil {
		if d.capability.loaded {
			d.log.Warn("could not refresh the engine's capabilities, keeping the last answer",
				slog.String("error", err.Error()),
				slog.Time("as_of", d.capability.value.at))
			return d.capability.value, nil
		}
		return capability{}, fmt.Errorf("runtime: ask the Docker engine what it can do: %w", err)
	}

	runtimes := make([]string, 0, len(info.Info.Runtimes))
	for name := range info.Info.Runtimes {
		runtimes = append(runtimes, name)
	}
	sort.Strings(runtimes)

	fresh := capability{
		hasRunsc:       contains(runtimes, "runsc"),
		cgroupVersion:  info.Info.CgroupVersion,
		userNamespaces: hasSecurityOption(info.Info.SecurityOptions, "userns"),
		seccomp:        hasSecurityOption(info.Info.SecurityOptions, "seccomp"),
		initBinary:     strings.TrimSpace(info.Info.InitBinary) != "",
		runtimes:       runtimes,
		at:             d.now(),
	}

	if !d.capability.loaded || d.capability.value.hasRunsc != fresh.hasRunsc {
		d.announce(fresh)
	}
	d.capability.value = fresh
	d.capability.loaded = true
	return fresh, nil
}

// announce says out loud what isolation this node is really going to give people. It is
// logged at warning level when gVisor is missing because "runs on runc" is a property an
// operator has to know about the machine, not a detail (design section 7.2).
func (d *Docker) announce(c capability) {
	switch {
	case d.dev:
		d.log.Warn("running every workload under runc because --dev was given: "+
			"this node provides kernel isolation only",
			slog.String("cgroups", c.cgroupVersion))
	case c.hasRunsc:
		d.log.Info("gVisor is available and will be the default runtime",
			slog.String("cgroups", c.cgroupVersion),
			slog.Bool("user_namespaces", c.userNamespaces))
	default:
		d.log.Warn("the engine has no runtime called runsc, so every workload here runs "+
			"under runc with kernel isolation only",
			slog.String("runtimes", strings.Join(c.runtimes, ", ")),
			slog.String("cgroups", c.cgroupVersion))
	}
	if c.cgroupVersion != "" && c.cgroupVersion != "2" {
		d.log.Warn("the engine reports cgroups v1, where the memory and pids ceilings in "+
			"a spec are not enforced the way the panel means them",
			slog.String("cgroups", c.cgroupVersion))
	}
	if !c.seccomp {
		d.log.Warn("the engine reports no seccomp profile, so containers here run with the " +
			"whole system call surface available; wisper never disables seccomp itself, " +
			"so this is a daemon-level setting somebody chose")
	}
	if !c.initBinary {
		d.log.Warn("the engine reports no init binary, so containers here run the image's " +
			"own process as pid 1 and will accumulate zombies if it does not reap them")
	}
}

// Isolation is what the panel is told about this node's containment, so the node page can
// say "kernel isolation only" instead of implying gVisor that is not there.
//
// Every field is a fact about the machine as it is now, not about what was asked for. A
// node that quietly provides less than the panel believes is the failure this whole
// package is arranged to prevent, and a struct nobody reads would recreate it, so this is
// carried in the heartbeat.
type Isolation struct {
	// Runsc is true when new containers really will run under gVisor.
	Runsc bool
	// CgroupVersion is "1" or "2" as the engine reports it. Only "2" enforces the memory
	// and pids ceilings the way the spec means them.
	CgroupVersion string
	// UserNamespaces is whether the daemon remaps container root to an unprivileged host
	// user.
	UserNamespaces bool
	// Seccomp is whether the engine applies a system call filter at all.
	Seccomp bool
	// EgressFiltered is false once any tenant network on this node has failed to get its
	// packet filter, which only happens in --dev.
	EgressFiltered bool
	// QuotaEnforceable is whether a disk ceiling on this node is applied by the
	// filesystem or merely recorded.
	QuotaEnforceable bool
}

// Isolation reports what containment this node actually provides right now.
func (d *Docker) Isolation(ctx context.Context) (Isolation, error) {
	capability, err := d.capabilities(ctx)
	if err != nil {
		return Isolation{}, err
	}
	return Isolation{
		Runsc:            capability.hasRunsc && !d.dev,
		CgroupVersion:    capability.cgroupVersion,
		UserNamespaces:   capability.userNamespaces,
		Seccomp:          capability.seccomp,
		EgressFiltered:   d.egress.Load(),
		QuotaEnforceable: d.QuotaEnforceable(),
	}, nil
}

// hasSecurityOption looks for a name in the engine's own list, whose entries look like
// "name=seccomp,profile=builtin" and "name=userns".
func hasSecurityOption(options []string, want string) bool {
	for _, option := range options {
		for _, field := range strings.Split(option, ",") {
			if strings.TrimSpace(field) == "name="+want {
				return true
			}
		}
	}
	return false
}

func contains(values []string, want string) bool {
	for _, value := range values {
		if value == want {
			return true
		}
	}
	return false
}
