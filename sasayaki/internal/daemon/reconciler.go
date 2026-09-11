package daemon

import (
	"context"
	"sync/atomic"

	"github.com/furimeo/wisper/sasayaki/internal/rpc"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The reconcile loop as the control stream sees it, plus the one fact the heartbeat needs
// and the loop does not publish.
//
// A drained node has to report NODE_HEALTH_DRAINING between status batches as well as in
// them: the heartbeat goes out every twenty seconds and a batch every fifteen, but a batch
// is skipped whenever a pass cannot complete, and "still serving, not accepting anything
// new" is precisely the state an administrator is watching for while they wait to remove
// the node. reconcile.Loop keeps that flag in an unexported field, so the composition root
// records it on the way past rather than reaching into the loop.
//
// The flag is set whenever a real evacuation was asked for, regardless of what the loop
// answered. That is deliberate and it is the conservative direction: the loop marks itself
// drained on every path where the evacuation actually began, including the one where a
// container refused to stop and it returned an error, and the two paths where it does not -
// the spec could not be read, the engine could not be listed - leave a node that has just
// failed to survey itself. Telling the panel that node is draining keeps new workloads off
// it, which is what the administrator asked for; telling it the node is healthy would not be
// true of either case.

// drainer is the reconcile loop, as the control stream needs it.
type drainer interface {
	ReconcileNow(reason string)
	Drain(ctx context.Context, request *wisperpb.DrainNode) (*wisperpb.DrainReport, error)
}

// reconciler is the daemon's rpc.Reconciler, and the heartbeat's drainFlag.
type reconciler struct {
	loop drainer

	// drained is written by a gRPC handler goroutine and read by the heartbeat's.
	drained atomic.Bool
}

var (
	_ rpc.Reconciler = (*reconciler)(nil)
	_ drainFlag      = (*reconciler)(nil)
)

// ReconcileNow wakes the loop early.
func (r *reconciler) ReconcileNow(reason string) { r.loop.ReconcileNow(reason) }

// Drain stops accepting new workloads and, when asked, evacuates what can move.
func (r *reconciler) Drain(ctx context.Context, request *wisperpb.DrainNode) (*wisperpb.DrainReport, error) {
	report, err := r.loop.Drain(ctx, request)
	if request.GetEvacuateStateless() {
		// A survey - evacuate_stateless false - changes nothing, including this. It exists
		// so an administrator can find out what a drain would cost before committing to it.
		r.drained.Store(true)
	}
	return report, err
}

// Draining is whether this node has been emptied on purpose.
func (r *reconciler) Draining() bool { return r.drained.Load() }
