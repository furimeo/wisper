package stats

import (
	"errors"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/runtime"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// What the sampler does when something it depends on goes away: the panel, the container
// engine, or the free space on the disk.

// Half an hour of tunnel outage at one pass every twelve seconds. Nothing reaches the panel,
// everything that fits is on disk, and the disk holding it does not grow without limit.
func TestALongOutageBuffersWithinItsBoundAndThenCatchesUp(t *testing.T) {
	const bound = 40
	r := newRig(t, func(o *Options) { o.BufferCapacity = bound })
	started := r.clock.Now()
	r.uplink.reach(false)

	cpu := int64(0)
	for pass := range 150 {
		cpu += 1_000_000_000
		r.burn(t, 10, 1_000, 500)
		r.engine.place("app", "container-a", started, runtime.Sample{
			At: started.Add(time.Duration(pass) * interval), CPUNanos: cpu})
		r.tick(t, interval)

		if held := r.sampler.Snapshot().Buffered; held > bound {
			t.Fatalf("after pass %d the buffer holds %d samples and its bound is %d: a node whose "+
				"panel has been away since Friday must not have spent the weekend filling the disk",
				pass, held, bound)
		}
	}

	if sent := r.uplink.delivered(); len(sent) != 0 {
		t.Fatalf("%d samples reached a panel that was not there", len(sent))
	}
	if held := r.sampler.Snapshot().Buffered; held != bound {
		t.Fatalf("the buffer holds %d samples after the outage, want it full at %d", held, bound)
	}

	// The tunnel comes back. The backlog is delivered oldest first, a bounded amount per
	// pass, alongside what the pass has just measured.
	r.uplink.reach(true)
	cpu += 1_000_000_000
	r.burn(t, 10, 1_000, 500)
	r.engine.place("app", "container-a", started, runtime.Sample{
		At: started.Add(151 * interval), CPUNanos: cpu})
	r.tick(t, interval)

	delivered := r.uplink.delivered()
	if len(delivered) != bound+2 {
		t.Fatalf("%d samples reached the panel on the first pass after it came back, want the %d "+
			"held plus the 2 this pass measured", len(delivered), bound)
	}
	if r.sampler.Snapshot().Buffered != 0 {
		t.Fatalf("%d samples are still held after they were delivered", r.sampler.Snapshot().Buffered)
	}
	for index := 1; index < len(delivered); index++ {
		earlier := delivered[index-1].GetTakenAt().AsTime()
		later := delivered[index].GetTakenAt().AsTime()
		if later.Before(earlier) {
			t.Fatalf("the sample delivered at position %d was taken at %s, after the one at position "+
				"%d, taken at %s: what was held has to go out before what was just measured, or a "+
				"chart drawn from them puts the outage in the wrong place",
				index-1, earlier, index, later)
		}
	}
}

// The panel is reachable but not keeping up. The samples it will not take go to disk rather
// than being thrown away.
func TestSamplesThePanelWillNotTakeAreBuffered(t *testing.T) {
	r := newRig(t, func(o *Options) { o.BufferCapacity = 100 })
	started := r.clock.Now()
	r.uplink.mu.Lock()
	r.uplink.queue = 1
	r.uplink.mu.Unlock()

	r.engine.place("app", "container-a", started, runtime.Sample{At: started, CPUNanos: 1})
	r.tick(t, 0)
	r.engine.place("app", "container-a", started, runtime.Sample{
		At: started.Add(interval), CPUNanos: 2})
	r.burn(t, 10, 0, 0)
	r.tick(t, interval)

	if len(r.uplink.delivered()) != 1 {
		t.Fatalf("%d samples were accepted by a queue of one", len(r.uplink.delivered()))
	}
	if held := r.sampler.Snapshot().Buffered; held != 1 {
		t.Fatalf("%d samples were buffered, want the 1 the panel refused", held)
	}
}

// Docker not answering is not the node being empty, and it is not a reason to forget what
// each container's counters were. The reading after it comes back spans the outage.
func TestAnEngineOutageDoesNotResetTheCounters(t *testing.T) {
	r := newRig(t, nil)
	started := r.clock.Now()

	r.engine.place("app", "container-a", started, runtime.Sample{At: started, CPUNanos: 5_000_000_000})
	r.tick(t, 0)

	r.engine.mu.Lock()
	r.engine.listError = errors.New("cannot connect to the docker daemon")
	r.engine.mu.Unlock()
	r.tick(t, interval)
	r.tick(t, interval)

	if len(r.uplink.delivered()) == 0 {
		t.Fatal("nothing at all was reported while Docker was away; the machine's own memory and " +
			"disk are still worth charting")
	}
	for _, sample := range r.uplink.delivered() {
		if sample.GetWorkload() != nil {
			t.Fatal("a workload sample was produced while the engine was unreachable")
		}
	}

	r.engine.mu.Lock()
	r.engine.listError = nil
	r.engine.mu.Unlock()
	r.engine.place("app", "container-a", started, runtime.Sample{
		At: started.Add(3 * interval), CPUNanos: 8_000_000_000})
	r.tick(t, interval)

	workload := workloadSampleFor(t, r.uplink.delivered(), "app")
	if workload.GetCpuNanos() != 3_000_000_000 {
		t.Fatalf("the first reading after the engine came back is %d, want the 3000000000 burned "+
			"since the last real one: forgetting the counter would make the outage vanish from the "+
			"chart instead of being a gap in it", workload.GetCpuNanos())
	}
}

// The disk filling closes both gates, says so once, and opens them again when it drains.
func TestDiskPressureClosesTheGatesAndAnnouncesItself(t *testing.T) {
	r := newRig(t, nil)
	r.disk.fillTo(0.40)
	r.tick(t, 0)

	if admission := r.sampler.Admission(); !admission.Workloads || !admission.Deployments {
		t.Fatalf("a node at 40%% refused something: %q", admission.Refusal())
	}
	if len(r.uplink.emitted()) != 0 {
		t.Fatalf("%d events for a node with plenty of room", len(r.uplink.emitted()))
	}

	r.disk.fillTo(0.95)
	r.tick(t, interval)

	admission := r.sampler.Admission()
	if admission.Workloads || admission.Deployments {
		t.Fatalf("a node at 95%% is still accepting work: %+v", admission)
	}
	if admission.Disk != PressureCritical {
		t.Fatalf("disk pressure is %s, want CRITICAL", admission.Disk)
	}
	if r.sampler.Capacity().GetAcceptingWorkloads() {
		t.Fatal("the capacity the heartbeat carries still says this node is accepting workloads, " +
			"which is the figure the panel places against")
	}

	events := r.uplink.emitted()
	if len(events) != 1 {
		t.Fatalf("%d events, want exactly one", len(events))
	}
	if events[0].GetKind() != wisperpb.NodeEventKind_NODE_EVENT_KIND_DISK_CRITICAL ||
		events[0].GetSeverity() != wisperpb.EventSeverity_EVENT_SEVERITY_CRITICAL {
		t.Fatalf("the event is %s at %s, want DISK_CRITICAL at CRITICAL",
			events[0].GetKind(), events[0].GetSeverity())
	}

	// Still full on the next pass: nothing new is said.
	r.tick(t, interval)
	if len(r.uplink.emitted()) != 1 {
		t.Fatalf("%d events after a second pass at 95%%; a state that has not changed has nothing "+
			"to announce", len(r.uplink.emitted()))
	}

	// A retention sweep frees space.
	r.disk.fillTo(0.50)
	r.tick(t, interval)

	if admission := r.sampler.Admission(); !admission.Workloads || !admission.Deployments {
		t.Fatalf("the node did not start accepting work again: %q", admission.Refusal())
	}
	events = r.uplink.emitted()
	if len(events) != 2 {
		t.Fatalf("%d events after recovery, want the node to have said it recovered", len(events))
	}
	if events[1].GetSeverity() != wisperpb.EventSeverity_EVENT_SEVERITY_INFO {
		t.Fatalf("the recovery event is at %s, want INFO", events[1].GetSeverity())
	}
}

// A machine nobody can measure - which is every developer's laptop, and this test run - must
