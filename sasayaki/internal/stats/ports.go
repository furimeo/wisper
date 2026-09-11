package stats

import (
	"context"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/rpc"
	"github.com/furimeo/wisper/sasayaki/internal/runtime"
	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// What this package needs from the rest of the daemon, declared here by the consumer.
//
// Three collaborators, and each one is behind an interface for a reason that is specific
// rather than habitual. The engine, because the behaviour worth testing here is what
// happens to a counter when a container is restarted underneath the sampler, and arranging
// that against a real Docker daemon means killing a customer's container from inside a unit
// test. The panel, because the behaviour worth testing is what happens over an hour of it
// being unreachable. The spec, because allocation is read from it and a node's promises are
// what admission is mostly about.
//
// The filesystem and /proc are not abstracted behind interfaces: they are read through a
// root path and a function, so a test points them at a temporary directory and fixtures
// (options.go). A fake procfs interface would prove that this package can parse a fake.

// Engine is the container engine, as sampling needs it.
//
// Two calls, in this order, every pass: list what is here, then read each one. The list is
// what carries the workload id and the moment the container was started, and the second is
// what makes a restart visible at all - a counter that reset is indistinguishable from a
// counter that went quiet unless you know the container is not the same one.
//
// An error from Containers means the engine could not be asked. It never means the machine
// is empty, and nothing in this package concludes otherwise: a pass that cannot list keeps
// every remembered counter, so the reading after the engine comes back is a difference
// against the last real one rather than a fresh baseline (AGENTS.md section 4.5).
type Engine interface {
	Containers(ctx context.Context) ([]reconcile.Container, error)

	// Sample reads one container's cgroup counters. A container that exited between the
	// list and this call is reported as an error and skipped for the pass, which is a gap
	// in a chart and nothing worse.
	Sample(ctx context.Context, containerID string) (runtime.Sample, error)
}

// Specs is the desired state, as capacity needs it.
//
// Only for what has been promised. Placement needs the limits of everything placed here and
// not just what is being used - an idle workload still holds its ceiling, and packing a node
// against live usage is how it dies at the first spike (node.proto, Capacity).
//
// state.ErrNoSpec means the panel has never spoken to this node, which is answered with an
// allocation of zero rather than a failure: a node with no spec has promised nothing.
type Specs interface {
	LoadSpec(ctx context.Context) (state.StoredSpec, error)
}

// Uplink is the panel, as sampling needs it.
type Uplink interface {
	// SendStat queues one sample and reports whether it was queued. False means the uplink's
	// own queue is full, which is answered here by writing the sample to the buffer instead
	// of dropping it.
	SendStat(sample *wisperpb.StatSample) bool

	// Connected reports whether a control stream is up right now. Not a guarantee of
	// delivery - nothing here is - but the difference between a node that is talking to a
	// panel and one that is not, which is what decides whether a pass drains the buffer or
	// fills it.
	Connected() bool

	// Emit reports a transition no neighbouring heartbeat would show. Used for exactly one
	// thing here: the disk crossing the mark past which this node stops accepting
	// deployments (NODE_EVENT_KIND_DISK_CRITICAL).
	Emit(event *wisperpb.NodeEvent)

	// NodeID stamps every sample, including the ones written to the buffer, so a row that
	// sat on disk through an outage still says which node it describes.
	NodeID() string
}

// The one implementation of each, asserted here rather than discovered in the composition
// root. The interfaces are written in the other packages' own signatures precisely so that
// no adapter is needed; these lines are what keep that true when either side moves.
var (
	_ Engine = (*runtime.Docker)(nil)
	_ Specs  = (*state.Store)(nil)
	_ Uplink = (*rpc.Client)(nil)
)
