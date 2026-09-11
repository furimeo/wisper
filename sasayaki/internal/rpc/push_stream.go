package rpc

import (
	"context"
	"fmt"
	"log/slog"
	"sync/atomic"
	"time"

	"google.golang.org/grpc"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// How much is held for a panel that is away, per uplink. Both are lossy by design:
// samples and log chunks describe a moment, and a moment kept for ten minutes is not
// worth the memory it is sitting in.
const (
	statsQueueDepth = 1024
	logQueueDepth   = 512
)

// pushStream is one client-streaming uplink to the panel: stats or logs.
//
// The two are the same machine with different cargo, so they are one generic rather
// than two files that drift apart. What the machine does that is not obvious:
//
// It closes and reopens the stream every so often. PushStats and LogStream return a
// single Ack, delivered when the node half-closes, so a stream held open forever is a
// stream the panel can never throttle - and Ack.pause_seconds is the only throttle it
// has short of dropping the connection. Rotating is what makes that field mean
// something. Nothing already sent is delayed by it; only the acknowledgement is.
//
// It drops rather than blocks, and tells the caller it dropped. The caller is the
// package producing the data, which is the only one that can account for the loss in a
// way a customer can act on - LogChunk.dropped_bytes on the next chunk.
type pushStream[T any] struct {
	name   string
	client *Client
	open   func(ctx context.Context) (grpc.ClientStreamingClient[T, wisperpb.Ack], error)

	queue   chan *T
	dropped atomic.Uint64
}

// offer queues a message if there is room. It never blocks: the callers are a sampling
// ticker and a log reader attached to a customer's container, and a blocked log reader
// eventually blocks the container writing to it.
func (p *pushStream[T]) offer(message *T) bool {
	select {
	case p.queue <- message:
		return true
	default:
		p.dropped.Add(1)
		return false
	}
}

// run keeps the uplink going until the context is cancelled. It returns an error when
// the panel is unreachable, and the supervisor decides when to try again.
func (p *pushStream[T]) run(ctx context.Context) error {
	for {
		stream, err := p.open(ctx)
		if err != nil {
			return fmt.Errorf("open %s stream: %w", p.name, err)
		}

		pumpErr := p.pump(ctx, stream)

		// CloseAndRecv even after a failure: it is what turns a Send error into the
		// status the panel actually returned, which is the difference between "the
		// tunnel dropped" and "this credential is no longer valid".
		acknowledgement, closeErr := stream.CloseAndRecv()
		switch {
		case pumpErr != nil && ctx.Err() == nil:
			return fmt.Errorf("%s stream: %w", p.name, pumpErr)
		case ctx.Err() != nil:
			return ctx.Err()
		case closeErr != nil:
			return fmt.Errorf("close %s stream: %w", p.name, closeErr)
		}

		if dropped := p.dropped.Swap(0); dropped > 0 {
			p.client.log.Warn("dropped messages the panel could not keep up with",
				slog.String("uplink", p.name), slog.Uint64("dropped", dropped))
		}
		if pause := acknowledgement.GetPauseSeconds(); pause > 0 {
			p.client.log.Info("panel asked for a pause",
				slog.String("uplink", p.name), slog.Int64("seconds", pause))
			if err := wait(ctx, time.Duration(pause)*time.Second); err != nil {
				return err
			}
		}
	}
}

// pump sends until it is time to rotate, the context ends, or the stream refuses.
//
// The rotation timer is only armed once something has been sent. An idle node would
// otherwise open and close an empty RPC every thirty seconds forever, which is traffic
// through somebody's tunnel in exchange for nothing.
func (p *pushStream[T]) pump(ctx context.Context, stream grpc.ClientStreamingClient[T, wisperpb.Ack]) error {
	var timer *time.Timer
	var rotate <-chan time.Time
	defer func() {
		if timer != nil {
			timer.Stop()
		}
	}()

	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-rotate:
			return nil
		case message := <-p.queue:
			if err := stream.Send(message); err != nil {
				return err
			}
			if timer == nil {
				timer = time.NewTimer(p.client.rotation)
				rotate = timer.C
			}
		}
	}
}

// newStatsUplink is PushStats: one sample of the machine or of a workload at a time.
func newStatsUplink(client *Client) *pushStream[wisperpb.StatSample] {
	return &pushStream[wisperpb.StatSample]{
		name:   "stats",
		client: client,
		queue:  make(chan *wisperpb.StatSample, statsQueueDepth),
		open: func(ctx context.Context) (grpc.ClientStreamingClient[wisperpb.StatSample, wisperpb.Ack], error) {
			return client.panel.PushStats(ctx)
		},
	}
}

// newLogUplink is LogStream: every subscription the panel has started, multiplexed by
// stream_id onto one call.
func newLogUplink(client *Client) *pushStream[wisperpb.LogChunk] {
	return &pushStream[wisperpb.LogChunk]{
		name:   "logs",
		client: client,
		queue:  make(chan *wisperpb.LogChunk, logQueueDepth),
		open: func(ctx context.Context) (grpc.ClientStreamingClient[wisperpb.LogChunk, wisperpb.Ack], error) {
			return client.panel.LogStream(ctx)
		},
	}
}
