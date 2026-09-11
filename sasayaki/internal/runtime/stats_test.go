package runtime

import (
	"context"
	"io"
	"strings"
	"testing"

	"github.com/moby/moby/api/types/container"
	"github.com/moby/moby/client"
)

const oneReading = `{
  "read": "2026-04-01T12:00:00Z",
  "cpu_stats": {"cpu_usage": {"total_usage": 4200000000}},
  "memory_stats": {"usage": 300000000, "max_usage": 480000000, "limit": 536870912,
                   "stats": {"inactive_file": 100000000}},
  "networks": {"eth0": {"rx_bytes": 1000, "tx_bytes": 2000},
               "eth1": {"rx_bytes": 5, "tx_bytes": 7}},
  "blkio_stats": {"io_service_bytes_recursive": [
      {"op": "read", "value": 4096}, {"op": "write", "value": 8192},
      {"op": "Read", "value": 1}, {"op": "Write", "value": 2}]},
  "pids_stats": {"current": 12}
}`

func TestSampleReadsEveryCounterAndTheCeilingInEffect(t *testing.T) {
	api := newFake()
	api.onStats = func(string) (client.ContainerStatsResult, error) {
		return client.ContainerStatsResult{Body: io.NopCloser(strings.NewReader(oneReading))}, nil
	}
	api.onInspect = func(id string) (client.ContainerInspectResult, error) {
		return client.ContainerInspectResult{Container: container.InspectResponse{
			ID: id,
			HostConfig: &container.HostConfig{
				Resources: container.Resources{NanoCPUs: 500_000_000, Memory: 536870912},
			},
		}}, nil
	}
	docker := newDocker(t, api, newHost())

	sample, err := docker.Sample(context.Background(), "c1")
	if err != nil {
		t.Fatalf("Sample: %v", err)
	}

	if sample.CPUNanos != 4_200_000_000 {
		t.Errorf("cpuNanos = %d, want the cumulative counter rather than a rate", sample.CPUNanos)
	}
	if sample.CPULimitNanoCPUs != 500_000_000 {
		t.Errorf("cpuLimit = %d, want the ceiling really in effect so a chart can draw the "+
			"limit line", sample.CPULimitNanoCPUs)
	}
	if sample.MemoryUsedBytes != 200_000_000 {
		t.Errorf("memoryUsed = %d, want usage minus the reclaimable page cache: including it "+
			"makes every healthy container look like it is about to die", sample.MemoryUsedBytes)
	}
	if sample.MemoryPeakBytes != 480_000_000 {
		t.Errorf("memoryPeak = %d, want the high-water mark that explains an OOM kill after "+
			"the fact", sample.MemoryPeakBytes)
	}
	if sample.NetworkRxBytes != 1005 || sample.NetworkTxBytes != 2007 {
		t.Errorf("network = %d/%d, want every interface counted", sample.NetworkRxBytes, sample.NetworkTxBytes)
	}
	if sample.BlockReadBytes != 4097 || sample.BlockWriteBytes != 8194 {
		t.Errorf("blockIO = %d/%d, want both spellings counted: cgroups v1 says Read and v2 "+
			"says read, which is a graph that works on the developer's machine and is empty "+
			"on the node", sample.BlockReadBytes, sample.BlockWriteBytes)
	}
	if sample.Pids != 12 {
		t.Errorf("pids = %d", sample.Pids)
	}
	if sample.At.IsZero() {
		t.Error("no reading time, so two samples in one rollup bucket cannot be ordered")
	}
}

func TestMemoryInUseHandlesBothCgroupGenerations(t *testing.T) {
	v2 := container.MemoryStats{Usage: 1000, Stats: map[string]uint64{"inactive_file": 400}}
	if got := memoryInUse(v2); got != 600 {
		t.Errorf("cgroups v2: %d, want 600", got)
	}
	v1 := container.MemoryStats{Usage: 1000, Stats: map[string]uint64{"total_inactive_file": 250}}
	if got := memoryInUse(v1); got != 750 {
		t.Errorf("cgroups v1: %d, want 750", got)
	}
	bare := container.MemoryStats{Usage: 1000}
	if got := memoryInUse(bare); got != 1000 {
		t.Errorf("no cache counter: %d, want the raw usage", got)
	}
	// A cache figure larger than the usage is nonsense the kernel occasionally reports
	// between two reads; a negative number shown to a customer is worse than a zero.
	odd := container.MemoryStats{Usage: 100, Stats: map[string]uint64{"inactive_file": 500}}
	if got := memoryInUse(odd); got != 0 {
		t.Errorf("inconsistent counters: %d, want 0 rather than a negative", got)
	}
}
