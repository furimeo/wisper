package stats

import (
	"testing"
	"time"
)

// The one piece of arithmetic in this package that is easy to get wrong and impossible to
// notice: what a counter's difference is when the counter started again.

func TestFirstSightingProducesNoDelta(t *testing.T) {
	tracked := newCounters()
	at := time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC)

	_, _, ok := tracked.delta("app", "container-a@1", at, totals{CPUNanos: 900_000_000_000})
	if ok {
		t.Fatal("the first reading of a subject produced a delta; there is no previous reading to " +
			"subtract, so the whole of a container's lifetime would land in one interval")
	}

	delta, interval, ok := tracked.delta("app", "container-a@1", at.Add(10*time.Second),
		totals{CPUNanos: 902_000_000_000})
	if !ok {
		t.Fatal("the second reading produced no delta")
	}
	if delta.CPUNanos != 2_000_000_000 {
		t.Fatalf("CPU delta is %d, want 2000000000", delta.CPUNanos)
	}
	if interval != 10*time.Second {
		t.Fatalf("interval is %s, want 10s", interval)
	}
}

// A container that is restarted keeps its workload id and gets a new epoch, and its cgroup
// counters start again at zero. Everything it has burned since then is the delta.
func TestCounterDeltaAcrossAContainerRestart(t *testing.T) {
	tracked := newCounters()
	at := time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC)

	tracked.delta("app", "container-a@1", at, totals{
		CPUNanos: 500_000_000_000, NetworkRxBytes: 4_000_000, BlockWriteBytes: 900_000,
	})
	at = at.Add(12 * time.Second)
	delta, _, ok := tracked.delta("app", "container-a@1", at, totals{
		CPUNanos: 506_000_000_000, NetworkRxBytes: 4_500_000, BlockWriteBytes: 950_000,
	})
	if !ok || delta.CPUNanos != 6_000_000_000 {
		t.Fatalf("steady-state CPU delta is %d, want 6000000000", delta.CPUNanos)
	}

	// Restarted in place: same container id, a new start time, counters back near zero.
	at = at.Add(12 * time.Second)
	delta, interval, ok := tracked.delta("app", "container-a@2", at, totals{
		CPUNanos: 300_000_000, NetworkRxBytes: 12_000, BlockWriteBytes: 4_000,
	})
	if !ok {
		t.Fatal("the reading after a restart produced no delta at all, so the interval either side " +
			"of the restart is missing from the chart")
	}
	if delta.CPUNanos != 300_000_000 {
		t.Fatalf("CPU delta after a restart is %d, want the whole new reading, 300000000: a "+
			"difference against the old container's counter is meaningless", delta.CPUNanos)
	}
	if delta.NetworkRxBytes != 12_000 || delta.BlockWriteBytes != 4_000 {
		t.Fatalf("network and block deltas after a restart are %d and %d, want 12000 and 4000",
			delta.NetworkRxBytes, delta.BlockWriteBytes)
	}
	if interval != 12*time.Second {
		t.Fatalf("interval across a restart is %s, want the sampling interval, 12s", interval)
	}

	// And the pass after the restart is an ordinary difference against the new container.
	at = at.Add(12 * time.Second)
	delta, _, ok = tracked.delta("app", "container-a@2", at, totals{CPUNanos: 1_300_000_000})
	if !ok || delta.CPUNanos != 1_000_000_000 {
		t.Fatalf("CPU delta on the pass after a restart is %d, want 1000000000", delta.CPUNanos)
	}
}

// A restart that burns more in one interval than the container had used in its whole
// previous life. The numbers alone say the counter went forward; only the epoch says it did
// not.
func TestRestartThatOutrunsThePreviousLifetime(t *testing.T) {
	tracked := newCounters()
	at := time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC)

	tracked.delta("app", "container-a@1", at, totals{CPUNanos: 1_000_000_000})
	at = at.Add(12 * time.Second)
	delta, _, ok := tracked.delta("app", "container-a@2", at, totals{CPUNanos: 9_000_000_000})
	if !ok {
		t.Fatal("no delta after the restart")
	}
	if delta.CPUNanos != 9_000_000_000 {
		t.Fatalf("CPU delta is %d, want the whole new reading, 9000000000: subtracting the previous "+
			"container's total under-reports a busy restart by exactly that total", delta.CPUNanos)
	}
}

// A counter that goes backwards with no epoch change. Something reset that this package did
// not see, and a negative delta is a number no chart can draw.
func TestCounterThatGoesBackwardsIsReadAsAReset(t *testing.T) {
	tracked := newCounters()
	at := time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC)

	tracked.delta("app", "container-a@1", at, totals{CPUNanos: 800, NetworkRxBytes: 5_000})
	delta, _, ok := tracked.delta("app", "container-a@1", at.Add(time.Second),
		totals{CPUNanos: 120, NetworkRxBytes: 6_000})
	if !ok {
		t.Fatal("no delta")
	}
	if delta.CPUNanos != 120 {
		t.Fatalf("CPU delta is %d, want the new reading, 120", delta.CPUNanos)
	}
	if delta.NetworkRxBytes != 1_000 {
		t.Fatalf("network delta is %d, want 1000: only the counter that reset should be treated "+
			"as one", delta.NetworkRxBytes)
	}
}

func TestTwoReadingsAtTheSameInstantProduceNothing(t *testing.T) {
	tracked := newCounters()
	at := time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC)

	tracked.delta("app", "container-a@1", at, totals{CPUNanos: 100})
	if _, _, ok := tracked.delta("app", "container-a@1", at, totals{CPUNanos: 200}); ok {
		t.Fatal("a zero-width interval produced a delta, which divides by zero at the panel")
	}
}

func TestForgetKeepsTheMachineAndDropsWorkloadsThatLeft(t *testing.T) {
	tracked := newCounters()
	at := time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC)

	tracked.delta(machineSubject, "boot-1", at, totals{CPUNanos: 10})
	tracked.delta("staying", "container-a@1", at, totals{CPUNanos: 10})
	tracked.delta("leaving", "container-b@1", at, totals{CPUNanos: 10})

	tracked.forget(map[string]struct{}{"staying": {}})

	if len(tracked.previous) != 2 {
		t.Fatalf("%d readings remembered, want 2: the machine and the workload still here",
			len(tracked.previous))
	}
	if _, kept := tracked.previous[machineSubject]; !kept {
		t.Fatal("forgetting the workloads that left also forgot the machine, whose counters are " +
			"not a workload's and do not go away")
	}
	if _, kept := tracked.previous["leaving"]; kept {
		t.Fatal("a workload that is no longer on the node is still remembered")
	}
}
