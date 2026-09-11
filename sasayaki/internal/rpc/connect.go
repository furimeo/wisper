package rpc

import (
	"context"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/version"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Heartbeat bounds. The panel names the interval in its hello; these keep a mistake at
// either end from turning into a flood or into a node that looks dead for an hour.
const (
	minHeartbeatInterval     = time.Second
	maxHeartbeatInterval     = 10 * time.Minute
	defaultHeartbeatInterval = 20 * time.Second
)

// ProtocolMismatch is the handshake failing on purpose.
//
// The alternative - carrying on with a peer that speaks a different version - is the
// failure this negotiation exists to prevent: two sides that each parse most of what
// the other sends and quietly misread the rest (design section 7.5).
type ProtocolMismatch struct {
	Node  uint32
	Panel uint32
}

func (e *ProtocolMismatch) Error() string {
	return fmt.Sprintf("protocol mismatch: this node speaks %d, the panel speaks %d. "+
		"One of the two needs upgrading; the stream stays closed until then", e.Node, e.Panel)
}

// session is one accepted control stream: everything between a completed handshake and
// the moment the stream drops.
type session struct {
	client *Client
	stream grpc.BidiStreamingClient[wisperpb.NodeMessage, wisperpb.PanelMessage]
}

// connectOnce runs one control stream from the handshake to the drop. It returns the
// reason the stream ended; the supervisor decides how long to wait before the next one.
func (c *Client) connectOnce(ctx context.Context) error {
	fresh := c.connections.Load() == 0

	hello, err := c.handlers.Hello.Hello(ctx)
	if err != nil {
		return fmt.Errorf("describe this node: %w", err)
	}
	if hello == nil {
		return fmt.Errorf("describe this node: nothing to say")
	}
	// These three belong to the connection, not to the node, so they are stamped here
	// and an implementation of HelloSource cannot get them wrong.
	hello.ProtocolVersion = uint32(version.Protocol)
	hello.FreshStart = fresh
	if hello.AgentVersion == "" {
		hello.AgentVersion = version.Number
	}
	if hello.AgentCommit == "" {
		hello.AgentCommit = version.Commit
	}

	// Cancelling this is what tears the stream down on the way out, including the
	// heartbeat goroutine writing to it.
	streamCtx, endStream := context.WithCancel(ctx)
	defer endStream()

	stream, err := c.panel.Connect(streamCtx)
	if err != nil {
		return fmt.Errorf("open control stream: %w", err)
	}
	if err := stream.Send(&wisperpb.NodeMessage{
		Payload: &wisperpb.NodeMessage_Hello{Hello: hello},
	}); err != nil {
		return fmt.Errorf("send hello: %w", err)
	}

	first, err := stream.Recv()
	if err != nil {
		return fmt.Errorf("await panel hello: %w", err)
	}
	panelHello := first.GetHello()
	if panelHello == nil {
		return fmt.Errorf("panel answered the handshake with %T instead of a hello", first.GetPayload())
	}
	if panelHello.GetProtocolVersion() != uint32(version.Protocol) {
		return &ProtocolMismatch{Node: uint32(version.Protocol), Panel: panelHello.GetProtocolVersion()}
	}

	live := &session{client: c, stream: stream}
	c.connections.Add(1)
	c.publish(live)
	defer c.retire(live)

	c.log.Info("control stream up",
		slog.String("panel_version", panelHello.GetPanelVersion()),
		slog.Uint64("applied_generation", hello.GetAppliedGeneration()),
		slog.Uint64("panel_generation", panelHello.GetCurrentGeneration()),
		slog.Int64("reconcile_interval_seconds", panelHello.GetReconcileIntervalSeconds()),
		slog.Bool("fresh_start", fresh))

	if fresh {
		// The panel resends the whole spec on any reconnect, so this is not how it
		// learns to do that; it is how an operator sees a node that is restarting in a
		// loop rather than one that is merely slow.
		c.Emit(&wisperpb.NodeEvent{
			Kind:     wisperpb.NodeEventKind_NODE_EVENT_KIND_DAEMON_STARTED,
			Severity: wisperpb.EventSeverity_EVENT_SEVERITY_INFO,
			Detail:   fmt.Sprintf("sasayaki %s started", version.Short()),
		})
	}

	var beating sync.WaitGroup
	beating.Add(1)
	go func() {
		defer beating.Done()
		c.beat(streamCtx, heartbeatInterval(panelHello))
	}()
	defer beating.Wait()
	defer endStream()

	return c.readFrames(ctx, live)
}

// readFrames is the control stream's read loop, and the only place a PanelMessage is
// turned into work.
func (c *Client) readFrames(ctx context.Context, live *session) error {
	for {
		message, err := live.stream.Recv()
		if err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			return fmt.Errorf("control stream ended: %w", err)
		}
		live.dispatch(ctx, message)
	}
}

// beat sends a heartbeat on the interval the panel asked for.
//
// On its own goroutine because the read loop can be busy storing a spec, and a node
// whose liveness signal waits behind a disk write looks dead to a panel that is only
// counting the gaps.
func (c *Client) beat(ctx context.Context, every time.Duration) {
	ticker := time.NewTicker(every)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			heartbeat := c.handlers.Heartbeat.Heartbeat(ctx)
			if heartbeat == nil {
				// A node that cannot describe itself is still a node that is alive, and
				// silence would be read as the opposite. Say so instead.
				heartbeat = &wisperpb.Heartbeat{
					Health:       wisperpb.NodeHealth_NODE_HEALTH_DEGRADED,
					HealthDetail: "the node could not assemble a heartbeat",
				}
			}
			heartbeat.SentAt = timestamppb.Now()
			c.send(&wisperpb.NodeMessage{
				Payload: &wisperpb.NodeMessage_Heartbeat{Heartbeat: heartbeat},
			})
		}
	}
}

func heartbeatInterval(hello *wisperpb.PanelHello) time.Duration {
	seconds := hello.GetHeartbeatIntervalSeconds()
	if seconds <= 0 {
		return defaultHeartbeatInterval
	}
	interval := time.Duration(seconds) * time.Second
	return min(max(interval, minHeartbeatInterval), maxHeartbeatInterval)
}
