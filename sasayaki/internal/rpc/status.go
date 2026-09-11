package rpc

import (
	"context"
	"fmt"
	"time"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// ReportStatus hands over what one reconcile pass observed and returns the panel's
// acknowledgement, whose request_status tells the caller whether a full batch is wanted
// again immediately.
//
// It retries briefly rather than tearing anything down: this is a unary call precisely
// so a panel that is restarting costs a few seconds instead of the control stream and
// every command queued on it.
func (c *Client) ReportStatus(ctx context.Context, batch *wisperpb.StatusBatch) (*wisperpb.Ack, error) {
	if batch == nil {
		return nil, fmt.Errorf("report status: nothing to report")
	}
	if batch.NodeId == "" {
		batch.NodeId = c.credential.NodeID
	}
	if batch.ObservedAt == nil {
		batch.ObservedAt = timestamppb.Now()
	}

	// Three attempts over about three seconds. Longer than that and this call starts
	// overlapping the next reconcile pass, whose batch is fresher than this one anyway.
	const attempts = 3
	var lastErr error
	for attempt := range attempts {
		if attempt > 0 {
			if err := wait(ctx, time.Duration(attempt)*time.Second); err != nil {
				return nil, err
			}
		}
		acknowledgement, err := c.panel.ReportStatus(ctx, batch)
		if err == nil {
			return acknowledgement, nil
		}
		lastErr = err
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
	}
	return nil, fmt.Errorf("report status after %d attempts: %w", attempts, lastErr)
}
