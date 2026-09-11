package stats

import (
	"context"
	"errors"
	"log/slog"
	"strconv"
	"time"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// One pass: read the machine, read every container on it, and turn both into the samples
// the panel charts and the numbers this node makes decisions with.
//
// Nothing in here fails a pass outright. Every reading is independent - the engine being
// down does not stop the machine's memory being worth reporting, and a spec that cannot be
// read only costs the allocation figures - so each one that fails is logged, left out, and
// tried again in twelve seconds.

// pass is everything one sampling pass produced.
type pass struct {
	At        time.Time
	Samples   []*wisperpb.StatSample
	Capacity  *wisperpb.Capacity
	Running   uint32
	Admission Admission
}

// collect takes one pass.
func (s *Sampler) collect(ctx context.Context, at time.Time) pass {
	desired := s.desiredSpec(ctx)
	containers, listed := s.listContainers(ctx)

	result := pass{At: at, Running: runningCount(containers)}
	result.Samples = s.workloadSamples(ctx, desired, containers, at)

	machine, machineErr := s.procfs.read()
	if machineErr != nil {
		s.reportOnce("machine", machineErr)
	} else {
		s.recovered("machine")
	}

	diskTotal, diskAvailable, diskErr := s.disk(s.stateDir)
	if diskErr != nil {
		s.reportOnce("disk", diskErr)
	} else {
		s.recovered("disk")
	}

	machineDelta, interval, haveDelta := s.counters.delta(machineSubject,
		strconv.FormatInt(machine.BootedAt, 10), at, totals{
			CPUNanos:       machine.CPUNanos,
			NetworkRxBytes: machine.NetworkRxBytes,
			NetworkTxBytes: machine.NetworkTxBytes,
		})

	if machineErr == nil && haveDelta {
		result.Samples = append(result.Samples, &wisperpb.StatSample{
			NodeId:        s.uplink.NodeID(),
			TakenAt:       timestamppb.New(at),
			IntervalNanos: interval.Nanoseconds(),
			Subject: &wisperpb.StatSample_Node{Node: &wisperpb.NodeSample{
				CpuNanos: machineDelta.CPUNanos,
				// What the machine had to burn over the same window, so the panel can work
				// out a utilisation without being told the core count separately.
				CpuCapacityNanos:     int64(machine.Cores) * interval.Nanoseconds(),
				MemoryUsedBytes:      machine.MemoryUsedBytes(),
				MemoryTotalBytes:     machine.MemoryTotalBytes,
				MemoryAvailableBytes: machine.MemoryAvailableBytes,
				DiskUsedBytes:        diskTotal - diskAvailable,
				DiskTotalBytes:       diskTotal,
				NetworkRxBytes:       machineDelta.NetworkRxBytes,
				NetworkTxBytes:       machineDelta.NetworkTxBytes,
				LoadAverage_1M:       machine.Load1m,
				RunningContainers:    int32(result.Running),
			}},
		})
	}

	pressure, moved := s.gauge.observe(diskTotal-diskAvailable, diskTotal)
	if moved {
		s.announce(pressure, diskTotal, diskAvailable, at)
	}

	promised := allocatedBy(desired)
	used := nanoCPUsUsed(machineDelta.CPUNanos, interval)
	// Built twice: once to decide, once with the decision in it. Capacity carries the
	// node's own answer in accepting_workloads, and deriving that answer from the very
	// figure it is stored beside is what keeps the two from ever disagreeing.
	provisional := capacityOf(machine, promised, used, diskTotal, diskAvailable, true)
	result.Admission = decide(s.thresholds, provisional, pressure, at)
	result.Capacity = capacityOf(machine, promised, used, diskTotal, diskAvailable, result.Admission.Workloads)

	if listed {
		live := make(map[string]struct{}, len(containers))
		for _, container := range containers {
			live[container.WorkloadID] = struct{}{}
		}
		s.counters.forget(live)
		s.volumes.forget(live)
	}

	return result
}

// workloadSamples reads every running container on the node.
func (s *Sampler) workloadSamples(ctx context.Context, desired spec.Spec,
	containers []reconcile.Container, at time.Time) []*wisperpb.StatSample {
	samples := make([]*wisperpb.StatSample, 0, len(containers))
	for _, container := range containers {
		if !container.Running || container.WorkloadID == "" {
			// A stopped container has no live cgroup to read, and one without a workload
			// label belongs to another part of the daemon.
			continue
		}
		sample, ok := s.workloadSample(ctx, desired, container, at)
		if ok {
			samples = append(samples, sample)
		}
	}
	return samples
}

// workloadSample turns one container's counters into one message.
//
// The false return is the ordinary case of a container being sampled for the first time -
// there is no previous reading to subtract, so this one becomes the baseline and the sample
// appears twelve seconds later. It is also the answer when the container exited between
// being listed and being read, which is a gap in a chart and nothing worse.
func (s *Sampler) workloadSample(ctx context.Context, desired spec.Spec,
	container reconcile.Container, at time.Time) (*wisperpb.StatSample, bool) {
	reading, err := s.engine.Sample(ctx, container.ID)
	if err != nil {
		s.log.Debug("could not read a container's resource use",
			slog.String("workload", container.WorkloadID),
			slog.String("error", err.Error()))
		return nil, false
	}

	takenAt := reading.At
	if takenAt.IsZero() {
		takenAt = at
	}

	// The container's id and the moment it started. Both, because a restart in place keeps
	// the id and a recreation changes it, and the cgroup counters begin again at zero either
	// way.
	epoch := container.ID + "@" + strconv.FormatInt(container.StartedAt.UnixNano(), 10)
	delta, interval, ok := s.counters.delta(container.WorkloadID, epoch, takenAt, totals{
		CPUNanos:        reading.CPUNanos,
		NetworkRxBytes:  reading.NetworkRxBytes,
		NetworkTxBytes:  reading.NetworkTxBytes,
		BlockReadBytes:  reading.BlockReadBytes,
		BlockWriteBytes: reading.BlockWriteBytes,
	})
	if !ok {
		return nil, false
	}

	workload, _ := desired.Workload(container.WorkloadID)
	return &wisperpb.StatSample{
		NodeId:        s.uplink.NodeID(),
		TakenAt:       timestamppb.New(takenAt),
		IntervalNanos: interval.Nanoseconds(),
		Subject: &wisperpb.StatSample_Workload{Workload: &wisperpb.WorkloadSample{
			WorkloadId:       container.WorkloadID,
			CpuNanos:         delta.CPUNanos,
			CpuLimitNanoCpus: reading.CPULimitNanoCPUs,
			MemoryUsedBytes:  reading.MemoryUsedBytes,
			MemoryLimitBytes: reading.MemoryLimitBytes,
			MemoryPeakBytes:  reading.MemoryPeakBytes,
			NetworkRxBytes:   delta.NetworkRxBytes,
			NetworkTxBytes:   delta.NetworkTxBytes,
			BlockReadBytes:   delta.BlockReadBytes,
			BlockWriteBytes:  delta.BlockWriteBytes,
			Pids:             reading.Pids,
			DiskUsedBytes:    s.volumes.bytesFor(ctx, container.WorkloadID, at),
			DiskQuotaBytes:   workload.Limits.DiskBytes,
		}},
	}, true
}

// listContainers asks the engine what is here.
//
// The second return says whether the question was answered. It matters more than the slice
// does: a pass that could not ask must not forget the counters of the containers it did not
// see, or the reading after the engine comes back is a fresh baseline and the whole outage
// vanishes from the chart instead of being a gap in it (AGENTS.md section 4.5).
func (s *Sampler) listContainers(ctx context.Context) ([]reconcile.Container, bool) {
	containers, err := s.engine.Containers(ctx)
	if err != nil {
		s.reportOnce("engine", err)
		return nil, false
	}
	s.recovered("engine")
	return containers, true
}

// desiredSpec reads what has been promised to this node. A node the panel has never spoken
// to has promised nothing, which is an answer rather than a failure.
func (s *Sampler) desiredSpec(ctx context.Context) spec.Spec {
	stored, err := s.specs.LoadSpec(ctx)
	switch {
	case errors.Is(err, state.ErrNoSpec):
		return spec.Spec{}
	case err != nil:
		s.reportOnce("spec", err)
		return spec.Spec{}
	}
	s.recovered("spec")
	return spec.FromProto(stored.Spec)
}

// runningCount is how many containers are actually up, which is the figure a node's page
// leads with.
func runningCount(containers []reconcile.Container) uint32 {
	running := uint32(0)
	for _, container := range containers {
		if container.Running {
			running++
		}
	}
	return running
}
