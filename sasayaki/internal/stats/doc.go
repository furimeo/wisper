// Package stats is what this node says about its own load, and what it decides about its
// own capacity.
//
// Two jobs that look separate and are not. The numbers a customer sees on a chart and the
// numbers a node uses to refuse work are the same readings, taken once: a node that decided
// it was full from figures the panel had never seen would be a node whose refusals nobody
// could explain, and a second sampler taking its own readings is two graphs of one machine
// that disagree.
//
// # Deltas, not rates, and not absolutes either
//
// Every counter on the wire is the change over StatSample.interval_nanos, never a
// percentage and never a running total (stats.proto). A percentage cannot be re-bucketed:
// two samples that land in the same five-minute rollup have to add up, and 40% plus 40% is
// not 80% of anything. A running total cannot survive a container restart, because the
// cgroup counters start again at zero and the panel would see a negative rate.
//
// So this package keeps the previous reading of every subject and sends the difference
// (counters.go). A subject it has never seen becomes a baseline and produces nothing for
// one interval; a subject whose counters went backwards, or whose container was restarted
// underneath it, is reported at its new absolute value, because that is exactly how much it
// has burned since it started again.
//
// # Losing samples is fine, losing them silently is not
//
// A sample is a description of one moment. Blocking the sampling loop to deliver one is
// how a metrics agent turns a slow panel into a node that stops converging, so the uplink
// drops rather than blocks (rpc.Client.SendStat) and this package buffers rather than
// waits.
//
// The buffer is a bounded ring in its own SQLite file (buffer.go). Bounded, because a node
// whose tunnel has been down for a week must not have spent that week filling the disk it
// is supposed to be protecting. Its own file, because the node's state database serialises
// every statement onto one connection, and a ring being trimmed every twelve seconds does
// not belong in front of the spec the reconcile loop is waiting to read.
//
// # Headroom is the node's own decision
//
// The panel places workloads from figures that are up to a heartbeat old. A node protecting
// itself beats a scheduler being clever with stale numbers (design section 7.6), so
// Admission is computed here, published in Capacity.accepting_workloads, and read directly
// by the parts of the daemon that would otherwise start something new.
//
// Two gates, because they protect different things:
//
//   - Workloads is about promises. It refuses when the limits already placed here, or the
//     memory actually in use, leave no room for another container. CPU *usage* is
//     deliberately not a gate: a machine at 100% for a minute is busy, not full, and
//     refusing placements on a spike makes placement flap.
//   - Deployments is about the disk, and it closes before the disk is actually full
//     (pressure.go). A build that fails at 99% has already written the bytes that took the
//     node down with it.
//
// Disk pressure has hysteresis in both directions. A filesystem sitting on a threshold
// would otherwise emit NODE_EVENT_KIND_DISK_CRITICAL every twelve seconds, and an alert
// that fires two hundred times an hour is an alert nobody reads.
package stats
