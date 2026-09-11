package stats

// Deciding that the disk is nearly full, before it is actually full.
//
// A node that fills its disk takes every customer on it down at once: containers cannot
// write, the edge cannot write a certificate, SQLite cannot commit and the daemon cannot
// even record why. The design's answer is to stop accepting new bytes while there are still
// bytes left (section 7.6), and this file is where "nearly" is given a number.

// Pressure is how much room the node's storage has left.
type Pressure string

const (
	// PressureNone is room to work in. New deployments are accepted.
	PressureNone Pressure = "NONE"

	// PressureWarning is the mark at which the node says so and nothing else changes. It
	// exists so that an operator finds out from a chart rather than from an outage, and so
	// that the drop back out of PressureCritical has somewhere to land.
	PressureWarning Pressure = "WARNING"

	// PressureCritical is read-mostly: no new deployment, no new workload, and the panel is
	// told once (NODE_EVENT_KIND_DISK_CRITICAL). What is already running keeps running -
	// stopping a customer's application because the disk is tight would turn a warning into
	// the outage it exists to prevent.
	PressureCritical Pressure = "CRITICAL"
)

// clearMargin is how far below a mark the disk has to fall before the state relaxes.
//
// Three percentage points, and the reason is arithmetic rather than taste: a filesystem
// hovering on a threshold crosses it every time a log rotates, and without a margin that is
// NODE_EVENT_KIND_DISK_CRITICAL every twelve seconds. An alert that fires three hundred
// times an hour is an alert an operator filters out, which is worse than no alert.
const clearMargin = 0.03

// gauge is the disk-pressure state machine. One per sampler; not safe for concurrent use,
// and only ever touched by a sampling pass.
type gauge struct {
	warning  float64
	critical float64
	state    Pressure
}

func newGauge(warning, critical float64) *gauge {
	return &gauge{warning: warning, critical: critical, state: PressureNone}
}

// observe folds one measurement into the state and reports whether it moved.
//
// Rising is immediate: a disk filling is not something to be cautious about noticing.
// Falling is not, in both directions - PressureCritical needs a fall all the way below the
// warning mark to relax, and PressureWarning needs a further margin below that. Recovery is
// usually a retention sweep deleting a release, and a sweep that frees exactly enough to
// cross back over would otherwise flip the state on every pass until the next one runs.
//
// total of zero means nothing was measured, which is not the same as an empty disk: it is
// what a machine with no /proc and no statfs reports, and it clears rather than raises,
// because a developer's laptop must not be a node that refuses to deploy.
func (g *gauge) observe(used, total int64) (Pressure, bool) {
	previous := g.state
	g.state = g.next(fraction(used, total))
	return g.state, g.state != previous
}

// next is the transition, with the two thresholds and the margin below each.
func (g *gauge) next(fraction float64) Pressure {
	switch {
	case fraction >= g.critical:
		return PressureCritical
	case fraction >= g.warning:
		// Not enough to clear critical: the fall out of critical is defined against the
		// warning mark, not against the critical one.
		if g.state == PressureCritical {
			return PressureCritical
		}
		return PressureWarning
	case fraction >= g.warning-clearMargin:
		// Inside the margin. Whatever the state was, it stays - which is what stops a
		// filesystem sitting on the mark from oscillating.
		if g.state == PressureNone {
			return PressureNone
		}
		return PressureWarning
	default:
		return PressureNone
	}
}

// fraction is how much of total is used, as a number between 0 and 1.
func fraction(used, total int64) float64 {
	if total <= 0 || used <= 0 {
		return 0
	}
	if used > total {
		return 1
	}
	return float64(used) / float64(total)
}
