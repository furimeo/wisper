package stats

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/runtime"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The whole loop, over the fakes: a machine, a container, a panel that goes away and a disk
// that fills up.

const interval = 12 * time.Second

func TestTheFirstPassSeedsAndTheSecondReports(t *testing.T) {
	r := newRig(t, nil)
	started := r.clock.Now()
	r.engine.place("app", "container-a", started, runtime.Sample{
		At: started, CPUNanos: 10_000_000_000, MemoryUsedBytes: 300 << 20,
		MemoryLimitBytes: 512 << 20, NetworkRxBytes: 1_000, Pids: 12,
	})

	r.tick(t, 0)
	if delivered := r.uplink.delivered(); len(delivered) != 0 {
		t.Fatalf("the first pass sent %d samples; with no previous reading to subtract, a "+
			"container's whole lifetime would land in one interval", len(delivered))
	}
	if r.sampler.Snapshot().Capacity.GetMemoryBytesTotal() != 8<<30 {
		t.Fatal("the first pass produced no capacity, and the first reconcile pass runs at the " +
			"same moment as it")
	}

	r.burn(t, 400, 3_000_000, 1_000_000)
	r.engine.place("app", "container-a", started, runtime.Sample{
		At: started.Add(interval), CPUNanos: 13_000_000_000, MemoryUsedBytes: 320 << 20,
		MemoryLimitBytes: 512 << 20, NetworkRxBytes: 6_000, Pids: 13,
	})
	r.tick(t, interval)

	delivered := r.uplink.delivered()
	if len(delivered) != 2 {
		t.Fatalf("the second pass sent %d samples, want one for the machine and one for the "+
			"workload", len(delivered))
	}

	workload := workloadSampleFor(t, delivered, "app")
	if workload.GetCpuNanos() != 3_000_000_000 {
		t.Fatalf("the workload's CPU is %d, want the 3000000000 it burned in this interval",
			workload.GetCpuNanos())
	}
	if workload.GetNetworkRxBytes() != 5_000 {
		t.Fatalf("the workload's received bytes is %d, want the 5000 delta", workload.GetNetworkRxBytes())
	}
	if workload.GetMemoryUsedBytes() != 320<<20 {
		t.Fatalf("the workload's memory is %d, want the reading itself: a gauge run through a "+
			"difference reports the change in memory rather than the memory", workload.GetMemoryUsedBytes())
	}
	if workload.GetPids() != 13 {
		t.Fatalf("the workload's process count is %d, want 13", workload.GetPids())
	}

	var node *wisperpb.NodeSample
	for _, sample := range delivered {
		if sample.GetNode() != nil {
			node = sample.GetNode()
			if sample.GetIntervalNanos() != interval.Nanoseconds() {
				t.Fatalf("the machine sample covers %d nanoseconds, want %d",
					sample.GetIntervalNanos(), interval.Nanoseconds())
			}
			if sample.GetNodeId() != "node-under-test" {
				t.Fatalf("the sample says it came from %q", sample.GetNodeId())
			}
		}
	}
	if node == nil {
		t.Fatal("no sample described the machine")
	}
	if node.GetCpuNanos() != 400*10_000_000 {
		t.Fatalf("the machine's CPU is %d, want 400 ticks' worth", node.GetCpuNanos())
	}
	if node.GetCpuCapacityNanos() != 4*interval.Nanoseconds() {
		t.Fatalf("the machine's CPU capacity is %d, want four cores for the whole interval",
			node.GetCpuCapacityNanos())
	}
	if node.GetRunningContainers() != 1 {
		t.Fatalf("the machine sample counts %d running containers, want 1", node.GetRunningContainers())
	}
	if node.GetNetworkRxBytes() != 3_000_000 {
		t.Fatalf("the machine's received bytes is %d, want the 3000000 delta", node.GetNetworkRxBytes())
	}
}

// The same container, restarted between two passes. Its cgroup counters begin again at zero,
// and what it has burned since then is the whole of the new reading.
func TestASampledContainerThatRestarts(t *testing.T) {
	r := newRig(t, nil)
	started := r.clock.Now()

	r.engine.place("app", "container-a", started, runtime.Sample{At: started, CPUNanos: 40_000_000_000})
	r.tick(t, 0)
	r.engine.place("app", "container-a", started, runtime.Sample{
		At: started.Add(interval), CPUNanos: 42_000_000_000})
	r.tick(t, interval)

	if got := workloadSampleFor(t, r.uplink.delivered(), "app").GetCpuNanos(); got != 2_000_000_000 {
		t.Fatalf("the steady-state CPU delta is %d, want 2000000000", got)
	}

	// Restarted: a new container id, a new start time, counters near zero.
	restartedAt := started.Add(2 * interval)
	r.engine.place("app", "container-b", restartedAt, runtime.Sample{
		At: restartedAt, CPUNanos: 250_000_000})
	r.tick(t, interval)

	delivered := r.uplink.delivered()
	after := delivered[len(delivered)-1]
	if after.GetWorkload() == nil {
		after = delivered[len(delivered)-2]
	}
	if got := after.GetWorkload().GetCpuNanos(); got != 250_000_000 {
		t.Fatalf("the CPU delta across a restart is %d, want the new container's whole reading, "+
			"250000000: a difference against the container that is gone is meaningless", got)
	}
	if after.GetIntervalNanos() != interval.Nanoseconds() {
		t.Fatalf("the interval across a restart is %d, want %d",
			after.GetIntervalNanos(), interval.Nanoseconds())
	}

	// And the pass after it is an ordinary difference again.
	r.engine.place("app", "container-b", restartedAt, runtime.Sample{
		At: restartedAt.Add(interval), CPUNanos: 1_250_000_000})
	r.tick(t, interval)
	if got := workloadSampleFor(t, r.uplink.delivered()[len(r.uplink.delivered())-2:], "app").GetCpuNanos(); got != 1_000_000_000 {
		t.Fatalf("the CPU delta on the pass after a restart is %d, want 1000000000", got)
	}
}

