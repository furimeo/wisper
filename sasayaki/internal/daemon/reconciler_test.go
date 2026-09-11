package daemon

import (
	"context"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The one fact the loop keeps to itself and the heartbeat needs.
//
// A drain is the state an administrator watches while they wait to remove a node, and it
// has to be visible between status batches - a batch is skipped whenever a pass cannot
// complete, which on a node being emptied is exactly when they are watching.

type fakeLoop struct {
	report  *wisperpb.DrainReport
	err     error
	reasons []string
	drains  []*wisperpb.DrainNode
}

func (f *fakeLoop) ReconcileNow(reason string) { f.reasons = append(f.reasons, reason) }

func (f *fakeLoop) Drain(_ context.Context, request *wisperpb.DrainNode) (*wisperpb.DrainReport, error) {
	f.drains = append(f.drains, request)
	return f.report, f.err
}

func TestReconcilerRecordsADrain(t *testing.T) {
	cases := []struct {
		name    string
		request *wisperpb.DrainNode
		failed  bool

		draining bool
	}{
		{
			name:     "a survey changes nothing, which is what makes it safe to run first",
			request:  &wisperpb.DrainNode{Reason: "planned maintenance"},
			draining: false,
		},
		{
			name:     "an evacuation puts the node into draining",
			request:  &wisperpb.DrainNode{EvacuateStateless: true, Reason: "planned maintenance"},
			draining: true,
		},
		{
			name: "an evacuation that partly failed still leaves the node draining, because the " +
				"loop has already stopped some of it and the panel must not place there",
			request:  &wisperpb.DrainNode{EvacuateStateless: true},
			failed:   true,
			draining: true,
		},
	}

	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			loop := &fakeLoop{report: &wisperpb.DrainReport{Complete: true}}
			if test.failed {
				loop.err = errEngineDown
				loop.report = nil
			}
			steering := &reconciler{loop: loop}

			if steering.Draining() {
				t.Fatal("a node reported itself draining before anybody asked it to")
			}

			report, err := steering.Drain(context.Background(), test.request)
			if test.failed {
				if err == nil {
					t.Fatal("a drain that failed was reported as complete")
				}
			} else if err != nil {
				t.Fatalf("drain: %v", err)
			} else if !report.GetComplete() {
				t.Error("the loop's own report did not reach the panel")
			}

			if steering.Draining() != test.draining {
				t.Errorf("Draining() = %t, want %t", steering.Draining(), test.draining)
			}
			if len(loop.drains) != 1 {
				t.Errorf("the loop was asked to drain %d times, want once", len(loop.drains))
			}
		})
	}
}

func TestReconcilerForwardsANudge(t *testing.T) {
	loop := &fakeLoop{}
	steering := &reconciler{loop: loop}

	steering.ReconcileNow("the panel asked")

	if len(loop.reasons) != 1 || loop.reasons[0] != "the panel asked" {
		t.Errorf("the loop was nudged with %v, want [the panel asked]", loop.reasons)
	}
}
