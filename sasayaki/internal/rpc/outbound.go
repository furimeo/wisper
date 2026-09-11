package rpc

import (
	"log/slog"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The write path to the panel.
//
// Everything the node says goes through here, and it is one mutex wide on purpose: a
// gRPC stream is not safe for concurrent Send, and two frames half-written into each
// other is a stream the panel has to drop. The queue behind that mutex is what makes a
// dropped stream ordinary rather than lossy - the answer to a command whose connection
// died is delivered on the next one.

// Emit sends an event the panel did not ask for: Docker went away, a certificate was
// issued, the disk is nearly full. Events are queued while the panel is unreachable, so
// the reason a node went quiet arrives with it when it comes back.
func (c *Client) Emit(event *wisperpb.NodeEvent) {
	if event == nil {
		return
	}
	if event.At == nil {
		event.At = timestamppb.Now()
	}
	c.send(&wisperpb.NodeMessage{Payload: &wisperpb.NodeMessage_Event{Event: event}})
}

// SendStat queues one metric sample. Sampling is lossy on purpose: a sample dropped
// under load is a gap in a chart, while a sample that blocks the reconcile loop is an
// outage. Reports whether it was queued.
func (c *Client) SendStat(sample *wisperpb.StatSample) bool {
	if sample == nil {
		return false
	}
	if sample.NodeId == "" {
		sample.NodeId = c.credential.NodeID
	}
	return c.stats.offer(sample)
}

// SendLog queues one chunk of log output for a subscription the panel started. Lossy
// for the same reason, and the node says so: LogChunk.dropped_bytes is how a customer
// finds out the gap is real rather than the application having gone quiet.
func (c *Client) SendLog(chunk *wisperpb.LogChunk) bool {
	if chunk == nil {
		return false
	}
	return c.logs.offer(chunk)
}

// send puts a frame on the control stream, or holds it until there is one.
//
// Holding rather than failing is the whole difference between "the tunnel blinked" and
// "the panel never found out what happened to that backup". The queue is bounded and
// drops its oldest entry, because a node that has been away for an hour has nothing
// worth saying about the first minute of it.
func (c *Client) send(message *wisperpb.NodeMessage) {
	c.outbound.Lock()
	defer c.outbound.Unlock()

	if live := c.current.Load(); live != nil {
		if err := live.stream.Send(message); err == nil {
			return
		}
		// The stream died between the last frame and this one. Queue it: the next
		// session flushes, and the panel correlates by command id whenever it arrives.
		c.log.Debug("control stream refused a frame, queueing it for the next one")
	}

	if len(c.pending) >= c.queueDepth {
		dropped := len(c.pending) - c.queueDepth + 1
		c.pending = c.pending[dropped:]
		c.log.Warn("dropping queued frames for an unreachable panel",
			slog.Int("dropped", dropped), slog.Int("queue_depth", c.queueDepth))
	}
	c.pending = append(c.pending, message)
}

// publish makes a freshly accepted session the one everything writes to, and delivers
// what was waiting for it.
//
// Both under one lock, so a frame produced while the queue is draining cannot overtake
// what was already in it.
func (c *Client) publish(live *session) {
	c.outbound.Lock()
	defer c.outbound.Unlock()

	c.current.Store(live)
	if len(c.pending) == 0 {
		return
	}

	queued := c.pending
	c.pending = nil
	c.log.Info("delivering frames held while the panel was unreachable", slog.Int("frames", len(queued)))

	for index, message := range queued {
		if err := live.stream.Send(message); err != nil {
			// Still no good. Keep the rest rather than losing them a second time.
			c.pending = append(c.pending, queued[index:]...)
			return
		}
	}
}

// retire clears the published session, so send starts queueing again.
func (c *Client) retire(live *session) {
	c.outbound.Lock()
	defer c.outbound.Unlock()
	if c.current.Load() == live {
		c.current.Store(nil)
	}
}
