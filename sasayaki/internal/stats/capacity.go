package stats

import (
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// What the node has, what it has promised and what it is using.
//
// All three, because placement needs the promises and not just the usage: an idle workload
// still holds its ceiling, and packing a node against live usage is how it dies at the first
// spike (node.proto, Capacity). The heartbeat carries this, and the panel's scheduler reads
// it - which is why it is computed once here, from the same readings the charts are drawn
// from, rather than a second time from a second set of numbers.

// nanosPerCore is the unit Docker's NanoCPUs and ResourceLimits.nano_cpus are both in, so
// nothing between the spec and this figure multiplies anything.
const nanosPerCore = int64(1_000_000_000)

// allocation is the sum of what has been promised to the workloads placed here.
type allocation struct {
	NanoCPUs    int64
	MemoryBytes int64
	DiskBytes   int64
}

// allocatedBy totals the ceilings in a spec.
//
// Every workload counts, including the ones the panel has asked to be stopped. A stopped
// workload's volumes are still on the disk and its limits are still reserved for the moment
// the customer presses start; treating it as free is how a node accepts a placement it
// cannot honour the first time somebody comes back from holiday.
//
// Sites are counted too, for disk only in practice: a static site has no container and
// therefore no CPU or memory limit, so its zeroes add nothing while its release directories
// are real bytes.
func allocatedBy(desired spec.Spec) allocation {
	var total allocation
	for _, workload := range desired.Workloads {
		total.NanoCPUs += workload.Limits.NanoCPUs
		total.MemoryBytes += workload.Limits.MemoryBytes
		total.DiskBytes += workload.Limits.DiskBytes
	}
	return total
}

// capacityOf assembles the figure the heartbeat carries.
//
// cpuUsed is nano-CPUs rather than nanoseconds: the delta this pass measured, scaled to what
// it would be over one second, so it is directly comparable with the total and the
// allocation without the panel knowing the sampling interval.
func capacityOf(machine machineReading, promised allocation, cpuUsed int64,
	diskTotal, diskAvailable int64, accepting bool) *wisperpb.Capacity {
	diskUsed := diskTotal - diskAvailable
	if diskUsed < 0 {
		diskUsed = 0
	}
	return &wisperpb.Capacity{
		NanoCpusTotal:        int64(machine.Cores) * nanosPerCore,
		NanoCpusAllocated:    promised.NanoCPUs,
		NanoCpusUsed:         cpuUsed,
		MemoryBytesTotal:     machine.MemoryTotalBytes,
		MemoryBytesAllocated: promised.MemoryBytes,
		MemoryBytesUsed:      machine.MemoryUsedBytes(),
		DiskBytesTotal:       diskTotal,
		DiskBytesAllocated:   promised.DiskBytes,
		DiskBytesUsed:        diskUsed,
		LoadAverage_1M:       machine.Load1m,
		AcceptingWorkloads:   accepting,
	}
}

// nanoCPUsUsed converts a CPU-nanoseconds delta into the nano-CPU unit everything else here
// speaks.
//
// One core busy for the whole interval is one core's worth of nano-CPUs, whatever the
// interval was. Doing this here rather than sending the raw pair is the one place the two
// diverge: StatSample carries the nanoseconds and the interval so the panel can re-bucket
// them, while Capacity is a snapshot nobody sums.
func nanoCPUsUsed(cpuNanos int64, interval time.Duration) int64 {
	if interval <= 0 || cpuNanos <= 0 {
		return 0
	}
	return int64(float64(cpuNanos) / float64(interval.Nanoseconds()) * float64(nanosPerCore))
}
