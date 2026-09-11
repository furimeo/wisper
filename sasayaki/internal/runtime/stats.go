package runtime

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/moby/moby/api/types/container"
	"github.com/moby/moby/client"
)

// One reading of what a container is using.
//
// Counters, not rates, and integers, not percentages. The node sends cumulative CPU
// nanoseconds and the length of the interval and the panel divides; two samples that land
// in the same rollup bucket then add up exactly, which a pre-computed percentage does not
// (stats.proto). Nothing in this file computes a rate, and nothing in it remembers a
// previous sample - keeping the state that would need is the sampler's job, and doing it
// in two places is how two graphs of the same container come to disagree.
type Sample struct {
	ContainerID string
	// When the engine took the reading, not when this call returned.
	At time.Time

	// Cumulative CPU nanoseconds since the container started.
	CPUNanos int64
	// The ceiling in effect, in Docker's nano-CPUs, read from the container rather than
	// from the spec: it is what a chart draws the limit line at, and a limit that was
	// changed mid-window should show as changed rather than as the number the panel
	// currently intends.
	CPULimitNanoCPUs int64

	// Excluding reclaimable page cache. Including it makes every healthy Linux
	// container look like it is about to be killed, because the kernel fills the rest of
	// a memory cgroup with cache on purpose.
	MemoryUsedBytes  int64
	MemoryLimitBytes int64
	// The high-water mark since the container started, which is the number that explains
	// an out-of-memory kill after the fact. The current reading never does.
	MemoryPeakBytes int64

	NetworkRxBytes int64
	NetworkTxBytes int64

	BlockReadBytes  int64
	BlockWriteBytes int64

	Pids int32
}

// Sample reads one container's resource use.
//
// One shot, not a stream: the sampler decides the cadence, and a streaming connection per
// container would be one long-lived HTTP request per workload on the node held open for
// the life of the daemon.
//
// Two calls to the engine, and the second one is deliberate. The statistics endpoint
// reports a memory ceiling but no CPU ceiling, so the container is inspected for the
// NanoCPUs actually applied. A missing container is not an error worth a stack trace -
// it exited between being listed and being sampled - and is reported as such so the
// caller can skip it.
func (d *Docker) Sample(ctx context.Context, containerID string) (Sample, error) {
	response, err := d.api.ContainerStats(ctx, containerID, client.ContainerStatsOptions{Stream: false})
	if err != nil {
		return Sample{}, fmt.Errorf("runtime: read the statistics of the container %s: %w", containerID, err)
	}
	defer response.Body.Close()

	var reading container.StatsResponse
	if err := json.NewDecoder(response.Body).Decode(&reading); err != nil {
		return Sample{}, fmt.Errorf("runtime: decode the statistics of the container %s: %w", containerID, err)
	}

	sample := Sample{
		ContainerID:      containerID,
		At:               reading.Read.UTC(),
		CPUNanos:         int64(reading.CPUStats.CPUUsage.TotalUsage),
		MemoryUsedBytes:  memoryInUse(reading.MemoryStats),
		MemoryLimitBytes: int64(reading.MemoryStats.Limit),
		MemoryPeakBytes:  int64(reading.MemoryStats.MaxUsage),
		Pids:             int32(reading.PidsStats.Current),
	}
	for _, interfaceStats := range reading.Networks {
		sample.NetworkRxBytes += int64(interfaceStats.RxBytes)
		sample.NetworkTxBytes += int64(interfaceStats.TxBytes)
	}
	sample.BlockReadBytes, sample.BlockWriteBytes = blockIO(reading.BlkioStats)

	inspected, err := d.api.ContainerInspect(ctx, containerID, client.ContainerInspectOptions{})
	if err != nil {
		return Sample{}, fmt.Errorf("runtime: read the limits of the container %s: %w", containerID, err)
	}
	if inspected.Container.HostConfig != nil {
		sample.CPULimitNanoCPUs = inspected.Container.HostConfig.NanoCPUs
		if sample.MemoryLimitBytes == 0 || inspected.Container.HostConfig.Memory > 0 {
			sample.MemoryLimitBytes = inspected.Container.HostConfig.Memory
		}
	}
	return sample, nil
}

// memoryInUse is what a customer should be shown as their memory usage.
//
// The kernel's `usage` counter includes page cache, and a container that has read a large
// file sits at its limit forever without being anywhere near trouble - the cache is
// reclaimed the moment anything else needs it. Subtracting the inactive file pages is
// what `docker stats` itself does, and it is the difference between a memory graph that
// means something and one every customer opens a ticket about.
//
// The counter is called inactive_file on cgroups v2 and total_inactive_file on v1.
func memoryInUse(memory container.MemoryStats) int64 {
	usage := int64(memory.Usage)
	if cache, ok := memory.Stats["inactive_file"]; ok {
		usage -= int64(cache)
	} else if cache, ok := memory.Stats["total_inactive_file"]; ok {
		usage -= int64(cache)
	}
	if usage < 0 {
		return 0
	}
	return usage
}

// blockIO totals what the container has read from and written to block devices.
//
// One entry per device per operation, and the operation is spelled "Read" on cgroups v1
// and "read" on v2 - which is the sort of difference that produces a graph that is
// correct on the developer's machine and empty on the node.
func blockIO(blkio container.BlkioStats) (read, written int64) {
	for _, entry := range blkio.IoServiceBytesRecursive {
		switch strings.ToLower(entry.Op) {
		case "read":
			read += int64(entry.Value)
		case "write":
			written += int64(entry.Value)
		}
	}
	return read, written
}
