package bootstrap

import (
	"context"
	"fmt"
	"runtime"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The smallest machine worth enrolling.
//
// Two gigabytes is the failure line because the shared database engines alone - one
// Postgres and one MySQL container per node (design section 8.1) - want most of a
// gigabyte between them before a single customer workload starts. Four is the comfort
// line: below it the node holds one small app and nothing else, which is a node an
// operator will be surprised by rather than one that is broken.
const (
	minimumMemoryBytes     = 2 << 30
	comfortableMemoryBytes = 4 << 30
	comfortableCPUCores    = 2
)

// checkCapacity records what this machine has to offer, and warns when it is not enough
// to be worth placing anything on.
//
// The figures matter beyond the warning: they are what the panel schedules against from
// the moment the node enrols, so a node that reported nothing would be a node the
// placement algorithm considers empty and infinitely large.
func checkCapacity(_ context.Context, _ *machine, facts *wisperpb.MachineFacts) []*wisperpb.DoctorCheck {
	return []*wisperpb.DoctorCheck{
		memoryCheck(facts.GetMemoryBytes()),
		cpuCheck(int(facts.GetCpuCores())),
	}
}

func memoryCheck(total int64) *wisperpb.DoctorCheck {
	switch {
	case total == 0:
		return check("machine.memory", "Memory", severityRequired, outcomeWarn,
			"MemTotal could not be read from /proc/meminfo, so the panel has no memory "+
				"figure to place workloads against",
			"Check /proc is mounted. Until this is known the scheduler will treat this "+
				"node as having none.")

	case total < minimumMemoryBytes:
		return check("machine.memory", "Memory", severityRequired, outcomeFail,
			fmt.Sprintf("%s of RAM, below the %s a node needs before the shared database "+
				"engines and one workload fit on it", formatBytes(total),
				formatBytes(minimumMemoryBytes)),
			"Give this machine more memory.")

	case total < comfortableMemoryBytes:
		return check("machine.memory", "Memory", severityRequired, outcomeWarn,
			fmt.Sprintf("%s of RAM. It will run, and will hold very little",
				formatBytes(total)),
			fmt.Sprintf("Plan for at least %s on a node meant to hold several customers.",
				formatBytes(comfortableMemoryBytes)),
		)

	default:
		return passed("machine.memory", "Memory", severityRequired, formatBytes(total)+" of RAM")
	}
}

func cpuCheck(cores int) *wisperpb.DoctorCheck {
	if cores < comfortableCPUCores {
		return check("machine.cpu", "CPU cores", severityAdvisory, outcomeWarn,
			fmt.Sprintf("%d core. A build and the daemon's reconcile loop will contend for "+
				"it, and deployments will be slow", cores),
			"Two cores or more. Builds run on the node that will run the result "+
				"(design section 11.4), so a single-core node builds badly.")
	}
	return passed("machine.cpu", "CPU cores", severityAdvisory,
		fmt.Sprintf("%d cores on %s/%s", cores, runtime.GOOS, runtime.GOARCH))
}
