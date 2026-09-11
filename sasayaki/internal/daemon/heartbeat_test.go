package daemon

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/stats"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

var noon = time.Date(2026, time.March, 4, 12, 0, 0, 0, time.UTC)

// The rule this file is here to hold: a Docker outage produces a degraded node, never a
// silent one.
//
// The heartbeat is the only thing the panel counts to decide whether a node still exists.
// If it waited on the same socket that has stopped answering, an engine that is restarting
// for thirty seconds would look exactly like a machine that has been unplugged, and the
// panel would start placing that node's workloads elsewhere - which is a migration nobody
// asked for, caused by a hiccup (AGENTS.md section 4.5).

func TestHeartbeatHealth(t *testing.T) {
	dockerDown := "docker unreachable since 2026-03-04T12:04:11Z: " + errEngineDown.Error()

	cases := []struct {
		name        string
		convergence state.Convergence
		disk        stats.Pressure
		draining    bool

		health wisperpb.NodeHealth
		detail string
	}{
		{
			name:        "a node whose passes are converging is healthy and says nothing",
			convergence: state.Convergence{AppliedGeneration: 47},
			disk:        stats.PressureNone,
			health:      wisperpb.NodeHealth_NODE_HEALTH_HEALTHY,
			detail:      "",
		},
		{
			name: "an engine that is not answering is degraded, with the reason the loop wrote",
			convergence: state.Convergence{
				AppliedGeneration: 47,
				LastError:         dockerDown,
			},
			disk:   stats.PressureNone,
			health: wisperpb.NodeHealth_NODE_HEALTH_DEGRADED,
			detail: dockerDown,
		},
		{
			name:        "a disk past the mark is degraded even while every pass succeeds",
			convergence: state.Convergence{AppliedGeneration: 47},
			disk:        stats.PressureCritical,
			health:      wisperpb.NodeHealth_NODE_HEALTH_DEGRADED,
			detail:      "disk",
		},
		{
			name:        "a drained node is draining",
			convergence: state.Convergence{AppliedGeneration: 47},
			disk:        stats.PressureNone,
			draining:    true,
			health:      wisperpb.NodeHealth_NODE_HEALTH_DRAINING,
			detail:      "not accepting new workloads",
		},
		{
			name: "a drained node that is also broken is degraded, because that is the half " +
				"nobody would otherwise notice",
			convergence: state.Convergence{AppliedGeneration: 47, LastError: dockerDown},
			disk:        stats.PressureNone,
			draining:    true,
			health:      wisperpb.NodeHealth_NODE_HEALTH_DEGRADED,
			detail:      "draining; " + dockerDown,
		},
	}

	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			beat := &nodeHeartbeat{
				convergence: &fakeConvergence{record: test.convergence},
				sampler:     samplerAt(test.disk, 3),
				drain:       fakeDrain(test.draining),
				log:         discardLogger(),
			}

			heartbeat := beat.Heartbeat(context.Background())

			if heartbeat.GetHealth() != test.health {
				t.Errorf("health = %s, want %s", heartbeat.GetHealth(), test.health)
			}
			if !strings.Contains(heartbeat.GetHealthDetail(), test.detail) {
				t.Errorf("health_detail = %q, want it to mention %q",
					heartbeat.GetHealthDetail(), test.detail)
			}
			if heartbeat.GetAppliedGeneration() != test.convergence.AppliedGeneration {
				t.Errorf("applied_generation = %d, want %d",
					heartbeat.GetAppliedGeneration(), test.convergence.AppliedGeneration)
			}
			if heartbeat.GetRunningWorkloads() != 3 {
				t.Errorf("running_workloads = %d, want 3", heartbeat.GetRunningWorkloads())
			}
			if heartbeat.GetCapacity() == nil {
				t.Error("the heartbeat carries no capacity, so the panel has nothing to place against")
			}
		})
	}
}

// A state database that has stopped answering must not stop the heartbeat.
//
// The read is what a heartbeat does that could in principle block: everything else is an
// atomic load. So it is bounded, and this proves both halves of what happens when the bound
// is reached - a frame still goes out, and it says why it is not to be trusted.
func TestHeartbeatDoesNotWaitForAWedgedStateDatabase(t *testing.T) {
	wedged := &fakeConvergence{block: true}
	beat := &nodeHeartbeat{
		convergence: wedged,
		sampler:     samplerAt(stats.PressureNone, 2),
		drain:       fakeDrain(false),
		log:         discardLogger(),
		budget:      20 * time.Millisecond,
	}
	// A generation was read successfully once, before the disk went away.
	beat.lastGeneration.Store(47)

	started := time.Now()
	heartbeat := beat.Heartbeat(context.Background())
	took := time.Since(started)

	if took > time.Second {
		t.Fatalf("the heartbeat waited %s on the state database", took)
	}
	if heartbeat.GetHealth() != wisperpb.NodeHealth_NODE_HEALTH_DEGRADED {
		t.Errorf("health = %s, want DEGRADED", heartbeat.GetHealth())
	}
	if heartbeat.GetAppliedGeneration() != 47 {
		t.Errorf("applied_generation = %d, want the last number that was read (47): reporting 0 "+
			"would have the panel resend the whole spec on every beat",
			heartbeat.GetAppliedGeneration())
	}
	if !strings.Contains(heartbeat.GetHealthDetail(), "state database") {
		t.Errorf("health_detail = %q, want it to name what did not answer", heartbeat.GetHealthDetail())
	}
}

// sent_at is stamped by the transport, as it leaves, so that clock skew is measured against
// the moment the frame went out rather than the moment these numbers were gathered. Setting
// it here would be dead code that looks load-bearing.
func TestHeartbeatLeavesSentAtToTheTransport(t *testing.T) {
	beat := &nodeHeartbeat{
		convergence: &fakeConvergence{},
		sampler:     samplerAt(stats.PressureNone, 0),
		drain:       fakeDrain(false),
		log:         discardLogger(),
	}

	if sent := beat.Heartbeat(context.Background()).GetSentAt(); sent != nil {
		t.Errorf("sent_at = %v, want it left for rpc to stamp", sent.AsTime())
	}
}

// samplerAt is a published snapshot with one disk state and a given number of containers up.
func samplerAt(pressure stats.Pressure, running uint32) fakeSampler {
	admission := stats.Admission{MeasuredAt: noon, Workloads: true, Deployments: true, Disk: pressure}
	if pressure == stats.PressureCritical {
		admission.Workloads = false
		admission.Deployments = false
		admission.Reasons = []string{"this node's disk is 94% full, past the 92% mark at which " +
			"it stops accepting new bytes"}
	}
	return fakeSampler{snapshot: stats.Snapshot{
		At:               noon,
		Capacity:         &wisperpb.Capacity{NanoCpusTotal: 4 * nanoCPUsPerCore},
		Admission:        admission,
		RunningWorkloads: running,
	}}
}
