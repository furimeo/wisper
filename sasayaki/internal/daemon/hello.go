package daemon

import (
	"context"
	"log/slog"

	"github.com/furimeo/wisper/sasayaki/internal/bootstrap"
	"github.com/furimeo/wisper/sasayaki/internal/rpc"
	"github.com/furimeo/wisper/sasayaki/internal/runtime"
	"github.com/furimeo/wisper/sasayaki/internal/version"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Describing this machine to the panel, from scratch, every time it is asked.
//
// Nothing here is cached, and that is the whole design of it. NodeHello is sent on every
// reconnect rather than only at startup because a node is a machine somebody administers:
// RAM gets added, a disk gets replaced with a smaller one, runsc gets uninstalled during an
// unrelated upgrade. A panel placing workloads against facts gathered a week ago places
// them onto a machine that cannot hold them, and a cached struct is how that happens.
//
// It is also why an unhealthy node still connects. A doctor check that failed is content
// for the report, not an error: the panel needs to be told about a node with no runsc, and
// a node that refuses to connect because it is unhealthy is a node nobody can see is
// unhealthy (rpc/handlers.go, HelloSource).

// generations is the applied generation, as the handshake needs it: one number, read off
// the disk before Docker has been asked anything, so a node reconnecting during a slow
// start still tells the panel where it really is.
type generations interface {
	AppliedGeneration(ctx context.Context) (uint64, error)
}

// containment is what the container engine really provides right now, which is not always
// what the doctor's own probes concluded - see machineFacts below.
type containment interface {
	Isolation(ctx context.Context) (runtime.Isolation, error)
	QuotaEnforceable() bool
}

// capacities is the sampler's published view. Never blocks and never nil.
type capacities interface {
	Capacity() *wisperpb.Capacity
}

// nodeDescription is the daemon's rpc.HelloSource.
type nodeDescription struct {
	generations generations
	engine      containment
	capacity    capacities
	edgeRunning func() bool
	log         *slog.Logger

	// stateDir and panelEndpoint are what a preflight run cannot work out for itself: which
	// filesystem decides whether disk quotas are enforceable, and where to measure the
	// clock against.
	stateDir      string
	panelEndpoint string

	// preflight is bootstrap.Preflight everywhere except in a test, which cannot run a real
	// one: half its checks bind ports and the other half read /proc.
	preflight func(context.Context, bootstrap.PreflightOptions) *wisperpb.DoctorReport
}

var _ rpc.HelloSource = nodeDescription{}

// Hello is everything the panel needs to decide whether to keep the stream.
//
// protocol_version and fresh_start are deliberately absent: they belong to the connection
// rather than to the node, and rpc stamps them so that no implementation of this interface
// can get them wrong.
func (n nodeDescription) Hello(ctx context.Context) (*wisperpb.NodeHello, error) {
	edgeRunning := false
	if n.edgeRunning != nil {
		edgeRunning = n.edgeRunning()
	}
	report := n.preflight(ctx, bootstrap.PreflightOptions{
		StateDir:    n.stateDir,
		Panel:       n.panelEndpoint,
		EdgeRunning: edgeRunning,
	})

	return &wisperpb.NodeHello{
		AgentVersion:      version.Number,
		AgentCommit:       version.Commit,
		AppliedGeneration: n.appliedGeneration(ctx),
		Machine:           n.machineFacts(ctx, report.GetMachine()),
		Doctor:            report,
	}, nil
}

// appliedGeneration is what this node has actually converged to.
//
// A read that fails answers zero rather than failing the handshake. Zero makes the panel
// resend the whole spec, which it was going to do on this reconnect anyway - the cost is
// one message. Refusing to connect because SQLite was busy costs the node its management
// plane for as long as the condition lasts.
func (n nodeDescription) appliedGeneration(ctx context.Context) uint64 {
	generation, err := n.generations.AppliedGeneration(ctx)
	if err != nil {
		n.log.Warn("could not read the applied generation for the handshake, so the panel will "+
			"be told nothing has been applied and will resend the whole spec",
			slog.String("error", err.Error()))
		return 0
	}
	return generation
}

// machineFacts is the doctor's description of the machine, corrected where the running
// daemon knows better.
//
// Three corrections, and each one exists because the preflight probe and the daemon can
// legitimately disagree:
//
//   - runsc. The probe asks whether the engine has a runtime called runsc registered. The
//     daemon knows whether a container created right now would actually get it, which is
//     also false under --dev. What the panel must be shown is the second one, because a
//     node quietly providing less isolation than the panel believes is the one failure
//     mode gVisor was adopted to prevent (design section 7.2).
//   - project quotas. The runtime is the thing that applies them, so its answer is the one
//     that decides whether a customer's disk limit is real or decorative.
//   - the capacity figures. The sampler measures the same filesystem every twelve seconds
//     and the panel's node page is drawn from its numbers; using them here means the two
//     halves of that page cannot show different totals. They are also the fallback for a
//     probe that could not read the disk at all.
func (n nodeDescription) machineFacts(ctx context.Context, facts *wisperpb.MachineFacts) *wisperpb.MachineFacts {
	if facts == nil {
		facts = &wisperpb.MachineFacts{}
	}

	if isolation, err := n.engine.Isolation(ctx); err != nil {
		// The engine did not answer. The doctor's own findings stay exactly as they are:
		// not being able to ask is not a fact about this machine's isolation, and reporting
		// "no runsc" because of a socket timeout would have the panel mark a perfectly well
		// isolated node as unsafe (AGENTS.md section 4.5).
		n.log.Warn("could not ask the container engine what isolation it provides; the handshake "+
			"carries the preflight report's own findings",
			slog.String("error", err.Error()))
	} else {
		facts.RunscAvailable = isolation.Runsc
		facts.CgroupsV2 = isolation.CgroupVersion == "2"
	}
	facts.ProjectQuotaSupported = n.engine.QuotaEnforceable()

	capacity := n.capacity.Capacity()
	if cores := capacity.GetNanoCpusTotal() / nanoCPUsPerCore; cores > 0 {
		facts.CpuCores = int32(cores)
	}
	if total := capacity.GetMemoryBytesTotal(); total > 0 {
		facts.MemoryBytes = total
	}
	if total := capacity.GetDiskBytesTotal(); total > 0 {
		facts.DiskTotalBytes = total
		facts.DiskFreeBytes = total - capacity.GetDiskBytesUsed()
	}

	if n.edgeRunning != nil && n.edgeRunning() {
		facts.Port_80Free = true
		facts.Port_443Free = true
	}

	return facts
}

// nanoCPUsPerCore is the unit Capacity counts in: one core is 1e9 nano-CPUs, the same unit
// as ResourceLimits.nano_cpus, so nothing on either side of the wire converts.
const nanoCPUsPerCore = 1_000_000_000
