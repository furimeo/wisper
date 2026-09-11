package stats

import "time"

// Turning the kernel's running totals into the deltas the wire carries.
//
// Every counter in stats.proto is the change over interval_nanos, so this is the only place
// in the daemon that remembers a previous reading. Doing it in two places is how two charts
// of the same container come to disagree; doing it nowhere is how a container restart
// becomes a negative rate.

// totals is one subject's cumulative counters, as the kernel reports them.
//
// Gauges - memory in use, pids, disk - are deliberately absent: they are sent as they are
// read, and running one through a difference would produce the change in memory rather than
// the memory.
type totals struct {
	CPUNanos        int64
	NetworkRxBytes  int64
	NetworkTxBytes  int64
	BlockReadBytes  int64
	BlockWriteBytes int64
}

// reading is what was last seen of one subject.
type reading struct {
	// epoch is what the counters are counting from: the boot time for the machine, the
	// container's id and start time for a workload. When it changes, the counters restarted
	// at zero and the current value *is* the delta.
	epoch  string
	at     time.Time
	totals totals
}

// counters remembers the last reading of every subject.
//
// Not safe for concurrent use, and does not need to be: one sampling pass runs at a time,
// and the snapshot other goroutines read is published separately (sampler.go).
type counters struct {
	previous map[string]reading
}

func newCounters() *counters {
	return &counters{previous: make(map[string]reading)}
}

// delta reports how much subject burned since it was last read.
//
// The false return means there is nothing to report yet, not that anything failed. It
// happens on the first sighting of a subject - the daemon has just started, or a container
// has just appeared - where there is no interval to divide by. Emitting the absolute
// counter instead would put a machine's entire uptime into one twelve-second bucket and
// draw a spike that never happened.
//
// Three ways the arithmetic can go, and the second and third are the reason this function
// exists:
//
//  1. Same epoch, counters moved forward: the ordinary difference.
//  2. New epoch: the container was restarted, its cgroup was recreated and its counters
//     began again at zero, so everything it has burned since then is the current reading.
//     Detected by the epoch rather than by the numbers, because a container that used more
//     CPU in one interval after restarting than it had used in its whole previous life
//     would otherwise be reported as having used the difference, which is far too little.
//  3. Same epoch, one counter went backwards: something reset that this package did not
//     see. Trusted at its face value for that field alone, because a negative delta is a
//     number no chart can draw.
func (c *counters) delta(subject, epoch string, at time.Time, current totals) (totals, time.Duration, bool) {
	last, seen := c.previous[subject]
	c.previous[subject] = reading{epoch: epoch, at: at, totals: current}

	if !seen {
		return totals{}, 0, false
	}
	interval := at.Sub(last.at)
	if interval <= 0 {
		// Two readings at the same instant, or a clock that went backwards. There is no
		// denominator, and inventing one would put the whole interval's traffic into a
		// bucket of zero width.
		return totals{}, 0, false
	}
	if epoch != last.epoch {
		return current, interval, true
	}
	return totals{
		CPUNanos:        advance(last.totals.CPUNanos, current.CPUNanos),
		NetworkRxBytes:  advance(last.totals.NetworkRxBytes, current.NetworkRxBytes),
		NetworkTxBytes:  advance(last.totals.NetworkTxBytes, current.NetworkTxBytes),
		BlockReadBytes:  advance(last.totals.BlockReadBytes, current.BlockReadBytes),
		BlockWriteBytes: advance(last.totals.BlockWriteBytes, current.BlockWriteBytes),
	}, interval, true
}

// advance is one counter's change, with a reset read as a reset.
func advance(previous, current int64) int64 {
	if current < previous {
		return current
	}
	return current - previous
}

// forget drops the subjects that are no longer here, so a node that has run ten thousand
// short-lived workloads is not still holding ten thousand readings.
//
// Called only on a pass that actually listed the containers. A pass where the engine did
// not answer must not forget anything: "cannot see it" is not "does not exist", and
// forgetting a counter would make the reading after the engine comes back a fresh baseline
// and lose the interval either side of the outage.
func (c *counters) forget(live map[string]struct{}) {
	for subject := range c.previous {
		if subject == machineSubject {
			continue
		}
		if _, alive := live[subject]; !alive {
			delete(c.previous, subject)
		}
	}
}

// machineSubject is the key the machine's own counters are remembered under. Workload ids
// are the other keys, and a workload cannot be called this because the panel's ids are
// UUIDs.
const machineSubject = "\x00machine"
