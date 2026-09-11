package stats

import (
	"context"
	"log/slog"
	"sync"
	"sync/atomic"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Sampler is the node's measuring loop. One per daemon.
//
// Its fields divide the same way the reconcile loop's do, and for the same reason. The
// collaborators and the thresholds are written once at construction. The counters, the
// volume measurements and the pressure gauge belong to a pass, passes never overlap, and so
// nothing guards them. The snapshot is the one thing other goroutines read - the heartbeat
// wants the capacity, the reconcile loop and the build path want the admission - and it is
// therefore published as a whole through an atomic pointer rather than as a set of fields
// somebody could read halfway through being updated.
type Sampler struct {
	engine Engine
	specs  Specs
	uplink Uplink
	buffer *buffer

	log        *slog.Logger
	now        func() time.Time
	stateDir   string
	interval   time.Duration
	thresholds Thresholds
	procfs     procfs
	disk       func(path string) (total, available int64, err error)

	drainPerPass int

	counters *counters
	volumes  *volumes
	gauge    *gauge

	// The published view. Never nil after New.
	snapshot atomic.Pointer[Snapshot]

	// Which recurring failures have already been said out loud, so a Docker daemon that has
	// been down for an hour costs one line rather than three hundred.
	quiet sync.Map
}

// Snapshot is what the rest of the daemon reads between passes.
//
// A value rather than a set of accessors, because every field in it was measured in the same
// pass: a heartbeat that carried this pass's memory and the previous pass's admission would
// be a heartbeat describing a machine that never existed.
type Snapshot struct {
	// At is when the pass that produced this ran. Zero before the first one.
	At time.Time

	// Capacity is the figure the heartbeat carries. Never nil.
	Capacity *wisperpb.Capacity

	Admission Admission

	// RunningWorkloads is how many of this node's containers are up.
	RunningWorkloads uint32

	// Buffered is how many samples are waiting on disk for a panel that is away. A node
	// that is quiet because it has nothing to say and one that is quiet because it cannot
	// reach anybody look identical without it.
	Buffered int
}

// Snapshot is the most recent pass's view of the machine.
func (s *Sampler) Snapshot() Snapshot { return *s.snapshot.Load() }

// Admission is what this node will take on right now.
//
// The shorthand the reconcile loop and the build path use: both want the decision and
// neither wants the capacity figures behind it. Before the first pass has finished it is
// open - a daemon that has just restarted has to converge, and a node that refused
// everything for its first twelve seconds would make every upgrade an outage.
func (s *Sampler) Admission() Admission { return s.snapshot.Load().Admission }

// Capacity is what the heartbeat carries. Never nil, and never blocks: a heartbeat that
// waited on Docker would turn a Docker outage into a node the panel believes is dead.
func (s *Sampler) Capacity() *wisperpb.Capacity { return s.snapshot.Load().Capacity }

// Run samples until the context is cancelled.
//
// It returns only when that happens. There is no failure it gives up on: a node whose panel
// is unreachable, whose engine is down or whose /proc will not parse is still a node that
// has to keep measuring itself, because the moment it stops is the moment nobody can see
// what it is doing.
//
// The first pass runs immediately rather than one interval in. It emits no samples - there
// is no previous reading to subtract from - but it does produce the capacity and the
// admission decision, and those are wanted by the first reconcile pass, which starts at the
// same moment.
func (s *Sampler) Run(ctx context.Context) error {
	ticker := time.NewTicker(s.interval)
	defer ticker.Stop()

	s.tick(ctx)
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
			s.tick(ctx)
		}
	}
}

// tick is one pass, from reading the machine to publishing what it found.
func (s *Sampler) tick(ctx context.Context) {
	at := s.now()
	result := s.collect(ctx, at)
	s.publish(ctx, result)

	waiting, err := s.buffer.count(ctx)
	if err != nil {
		s.reportOnce("buffer", err)
	}

	s.snapshot.Store(&Snapshot{
		At:               result.At,
		Capacity:         result.Capacity,
		Admission:        result.Admission,
		RunningWorkloads: result.Running,
		Buffered:         waiting,
	})
}

// Close releases the buffer file.
//
// Nothing is flushed here that was not already committed. The daemon is crash-only and being
// killed has to be indistinguishable from stopping, so this exists to return a file handle
// and for no other reason.
func (s *Sampler) Close() error { return s.buffer.Close() }

// reportOnce logs a recurring failure the first time it happens and stays quiet afterwards.
//
// The failures this package sees are all of the "still broken" kind - Docker has been down
// for ten minutes, /proc is not there because this is not Linux, the buffer file is on a
// full disk - and at one pass every twelve seconds each of them is three hundred identical
// lines an hour. One line when it starts and one when it stops is the whole of what an
// operator can act on.
func (s *Sampler) reportOnce(subject string, err error) {
	if _, alreadySaid := s.quiet.LoadOrStore(subject, err.Error()); alreadySaid {
		return
	}
	s.log.Warn("a reading could not be taken, and will be tried again on the next pass",
		slog.String("subject", subject), slog.String("error", err.Error()))
}

// recovered says so, once, when a subject starts answering again.
func (s *Sampler) recovered(subject string) {
	if _, wasBroken := s.quiet.LoadAndDelete(subject); wasBroken {
		s.log.Info("a reading is being taken again", slog.String("subject", subject))
	}
}
