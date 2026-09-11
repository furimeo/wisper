package stats

import (
	"strings"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The two gates, and what opens and closes each one.

var admissionAt = time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC)

func TestARoomyNodeAcceptsEverything(t *testing.T) {
	admission := decide(Thresholds{}.withDefaults(), &wisperpb.Capacity{
		NanoCpusTotal:        8 * nanosPerCore,
		NanoCpusAllocated:    2 * nanosPerCore,
		MemoryBytesTotal:     16 << 30,
		MemoryBytesAllocated: 4 << 30,
		MemoryBytesUsed:      6 << 30,
	}, PressureNone, admissionAt)

	if !admission.Workloads || !admission.Deployments {
		t.Fatalf("a node using a quarter of itself refused something: %+v", admission)
	}
	if admission.Refusal() != "" {
		t.Fatalf("a node that refused nothing still gave a reason: %q", admission.Refusal())
	}
}

func TestPromisedCPUClosesTheWorkloadGate(t *testing.T) {
	admission := decide(Thresholds{}.withDefaults(), &wisperpb.Capacity{
		NanoCpusTotal:     8 * nanosPerCore,
		NanoCpusAllocated: 8 * nanosPerCore,
		MemoryBytesTotal:  16 << 30,
	}, PressureNone, admissionAt)

	if admission.Workloads {
		t.Fatal("a node with every core already promised accepted another workload")
	}
	if !admission.Deployments {
		t.Fatal("a node out of CPU stopped accepting deployments: a build is bytes on a disk, and " +
			"the disk is not what ran out")
	}
	if !strings.Contains(admission.Refusal(), "CPU") {
		t.Fatalf("the refusal does not say what ran out: %q", admission.Refusal())
	}
}

// CPU that is being used is not CPU that has run out. A node at full load with room left to
// promise must keep accepting, or placement flaps between nodes on every spike.
func TestBusyCPUIsNotAFullNode(t *testing.T) {
	admission := decide(Thresholds{}.withDefaults(), &wisperpb.Capacity{
		NanoCpusTotal:     8 * nanosPerCore,
		NanoCpusAllocated: 1 * nanosPerCore,
		NanoCpusUsed:      8 * nanosPerCore,
		MemoryBytesTotal:  16 << 30,
	}, PressureNone, admissionAt)

	if !admission.Workloads {
		t.Fatalf("a busy but unpromised node refused a workload: %q", admission.Refusal())
	}
}

// Memory that is being used is a different matter: the kernel kills something.
func TestMemoryInUseClosesTheWorkloadGate(t *testing.T) {
	admission := decide(Thresholds{}.withDefaults(), &wisperpb.Capacity{
		NanoCpusTotal:    8 * nanosPerCore,
		MemoryBytesTotal: 16 << 30,
		MemoryBytesUsed:  15 << 30,
	}, PressureNone, admissionAt)

	if admission.Workloads {
		t.Fatal("a node with 94% of its memory in use accepted another workload")
	}
	if !strings.Contains(admission.Refusal(), "memory") {
		t.Fatalf("the refusal does not say what ran out: %q", admission.Refusal())
	}
}

// Disk pressure is the only thing that closes both gates, and the warning band closes
// neither: the point of the warning is that somebody finds out while there is still time.
func TestDiskPressureClosesBothGatesOnlyWhenCritical(t *testing.T) {
	roomy := &wisperpb.Capacity{
		NanoCpusTotal:    8 * nanosPerCore,
		MemoryBytesTotal: 16 << 30,
		DiskBytesTotal:   100 << 30,
		DiskBytesUsed:    95 << 30,
	}

	warning := decide(Thresholds{}.withDefaults(), roomy, PressureWarning, admissionAt)
	if !warning.Workloads || !warning.Deployments {
		t.Fatalf("the warning band refused something: %+v", warning)
	}

	critical := decide(Thresholds{}.withDefaults(), roomy, PressureCritical, admissionAt)
	if critical.Workloads || critical.Deployments {
		t.Fatalf("a node past the critical mark still accepts work: %+v", critical)
	}
	if !strings.Contains(critical.Refusal(), "disk") {
		t.Fatalf("the refusal does not say what ran out: %q", critical.Refusal())
	}
}

