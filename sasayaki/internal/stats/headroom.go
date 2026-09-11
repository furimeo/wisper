package stats

import (
	"fmt"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The node's own answer to "may something new be started here".
//
// The panel places against figures that are up to a heartbeat old, so the last word belongs
// to the machine: a node protecting itself beats a scheduler being clever with numbers from
// a minute ago (design section 7.6). The same decision is published in
// Capacity.accepting_workloads, so the panel usually never gets as far as asking.

// Thresholds are the marks past which this node stops taking on more.
//
// Fractions of the machine, not absolute bytes: a node with four gigabytes and one with five
// hundred need the same policy expressed the same way. Every field defaults when it is left
// at zero, because a zero ceiling would refuse everything and a caller that forgot to fill
// this in would get a node that quietly accepts nothing.
type Thresholds struct {
	// CPUAllocation is the fraction of the machine's cores that may be promised. Usage is
	// deliberately not a threshold: a machine at 100% for a minute is busy, not full, and
	// refusing placements on a spike makes placement flap between nodes.
	CPUAllocation float64

	// MemoryAllocation is the fraction of RAM that may be promised, and MemoryUsed the
	// fraction that may actually be in use. Memory has both because it is the one resource
	// where overcommitting kills something: a container that cannot get a page is a
	// container the kernel ends.
	MemoryAllocation float64
	MemoryUsed       float64

	// DiskWarning and DiskCritical are the marks of pressure.go. Past DiskCritical this node
	// is read-mostly.
	DiskWarning  float64
	DiskCritical float64
}

// Defaults, in one place because they are a policy rather than a set of magic numbers:
// leave a tenth of the machine unpromised, refuse when memory is genuinely nearly gone, and
// stop writing new bytes with 8% of the disk still free.
const (
	defaultCPUAllocation    = 0.90
	defaultMemoryAllocation = 0.90
	defaultMemoryUsed       = 0.92
	defaultDiskWarning      = 0.85
	defaultDiskCritical     = 0.92
)

// withDefaults fills in whatever was left at zero.
func (t Thresholds) withDefaults() Thresholds {
	if t.CPUAllocation <= 0 {
		t.CPUAllocation = defaultCPUAllocation
	}
	if t.MemoryAllocation <= 0 {
		t.MemoryAllocation = defaultMemoryAllocation
	}
	if t.MemoryUsed <= 0 {
		t.MemoryUsed = defaultMemoryUsed
	}
	if t.DiskWarning <= 0 {
		t.DiskWarning = defaultDiskWarning
	}
	if t.DiskCritical <= t.DiskWarning {
		t.DiskCritical = max(defaultDiskCritical, t.DiskWarning+clearMargin)
	}
	return t
}

// Admission is what this node will and will not take on right now.
//
// Two gates rather than one because they protect different things and clear at different
// times. Workloads is about promises and memory; Deployments is about bytes on the disk, and
// a node can easily be out of one and not the other.
type Admission struct {
	// MeasuredAt is when the figures behind this were taken. Zero means the sampler has not
	// completed a pass yet, in which case both gates are open: a daemon that has just
	// restarted must converge, and refusing everything for the first twelve seconds of its
	// life would make every upgrade an outage.
	MeasuredAt time.Time

	// Workloads is whether a workload this node is not already running may be created here.
	// Published as Capacity.accepting_workloads.
	Workloads bool

	// Deployments is whether a build, an upload or a restore may write new bytes.
	Deployments bool

	Disk Pressure

	// Reasons is why not, one sentence each, in the order they were found. Written for a
	// person: they end up in a log line and on the node's page, where "refused" on its own
	// is the start of a support ticket rather than the end of one.
	Reasons []string
}

// Refusal is the whole of Reasons as one sentence, or "" when nothing is refused. It is what
// a caller puts in the error it returns to whoever asked for the deployment.
func (a Admission) Refusal() string {
	switch len(a.Reasons) {
	case 0:
		return ""
	case 1:
		return a.Reasons[0]
	default:
		joined := a.Reasons[0]
		for _, reason := range a.Reasons[1:] {
			joined += "; " + reason
		}
		return joined
	}
}

// openAdmission is the state before the first pass has finished.
func openAdmission() Admission {
	return Admission{Workloads: true, Deployments: true, Disk: PressureNone}
}

// decide works out both gates from one pass's figures.
func decide(t Thresholds, capacity *wisperpb.Capacity, pressure Pressure, at time.Time) Admission {
	admission := Admission{MeasuredAt: at, Workloads: true, Deployments: true, Disk: pressure}

	if capacity.GetNanoCpusTotal() > 0 {
		promised := fraction(capacity.GetNanoCpusAllocated(), capacity.GetNanoCpusTotal())
		if promised >= t.CPUAllocation {
			admission.Workloads = false
			admission.Reasons = append(admission.Reasons, fmt.Sprintf(
				"%.0f%% of this node's CPU is already promised to the workloads on it, and the ceiling is %.0f%%",
				promised*100, t.CPUAllocation*100))
		}
	}

	if capacity.GetMemoryBytesTotal() > 0 {
		promised := fraction(capacity.GetMemoryBytesAllocated(), capacity.GetMemoryBytesTotal())
		if promised >= t.MemoryAllocation {
			admission.Workloads = false
			admission.Reasons = append(admission.Reasons, fmt.Sprintf(
				"%.0f%% of this node's memory is already promised, and the ceiling is %.0f%%",
				promised*100, t.MemoryAllocation*100))
		}
		inUse := fraction(capacity.GetMemoryBytesUsed(), capacity.GetMemoryBytesTotal())
		if inUse >= t.MemoryUsed {
			admission.Workloads = false
			admission.Reasons = append(admission.Reasons, fmt.Sprintf(
				"%.0f%% of this node's memory is in use, and starting anything else here would have "+
					"the kernel choose which customer to kill", inUse*100))
		}
	}

	switch pressure {
	case PressureCritical:
		admission.Workloads = false
		admission.Deployments = false
		admission.Reasons = append(admission.Reasons, fmt.Sprintf(
			"this node's disk is %.0f%% full, past the %.0f%% mark at which it stops accepting new bytes",
			fraction(capacity.GetDiskBytesUsed(), capacity.GetDiskBytesTotal())*100, t.DiskCritical*100))
	case PressureWarning:
		// Reported, not enforced. The point of the warning mark is that somebody finds out
		// while there is still time to do something about it.
	}

	return admission
}
