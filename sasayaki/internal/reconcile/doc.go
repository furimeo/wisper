// Package reconcile is the part of sasayaki that makes the machine match the document.
//
// The panel never says "start container X". It publishes "at generation 47 this node holds
// these workloads, these routes, these grants", writes that to the node's disk, and this
// loop closes the gap - every fifteen seconds, whether or not anything happened, and again
// the moment the panel asks. That periodicity is not a fallback for missed events; it is
// the mechanism. A container somebody stopped by hand comes back, a spec that arrived
// while the daemon was being upgraded is applied on the next tick, and a daemon that was
// killed reads the last spec off its own disk and converges again with the panel
// unreachable. Its predecessor kept the running state in RAM, lost it on every restart and
// never reconverged; that single fact is why this package exists.
//
// The shape of a pass:
//
//	desired  := the spec on disk            (state)
//	actual   := containers labelled ours    (runtime)
//	plan     := desired against actual      (diff.go, a pure function)
//	           create, start, stop, recreate on drift, remove what is not in the spec
//	edge.Sync(desired)                      (routes.go, only when the table changed)
//	report(statuses, applied_generation)    (report.go)
//
// One rule outranks every other line in this package:
//
//	"Cannot see it" is not "does not exist".
//
// If the engine cannot be asked, the node reports degraded, retries with backoff, and
// deletes nothing. Every removal in here is reachable only from a plan built on a container
// list the runtime actually returned. Mistaking a failed query for an empty machine is the
// fastest way to destroy a customer's data (AGENTS.md section 4.5).
//
// Where things are:
//
//	doc.go          this
//	ports.go        what the loop needs from the rest of the daemon, declared here
//	options.go      construction, and refusing to build a loop with a nil collaborator
//	loop.go         the tick, the nudge, the backoff, the watchdog's place in the select
//	pass.go         the order of one pass
//	degraded.go     what the node says when it cannot see enough to converge
//	diff.go         desired against actual, as a pure function
//	fingerprint.go  how drift is noticed at all
//	apply.go        performing one decision
//	workload.go     one workload's facts, turned into the sentence the panel shows
//	routes.go       the route table, and getting it into the edge
//	report.go       the status batch, and the bookkeeping that goes with it
//	drain.go        emptying a node on purpose
//	watchdog.go     proving to systemd that the loop is still turning
//
// What this package does not do: it does not run the shared database engines, execute the
// cron entries or produce the release directories a site is served from. Those belong to
// dbengine, to whoever owns the schedule, and to build. It converges workloads and routes,
// and it collects everybody's observations into the one status batch the panel receives -
// a batch is a snapshot of the whole node at one moment, and two halves of a machine
// observed a minute apart is a comparison that finds drift that was never there.
package reconcile