// Before the first pass the node has no figures, and it must not read that as being full: a
// daemon that has just been restarted for an upgrade has to converge immediately.
func TestAnUnmeasuredNodeAcceptsWork(t *testing.T) {
	admission := openAdmission()
	if !admission.Workloads || !admission.Deployments {
		t.Fatalf("a node that has not measured itself yet refuses work: %+v", admission)
	}
	if !admission.MeasuredAt.IsZero() {
		t.Fatal("an unmeasured admission claims a measurement time")
	}
}

// A machine nobody could measure - no /proc, no statfs, which is every developer's laptop -
// produces zero totals, and zero totals must not read as a full node.
func TestAnUnmeasurableMachineAcceptsWork(t *testing.T) {
	admission := decide(Thresholds{}.withDefaults(), &wisperpb.Capacity{}, PressureNone, admissionAt)
	if !admission.Workloads || !admission.Deployments {
		t.Fatalf("a machine with no measurements refuses work: %+v", admission)
	}
}

func TestEveryReasonIsInTheRefusal(t *testing.T) {
	admission := decide(Thresholds{}.withDefaults(), &wisperpb.Capacity{
		NanoCpusTotal:        8 * nanosPerCore,
		NanoCpusAllocated:    8 * nanosPerCore,
		MemoryBytesTotal:     16 << 30,
		MemoryBytesAllocated: 16 << 30,
		DiskBytesTotal:       100 << 30,
		DiskBytesUsed:        99 << 30,
	}, PressureCritical, admissionAt)

	if len(admission.Reasons) != 3 {
		t.Fatalf("%d reasons given, want three - CPU, memory and disk: %v",
			len(admission.Reasons), admission.Reasons)
	}
	refusal := admission.Refusal()
	for _, expected := range []string{"CPU", "memory", "disk"} {
		if !strings.Contains(refusal, expected) {
			t.Fatalf("the refusal %q does not mention %s", refusal, expected)
		}
	}
}

// Allocation counts every workload placed here, running or not. A stopped workload's limits
// are reserved for the moment its customer presses start.
func TestAllocationCountsStoppedWorkloads(t *testing.T) {
	promised := allocatedBy(spec.Spec{Workloads: []spec.Workload{
		{ID: "running", Desired: spec.DesiredRunning, Limits: spec.Limits{
			NanoCPUs: nanosPerCore, MemoryBytes: 1 << 30, DiskBytes: 10 << 30}},
		{ID: "stopped", Desired: spec.DesiredStopped, Limits: spec.Limits{
			NanoCPUs: nanosPerCore, MemoryBytes: 1 << 30, DiskBytes: 10 << 30}},
	}})

	if promised.NanoCPUs != 2*nanosPerCore {
		t.Fatalf("allocated CPU is %d, want both workloads' ceilings: a stopped workload still holds "+
			"its limits", promised.NanoCPUs)
	}
	if promised.MemoryBytes != 2<<30 || promised.DiskBytes != 20<<30 {
		t.Fatalf("allocated memory %d and disk %d, want 2 GiB and 20 GiB",
			promised.MemoryBytes, promised.DiskBytes)
	}
}

func TestNanoCPUsUsedIsIndependentOfTheInterval(t *testing.T) {
	// Two cores busy for the whole window, whatever the window was.
	short := nanoCPUsUsed(2*int64(10*time.Second), 10*time.Second)
	long := nanoCPUsUsed(2*int64(60*time.Second), 60*time.Second)

	if short != 2*nanosPerCore || long != 2*nanosPerCore {
		t.Fatalf("two cores busy reads as %d over ten seconds and %d over a minute, want %d for both: "+
			"a capacity figure the panel has to divide by the sampling interval is a figure the panel "+
			"has to be told the sampling interval", short, long, 2*nanosPerCore)
	}
}