// A machine nobody can measure - which is every developer's laptop, and this test run - must
// not read as a node that is full.
func TestAnUnmeasurableMachineKeepsAccepting(t *testing.T) {
	r := newRig(t, func(o *Options) {
		o.ProcRoot = t.TempDir()
		o.Disk = func(string) (int64, int64, error) { return 0, 0, errors.New("no such filesystem") }
	})

	r.tick(t, 0)
	r.tick(t, interval)

	admission := r.sampler.Admission()
	if !admission.Workloads || !admission.Deployments {
		t.Fatalf("a machine with no measurements refuses work: %q", admission.Refusal())
	}
	if admission.Disk != PressureNone {
		t.Fatalf("an unmeasured disk reads as %s", admission.Disk)
	}
}

func TestTheSamplerRefusesToBeBuiltWithoutItsCollaborators(t *testing.T) {
	if _, err := New(t.Context(), Options{StateDir: "/var/lib/wisper"}); err == nil {
		t.Fatal("a sampler with no engine, spec source or uplink was built; every one of those is " +
			"used on every pass")
	}
	if _, err := New(t.Context(), Options{
		Engine: newFakeEngine(), Specs: &fakeSpecs{}, Uplink: &fakeUplink{}, StateDir: "relative",
	}); err == nil {
		t.Fatal("a relative state directory was accepted; it measures whatever directory the " +
			"process happens to be in")
	}
}

func TestRunStopsWhenTheContextDoes(t *testing.T) {
	r := newRig(t, func(o *Options) { o.Interval = time.Millisecond })
	ctx, cancel := context.WithCancel(t.Context())

	finished := make(chan error, 1)
	go func() { finished <- r.sampler.Run(ctx) }()

	cancel()
	select {
	case err := <-finished:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("Run returned %v, want context.Canceled", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Run did not return after its context was cancelled")
	}
}

// A container that exited between being listed and being read is a gap in a chart and
// nothing worse. It must not take the rest of the pass with it.
func TestAContainerThatExitsBetweenListingAndSamplingIsSkipped(t *testing.T) {
	r := newRig(t, nil)
	started := r.clock.Now()
	r.engine.place("staying", "container-a", started, runtime.Sample{At: started, CPUNanos: 1_000})
	r.engine.place("going", "container-b", started, runtime.Sample{At: started, CPUNanos: 1_000})
	r.tick(t, 0)

	r.engine.mu.Lock()
	r.engine.sampleError["container-b"] = errors.New("no such container")
	r.engine.mu.Unlock()
	r.engine.place("staying", "container-a", started, runtime.Sample{
		At: started.Add(interval), CPUNanos: 3_000})
	r.burn(t, 10, 0, 0)
	r.tick(t, interval)

	delivered := r.uplink.delivered()
	if got := workloadSampleFor(t, delivered, "staying").GetCpuNanos(); got != 2_000 {
		t.Fatalf("the container that is still here reported %d, want 2000: one container "+
			"disappearing must not cost the pass", got)
	}
	for _, sample := range delivered {
		if sample.GetWorkload().GetWorkloadId() == "going" {
			t.Fatal("a sample was produced for a container that could not be read")
		}
	}
}

// What the panel has promised this node reaches the capacity the heartbeat carries, and a
// spec that cannot be read costs the allocation figures and nothing else.
func TestPromisesInTheSpecReachTheCapacity(t *testing.T) {
	r := newRig(t, nil)
	r.specs.spec = &wisperpb.NodeSpec{
		Generation: 7,
		Workloads: []*wisperpb.Workload{
			{Id: "one", Limits: &wisperpb.ResourceLimits{
				NanoCpus: nanosPerCore, MemoryBytes: 1 << 30, DiskBytes: 5 << 30}},
			{Id: "two", DesiredState: wisperpb.DesiredState_DESIRED_STATE_STOPPED,
				Limits: &wisperpb.ResourceLimits{
					NanoCpus: nanosPerCore / 2, MemoryBytes: 512 << 20, DiskBytes: 1 << 30}},
		},
	}
	r.tick(t, 0)

	capacity := r.sampler.Capacity()
	if capacity.GetNanoCpusAllocated() != nanosPerCore+nanosPerCore/2 {
		t.Fatalf("allocated CPU is %d, want both workloads' ceilings", capacity.GetNanoCpusAllocated())
	}
	if capacity.GetMemoryBytesAllocated() != (1<<30)+(512<<20) {
		t.Fatalf("allocated memory is %d, want 1.5 GiB", capacity.GetMemoryBytesAllocated())
	}
	if capacity.GetDiskBytesAllocated() != 6<<30 {
		t.Fatalf("allocated disk is %d, want 6 GiB", capacity.GetDiskBytesAllocated())
	}

	// A spec the node cannot read is answered with no promises rather than with a pass that
	// reports nothing at all.
	r.specs.err = errors.New("the state database is locked")
	r.tick(t, interval)

	if got := r.sampler.Capacity().GetNanoCpusAllocated(); got != 0 {
		t.Fatalf("allocated CPU is %d after the spec became unreadable, want 0", got)
	}
	if r.sampler.Capacity().GetMemoryBytesTotal() != 8<<30 {
		t.Fatal("a spec that could not be read cost the machine's own figures too")
	}
}
