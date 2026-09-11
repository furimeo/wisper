package daemon

import (
	"context"
	"fmt"
	"log/slog"
	"sync/atomic"

	"github.com/furimeo/wisper/sasayaki/internal/build"
	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/rpc"
	"github.com/furimeo/wisper/sasayaki/internal/stats"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The one cycle in the daemon's dependency graph, and the one place it is broken.
//
// rpc.New refuses a Handlers with a nil field, because a command the panel can send and
// nobody answers is a stub with extra steps. Three of those handlers - the reconcile loop,
// the builder, the sampler - are themselves built with the panel client as a collaborator:
// the loop reports statuses through it, the builder streams build output through it, the
// sampler pushes samples through it. Neither can be constructed first.
//
// The alternative to this shim is making three packages tolerate a nil panel and check for
// it on every call, which pushes a wiring problem into code that has real work to do. So
// the indirection lives here instead, in the only package that already knows about both
// ends, and it is exactly one pointer wide.
//
// After attach it is a straight forward with an atomic load in front. Before attach - a
// window of a few milliseconds during construction, in which nothing has been started and
// therefore nothing has anything to say - a send is refused rather than queued, and says so.

// panel is the node's view of its panel connection, resolved after the client exists.
type panel struct {
	// nodeID comes from the credential rather than from the client, because it is known
	// before the client is built and because it is what stamps a sample that is written to
	// the disk buffer during an outage.
	nodeID string
	log    *slog.Logger

	client atomic.Pointer[rpc.Client]
}

// The three interfaces this stands in for, asserted here rather than discovered at wiring
// time. Each is declared by its consumer, Go style; *rpc.Client satisfies all three, and
// this shim exists only to delay saying which one.
var (
	_ reconcile.Reporter = (*panel)(nil)
	_ stats.Uplink       = (*panel)(nil)
	_ build.LogSink      = (*panel)(nil)
)

func newPanel(nodeID string, log *slog.Logger) *panel {
	return &panel{nodeID: nodeID, log: log}
}

// attach publishes the real client. Called once, before anything is started.
func (p *panel) attach(client *rpc.Client) { p.client.Store(client) }

// NodeID is this node's id at the panel.
func (p *panel) NodeID() string { return p.nodeID }

// Connected reports whether a control stream is up right now.
func (p *panel) Connected() bool {
	if client := p.client.Load(); client != nil {
		return client.Connected()
	}
	return false
}

// SendStat queues one metric sample, and reports whether it was queued.
func (p *panel) SendStat(sample *wisperpb.StatSample) bool {
	if client := p.client.Load(); client != nil {
		return client.SendStat(sample)
	}
	return false
}

// SendLog queues one chunk of output for a subscription the panel started.
func (p *panel) SendLog(chunk *wisperpb.LogChunk) bool {
	if client := p.client.Load(); client != nil {
		return client.SendLog(chunk)
	}
	return false
}

// Emit sends an event the panel did not ask for. The client queues it while the panel is
// unreachable, so the reason a node went quiet arrives with it when it comes back.
func (p *panel) Emit(event *wisperpb.NodeEvent) {
	client := p.client.Load()
	if client == nil {
		// Unreachable in the assembled daemon: nothing that emits has been started yet. It
		// is said out loud rather than dropped silently, because an event that vanishes is
		// exactly the kind of thing nobody finds out about until they need it.
		p.log.Warn("an event was produced before the panel connection existed, and was dropped",
			slog.String("kind", event.GetKind().String()),
			slog.String("detail", event.GetDetail()))
		return
	}
	client.Emit(event)
}

// ReportStatus hands over what one reconcile pass observed.
func (p *panel) ReportStatus(ctx context.Context, batch *wisperpb.StatusBatch) (*wisperpb.Ack, error) {
	client := p.client.Load()
	if client == nil {
		return nil, fmt.Errorf("daemon: a status batch was produced before the panel connection " +
			"existed, which means a reconcile pass ran during construction")
	}
	return client.ReportStatus(ctx, batch)
}
