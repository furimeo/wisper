package daemon

import (
	"context"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
)

// Running the node: six loops that never finish on their own, and the two things that
// have to happen before any of them start.
//
// The order matters in one direction only. Recovery comes first, because a daemon that was
// killed mid-backup may have left a customer's application frozen and a node must never be
// answering commands while that is still true. The edge comes second, because a site that
// was being served a second ago should be being served again as soon as possible - before
// the first reconcile pass, which may take a while on a node with a hundred containers.
//
// Everything after that is concurrent and none of it is ordered, because none of it needs
// to be: the reconcile loop converges from the disk whether or not the panel is reachable,
// the sampler measures whether or not anything is converging, the scheduler fires the
// customer's cron entries off the same disk, and the control stream reconnects forever
// regardless of all of them.
//
// Recovery in particular has to come before the scheduler: ClearRunningCronRuns is what turns
// a row left saying "running" by a killed daemon into an honest interrupted one, and an entry
// that may not overlap would otherwise never fire again.

// uploadSweepInterval is how often abandoned uploads are reclaimed.
//
// The same cadence as the reconcile loop, which is what files/sweep.go asks for. It is
// cheap - a query for expired sessions and a listing of the node's own staging directory -
// and the failure it prevents is slow and invisible: a phone that lost signal in a lift
// leaves parts that nothing else on the node would ever mention, and a month of them is a
// disk that fills for no reason anybody can see.
const uploadSweepInterval = reconcile.DefaultInterval

// serve runs until the context is cancelled, or until a loop stops when it should not have.
func (n *node) serve(ctx context.Context) error {
	n.recover(ctx)

	if err := n.edge.Start(ctx); err != nil {
		return err
	}

	working, stop := context.WithCancel(ctx)
	defer stop()

	loops := []struct {
		name string
		run  func(context.Context) error
	}{
		{"the panel connection", n.client.Run},
		{"the reconcile loop", n.loop.Run},
		{"the stats sampler", n.sampler.Run},
		{"the database servers", n.engines.Run},
		{"the cron scheduler", n.cron.Run},
		{"the upload sweep", n.sweepUploads},
	}

	// Buffered to the number of loops, so a goroutine reporting a failure after the first
	// one has already brought the daemon down does not block for ever on the way out.
	failed := make(chan error, len(loops))
	var running sync.WaitGroup

	for _, loop := range loops {
		running.Add(1)
		go func() {
			defer running.Done()
			err := loop.run(working)
			if working.Err() != nil {
				// Shutting down. Every one of these returns the context's error on the way
				// out, and that is not news.
				return
			}
			if err == nil {
				err = fmt.Errorf("it returned without being asked to")
			}
			failed <- fmt.Errorf("%s stopped: %w", loop.name, err)
		}()
	}

	var reason error
	select {
	case <-ctx.Done():
		n.log.Info("stopping")
	case reason = <-failed:
		// None of these is supposed to be able to happen: each loop is written to keep
		// going through a panel that is unreachable, an engine that is down and a spec that
		// will not parse. One returning anyway means this node has stopped managing itself,
		// and the honest answer is to exit and let the service manager start a fresh one -
		// which touches no customer's container (design section 7.5).
		n.log.Error("a loop this node cannot run without has stopped",
			slog.String("error", reason.Error()))
	}

	stop()
	running.Wait()
	n.stopEdge(ctx)
	return reason
}

// recover finishes what a killed daemon left half-done.
//
// Crash-only means there is no cleanup on the way out, so there is some on the way in. None
// of it is fatal: a node that refuses to start because one container will not thaw is a node
// whose other two hundred containers stop being managed as well.
func (n *node) recover(ctx context.Context) {
	if cleared, err := n.store.ClearRunningCronRuns(ctx, time.Now()); err != nil {
		n.log.Warn("could not close out the scheduled runs that were in flight",
			slog.String("error", err.Error()))
	} else if cleared > 0 {
		n.log.Info("closed out scheduled runs interrupted by a restart", slog.Int64("runs", cleared))
	}

	if abandoned, err := n.builder.FailAbandonedBuilds(ctx); err != nil {
		n.log.Warn("could not close out the builds that were in flight",
			slog.String("error", err.Error()))
	} else if abandoned > 0 {
		n.log.Info("closed out builds interrupted by a restart", slog.Int("builds", abandoned))
	}

	if err := n.backups.Recover(ctx); err != nil {
		n.log.Warn("could not finish recovering from an interrupted backup or restore",
			slog.String("error", err.Error()))
	}
}

// sweepUploads reclaims what abandoned uploads left behind, on a timer.
//
// A loop here rather than inside the files package on purpose: the daemon owns the loops,
// and a package that starts a goroutine in its constructor is one nobody can stop.
func (n *node) sweepUploads(ctx context.Context) error {
	ticker := time.NewTicker(uploadSweepInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
		}

		report, err := n.files.SweepUploads(ctx, time.Now())
		if err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			// Retried on the next tick. A sweep that failed leaves the bytes where they
			// were, which is the safe direction: nothing here has ever deleted a customer's
			// file, and the disk pressure gauge is what notices if the backlog matters.
			n.log.Warn("could not reclaim abandoned uploads", slog.String("error", err.Error()))
			continue
		}
		if report.Sessions+report.Orphans+report.StagedArchives > 0 {
			n.log.Info("reclaimed abandoned uploads",
				slog.Int("sessions", report.Sessions),
				slog.Int("orphaned_parts", report.Orphans),
				slog.Int("staged_archives", report.StagedArchives))
		}
	}
}

// stopEdge takes the listeners down.
//
// Through a context that cannot be cancelled, because the one that brought us here already
// has been. This is the one shutdown step with a reason beyond tidiness: an upgrade
// restarts the process, and a new daemon cannot bind :443 while the old one is still
// holding it.
func (n *node) stopEdge(ctx context.Context) {
	if err := n.edge.Stop(context.WithoutCancel(ctx)); err != nil {
		n.log.Warn("could not stop the edge", slog.String("error", err.Error()))
	}
}
