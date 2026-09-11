package rpc

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"time"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// A stream that lasted this long counts as having worked, so the next failure starts at
// the bottom of the schedule again. Without it, a node whose tunnel drops once an hour
// would be waiting a full minute every time, having learned nothing from an hour of
// success.
const healthyStreamLife = 30 * time.Second

// Run keeps the node connected until the context is cancelled.
//
// Four streams, each supervised separately: the control stream, the file operation
// stream the panel drives, and the two uplinks carrying stats and logs. Separately
// because they fail for different reasons - a file transfer hitting a message size
// limit must not take down heartbeats - and because a node whose stats stream is
// broken is still a node that has to be managed.
//
// It returns only when the context ends. There is no failure this reports by giving up:
// the panel being unreachable is a state the node is expected to sit in indefinitely,
// still serving customers, still reconciling from the spec on disk (design section
// 7.6).
func (c *Client) Run(ctx context.Context) error {
	streams := []struct {
		name    string
		attempt func(context.Context) error
	}{
		{"control", c.connectOnce},
		{"files", c.runFileOps},
		{"stats", c.stats.run},
		{"logs", c.logs.run},
	}

	var running sync.WaitGroup
	for _, stream := range streams {
		running.Add(1)
		go func() {
			defer running.Done()
			c.supervise(ctx, stream.name, stream.attempt)
		}()
	}
	running.Wait()

	return ctx.Err()
}

// supervise runs one stream forever, waiting between attempts.
func (c *Client) supervise(ctx context.Context, name string, attempt func(context.Context) error) {
	clock := newRetryClock(c.backoff)
	log := c.log.With(slog.String("stream", name))

	for {
		if ctx.Err() != nil {
			return
		}

		started := time.Now()
		err := attempt(ctx)
		if ctx.Err() != nil {
			return
		}
		if time.Since(started) >= healthyStreamLife {
			clock.reset()
		}

		c.explain(log, clock, err)

		delay := clock.next()
		log.Debug("reconnecting", slog.Duration("in", delay))
		if wait(ctx, delay) != nil {
			return
		}
	}
}

// explain writes the one log line an operator reads when a node is not connected, and
// decides whether this is a failure worth hurrying back from.
//
// The three that are not: an incompatible protocol, a credential the panel refuses and
// a certificate that changed. None of them fixes itself in a second, and retrying every
// second would bury the line that says what is actually wrong. They still retry - a
// panel restoring from a backup, or an operator part-way through an upgrade, both come
// back - but from the top of the schedule.
func (c *Client) explain(log *slog.Logger, clock *retryClock, err error) {
	var mismatch *ProtocolMismatch

	// Taken first: a refused certificate arrives as an ordinary connection failure, and
	// reading it as "the tunnel is down" is exactly the wrong conclusion. Swapped out
	// so one mismatch produces one line rather than one per supervised stream.
	if changed := c.pinFailure.Swap(nil); changed != nil {
		log.Error("refusing to connect: the panel's certificate is not the one this node pinned",
			slog.String("pinned", changed.Expected),
			slog.String("presented", changed.Observed),
			slog.String("meaning", "either the panel's certificate was replaced, in which case re-enrol "+
				"this node, or something is terminating TLS in the middle"))
		clock.penalise()
		return
	}

	switch {
	case err == nil:
		log.Info("stream closed cleanly")

	case errors.As(err, &mismatch):
		log.Error("this node needs upgrading before the panel will talk to it",
			slog.Uint64("node_protocol", uint64(mismatch.Node)),
			slog.Uint64("panel_protocol", uint64(mismatch.Panel)))
		clock.penalise()

	case isRefusedByPanel(err):
		log.Error("the panel refused this node",
			slog.String("error", err.Error()),
			slog.String("meaning", "the credential is not accepted, or this node is suspended"))
		clock.penalise()

	case isUnavailable(err):
		// The ordinary case, and the reason this package exists. Not a warning: the
		// panel is behind a tunnel and a tunnel drops.
		log.Info("panel unreachable", slog.String("error", err.Error()))

	default:
		log.Warn("stream failed", slog.String("error", err.Error()))
	}
}

// isRefusedByPanel is the panel saying no rather than the network saying nothing.
func isRefusedByPanel(err error) bool {
	reported, ok := status.FromError(err)
	if !ok {
		return false
	}
	switch reported.Code() {
	case codes.Unauthenticated, codes.PermissionDenied:
		// The credential is wrong, revoked, or this node is suspended.
		return true
	case codes.FailedPrecondition:
		// What the panel answers a protocol version it cannot speak with, before it has
		// parsed a frame.
		return true
	default:
		return false
	}
}

func isUnavailable(err error) bool {
	reported, ok := status.FromError(err)
	if !ok {
		return false
	}
	switch reported.Code() {
	case codes.Unavailable, codes.DeadlineExceeded, codes.Canceled:
		return true
	default:
		return false
	}
}
