package stats

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Getting a pass's samples to the panel, or keeping them until there is one.
//
// The order is deliberate: what has been waiting goes first, then what has just been
// measured. A chart drawn from samples that arrived out of order is a chart with the outage
// in the wrong place.

// publish hands one pass's samples over, buffering whatever cannot be delivered.
func (s *Sampler) publish(ctx context.Context, result pass) {
	if !s.uplink.Connected() {
		s.hold(ctx, result.Samples)
		return
	}

	s.drain(ctx)

	for index, sample := range result.Samples {
		if !s.uplink.SendStat(sample) {
			// The uplink's queue is full, which means the panel is not keeping up rather
			// than being away. Everything from here on goes to disk in one write; offering
			// the rest one at a time would only find the same full queue.
			s.hold(ctx, result.Samples[index:])
			return
		}
	}
}

// hold writes samples to the ring.
//
// A failure here is logged and nothing more. The buffer is a convenience - it turns a
// two-minute tunnel wobble into a chart with no gap - and a node that stopped sampling
// because it could not write its cache would have traded the thing for its optimisation.
func (s *Sampler) hold(ctx context.Context, samples []*wisperpb.StatSample) {
	if len(samples) == 0 {
		return
	}
	if err := s.buffer.append(ctx, samples); err != nil {
		s.reportOnce("buffer", err)
		return
	}
	s.recovered("buffer")
}

// drain sends what has been waiting, oldest first, up to a bounded number per pass.
//
// Bounded because a node that has been away for an hour has thousands of samples on disk and
// the uplink's own queue holds a thousand: emptying the ring in one go would fill that queue,
// have it drop the fresh samples this pass just took, and put them back on the disk the
// drain was supposed to be clearing. A few hundred a pass catches up in a couple of minutes
// and never displaces the present with the past.
func (s *Sampler) drain(ctx context.Context) {
	waiting, err := s.buffer.take(ctx, s.drainPerPass)
	if err != nil {
		s.reportOnce("buffer", err)
		return
	}
	if len(waiting) == 0 {
		return
	}

	delivered := make([]int64, 0, len(waiting))
	for _, entry := range waiting {
		if !s.uplink.SendStat(entry.sample) {
			break
		}
		delivered = append(delivered, entry.id)
	}

	// Only what was accepted is removed. A row deleted before the uplink took it is a row
	// lost to the disconnection that was about to happen; a row delivered twice folds into
	// the same rollup bucket at the panel, which is the cheaper mistake by a wide margin.
	if err := s.buffer.discard(ctx, delivered); err != nil {
		s.reportOnce("buffer", err)
		return
	}
	s.recovered("buffer")
	s.log.Debug("delivered samples held while the panel was unreachable",
		slog.Int("delivered", len(delivered)), slog.Int("attempted", len(waiting)))
}

// announce tells the panel that the disk crossed a mark, and tells it once.
//
// Only the critical transitions produce an event. Entering the warning band changes nothing
// the panel cannot see in the capacity figures it already receives every heartbeat, and an
// event for it would be one more thing to filter out before finding the one that matters.
func (s *Sampler) announce(pressure Pressure, total, available int64, at time.Time) {
	switch pressure {
	case PressureCritical:
		detail := fmt.Sprintf("the disk under %s is %.1f%% full with %s free: this node has stopped "+
			"accepting new deployments and new workloads until it has room again",
			s.stateDir, fraction(total-available, total)*100, megabytes(available))
		s.log.Error("this node is out of disk headroom", slog.String("detail", detail))
		s.uplink.Emit(&wisperpb.NodeEvent{
			Kind:     wisperpb.NodeEventKind_NODE_EVENT_KIND_DISK_CRITICAL,
			Severity: wisperpb.EventSeverity_EVENT_SEVERITY_CRITICAL,
			Detail:   detail,
			At:       timestamppb.New(at),
		})

	case PressureWarning:
		s.log.Warn("this node is running short of disk",
			slog.String("state_dir", s.stateDir),
			slog.String("free", megabytes(available)),
			slog.String("meaning", "deployments are still accepted; they stop being accepted if it fills further"))

	case PressureNone:
		detail := fmt.Sprintf("the disk under %s is back to %.1f%% full with %s free: this node is "+
			"accepting deployments again", s.stateDir, fraction(total-available, total)*100, megabytes(available))
		s.log.Info("disk headroom recovered", slog.String("detail", detail))
		// The same kind at a different severity, because there is one disk event kind and
		// the panel needs the transition in both directions: a node that never says it
		// recovered is a node an operator has to go and check by hand.
		s.uplink.Emit(&wisperpb.NodeEvent{
			Kind:     wisperpb.NodeEventKind_NODE_EVENT_KIND_DISK_CRITICAL,
			Severity: wisperpb.EventSeverity_EVENT_SEVERITY_INFO,
			Detail:   detail,
			At:       timestamppb.New(at),
		})
	}
}

// megabytes renders a byte count for a person reading a log line or a node's page.
func megabytes(bytes int64) string {
	const (
		mebibyte = 1 << 20
		gibibyte = 1 << 30
	)
	switch {
	case bytes >= gibibyte:
		return fmt.Sprintf("%.1f GiB", float64(bytes)/gibibyte)
	case bytes >= mebibyte:
		return fmt.Sprintf("%.0f MiB", float64(bytes)/mebibyte)
	default:
		return fmt.Sprintf("%d bytes", bytes)
	}
}
