package daemon

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/rpc"
	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Taking the desired state and writing it down.
//
// Two things happen here and nothing else. The spec is stored, because the panel is waiting
// for an acknowledgement that it arrived and was understood - and an acknowledgement has to
// take milliseconds while convergence takes minutes. Then the loop is nudged, because a
// customer who pressed "deploy" should not wait out the remainder of a fifteen-second tick
// for something the node already knows about.
//
// The interesting line is the error translation, and it is the reason this is a type rather
// than a closure. state and rpc each have their own name for "that generation is older than
// the one I have", and the two are deliberately different values: rpc must not import state,
// or the transport and the disk stop being separable. This is the only place that can join
// them up, and skipping the join does not break anything visible - the acknowledgement still
// says accepted: false - which is exactly what makes it dangerous. What it breaks is the log:
// a routine resend on a flapping tunnel is written at error level as "could not store the
// spec", and an operator goes looking for a disk fault that is not there
// (rpc/handlers.go, ErrSpecSuperseded).

// specWriter is the node's disk, as the control stream needs it: one method.
type specWriter interface {
	SaveSpec(ctx context.Context, spec *wisperpb.NodeSpec, reason string, receivedAt time.Time) error
}

// nudger is the reconcile loop, as the control stream needs it. Losing a nudge is harmless
// by design, which is what makes it safe to send from here without waiting for anything.
type nudger interface {
	ReconcileNow(reason string)
}

// storedSpec is the daemon's rpc.SpecReceiver.
type storedSpec struct {
	store specWriter
	loop  nudger
	now   func() time.Time
}

var _ rpc.SpecReceiver = storedSpec{}

// ApplySpec stores the spec durably and returns. It does not converge.
func (s storedSpec) ApplySpec(ctx context.Context, spec *wisperpb.NodeSpec, reason string) error {
	err := s.store.SaveSpec(ctx, spec, reason, s.now())
	switch {
	case errors.Is(err, state.ErrSupersededGeneration):
		// Normal traffic, not a failure: the panel resends the whole spec on every
		// reconnect, so a node that dropped and reconnected twice in quick succession sees
		// the older of the two arrive second.
		return fmt.Errorf("generation %d is not newer than the one this node has already "+
			"applied: %w", spec.GetGeneration(), rpc.ErrSpecSuperseded)
	case err != nil:
		return err
	}

	s.loop.ReconcileNow(fmt.Sprintf("the panel published generation %d (%s)",
		spec.GetGeneration(), reason))
	return nil
}
