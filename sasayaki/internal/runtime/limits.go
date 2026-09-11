package runtime

import (
	"github.com/moby/moby/api/types/container"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// The cgroups v2 ceilings, and the one field this whole package exists to get right.
//
// # NanoCPUs is nano-CPUs
//
// container.Resources.NanoCPUs is a hard CPU ceiling expressed in billionths of a core:
// 1000000000 is one full core and 500000000 is half of one. The engine turns it into
// cpu.max, and a container that hits it is throttled.
//
// container.Resources.CPUShares, sitting two fields above it, is something completely
// different: a *relative weight* against the other containers on the machine, which does
// nothing at all while the machine is idle and does not bound anything ever.
//
// The predecessor set CPUShares to the millicore figure multiplied by a thousand and
// called it a limit (design section 9, Sachuro #7). Every workload on every node
// therefore had a scheduling preference where a ceiling was intended: one busy container
// could take the entire machine, everyone else on it went slow, and the panel showed a
// tidy "0.5 vCPU" next to all of them. Nothing failed, nothing logged, and the only
// symptom was customers saying the platform was slow.
//
// So: the spec carries nano-CPUs, spec.Limits carries nano-CPUs, and this function
// assigns them to NanoCPUs with no arithmetic in between. CPUShares is never set by this
// package, and limits_test.go asserts that it stays zero.
func resourcesFor(limits spec.Limits) container.Resources {
	resources := container.Resources{
		NanoCPUs: limits.NanoCPUs,
		Memory:   limits.MemoryBytes,
	}

	// Swap is only meaningful alongside a memory limit; the engine rejects the pair
	// otherwise. Equal to Memory disables swap entirely, which is the panel's default
	// and the right one: a container that swaps makes the whole machine grind, while a
	// container that is killed fails one workload.
	if limits.MemoryBytes > 0 {
		resources.MemorySwap = limits.MemorySwapBytes
		if resources.MemorySwap == 0 || (resources.MemorySwap > 0 && resources.MemorySwap < limits.MemoryBytes) {
			resources.MemorySwap = limits.MemoryBytes
		}
	}

	// A fork bomb inside gVisor is still a fork bomb for the host's process table:
	// runsc's sentry creates real host tasks for guest threads.
	if limits.PidsLimit > 0 {
		pids := limits.PidsLimit
		resources.PidsLimit = &pids
	}

	// RLIMIT_NOFILE. Node and Go servers open a lot of sockets and the distribution
	// default is low enough that running out looks like a networking bug rather than a
	// limit. Soft and hard are set to the same value: a soft limit the process can raise
	// on its own is not a limit, and every runtime that cares raises it to the hard one
	// at startup anyway.
	if limits.NofileLimit > 0 {
		resources.Ulimits = []*container.Ulimit{
			{Name: "nofile", Soft: limits.NofileLimit, Hard: limits.NofileLimit},
		}
	}

	return resources
}

// restartFor maps the spec's restart policy onto the engine's.
//
// Note what is not here: the workload's desired state. Starting and stopping a container
// must not change its configuration, or every pause and resume would recreate it and take
// the customer's logs with it - which is why reconcile's fingerprint leaves Desired out
// too. The consequence is worth stating: a workload whose policy is ALWAYS and which the
// customer has stopped will be started once by the engine when the Docker daemon itself
// restarts, and stopped again by the next reconcile pass within fifteen seconds. That is
// the policy the panel asked for, applied honestly.
func restartFor(restart spec.Restart) container.RestartPolicy {
	switch restart.Mode {
	case spec.RestartAlways:
		return container.RestartPolicy{Name: container.RestartPolicyAlways}
	case spec.RestartUnlessStopped:
		return container.RestartPolicy{Name: container.RestartPolicyUnlessStopped}
	case spec.RestartOnFailure:
		return container.RestartPolicy{
			Name: container.RestartPolicyOnFailure,
			// Past this the workload is reported as crash-looping rather than restarted
			// forever, so the customer is told instead of left wondering why their
			// service is never quite up.
			MaximumRetryCount: int(restart.MaxRetries),
		}
	default:
		// The reconcile loop runs every fifteen seconds whatever happens, so a workload
		// the panel wants running comes back even with no engine-level policy at all.
		return container.RestartPolicy{Name: container.RestartPolicyDisabled}
	}
}
