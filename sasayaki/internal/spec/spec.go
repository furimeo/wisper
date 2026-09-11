// Package spec is the daemon's own view of the document the panel publishes.
//
// Every other package in sasayaki - reconcile, runtime, edge, build, files, terminal,
// dbengine, backup - works with the types here rather than with *wisperpb.NodeSpec. Three
// reasons, in the order they matter:
//
//  1. Units are resolved once. The wire counts seconds and nano-CPUs and carries
//     timestamppb pointers; Go has time.Duration and time.Time. Doing that conversion at
//     ten call sites is how one of them ends up multiplying by a thousand.
//  2. Unknown values are named once. FromProto is total: it never fails, and an enum this
//     binary does not recognise becomes a constant that says so, so the reconciler can
//     refuse one workload instead of the daemon refusing the whole spec. A node that
//     stopped converging because the panel was upgraded first would be a worse failure
//     than anything a new field could cause.
//  3. A change in the generated code lands in one package with tests around it.
//
// The conversion goes in exactly the two directions the wire does. The panel publishes a
// NodeSpec, so that is FromProto. The node reports status, so the four status types have
// ToProto - and FromProto too, because internal/state stores them on disk as the protobuf
// they will be sent as and reads them back after a restart.
//
// There are no interfaces in this package. What a consumer needs from a collaborator is
// declared in the consuming package, Go style; this is a package of values.
package spec

import (
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Spec is the whole desired state of this node at one generation.
//
// Always complete, never a delta (design section 5.1): a node that has been unreachable
// for a week is caught up by one document. The corollary the reconciler depends on is that
// omission is deletion - a workload that is not in Workloads is removed, together with its
// container and its logs. Anything the panel wants kept but not running arrives with
// DesiredStopped, never left out.
type Spec struct {
	// Strictly increasing per node, never reused. The node refuses anything below what it
	// has applied and re-reconciles anything equal, because the disk may have drifted.
	Generation uint64
	// When the panel built this document. Compared against the node's own clock: skew
	// breaks ACME and TLS with symptoms that point everywhere except at the clock.
	// Zero when the panel sent nothing, which is not an error.
	IssuedAt time.Time

	Workloads []Workload
	Routes    []Route
	// The shared database engine containers this node must run, at most one per engine.
	Engines []Engine
	// Customer databases carved out of those engines.
	Grants    []Grant
	Cron      []CronEntry
	FileRoots []FileRoot
	Retention Retention
	// How often to reconcile, event or no event. Sent with the spec rather than compiled
	// in, so the interval can be tuned without an upgrade. Zero means the panel did not
	// say and the daemon uses its own default; it does not mean "never".
	ReconcileInterval time.Duration
}

// FromProto reads a published spec.
//
// Total by construction. A nil message is an empty spec, which is the same thing the panel
// sends a drained node and means "run nothing" - not "do nothing", which is what an error
// return here would have been mistaken for.
//
// Nothing below this line guards against a nil sub-message, and that is not an oversight:
// the generated getters are nil-receiver-safe, so a missing Limits and a zero Limits read
// identically. A hand-written guard would be a second way of being empty, and two ways of
// being empty is one of them being wrong somewhere.
func FromProto(message *wisperpb.NodeSpec) Spec {
	if message == nil {
		return Spec{}
	}
	spec := Spec{
		Generation:        message.GetGeneration(),
		IssuedAt:          instant(message.GetIssuedAt()),
		Retention:         retentionFromProto(message.GetRetention()),
		ReconcileInterval: seconds(message.GetReconcileIntervalSeconds()),
	}

	spec.Workloads = make([]Workload, 0, len(message.GetWorkloads()))
	for _, workload := range message.GetWorkloads() {
		spec.Workloads = append(spec.Workloads, workloadFromProto(workload))
	}
	spec.Routes = make([]Route, 0, len(message.GetRoutes()))
	for _, route := range message.GetRoutes() {
		spec.Routes = append(spec.Routes, routeFromProto(route))
	}
	spec.Engines = make([]Engine, 0, len(message.GetEngines()))
	for _, engine := range message.GetEngines() {
		spec.Engines = append(spec.Engines, engineFromProto(engine))
	}
	spec.Grants = make([]Grant, 0, len(message.GetDatabases()))
	for _, grant := range message.GetDatabases() {
		spec.Grants = append(spec.Grants, grantFromProto(grant))
	}
	spec.Cron = make([]CronEntry, 0, len(message.GetCron()))
	for _, entry := range message.GetCron() {
		spec.Cron = append(spec.Cron, cronFromProto(entry))
	}
	spec.FileRoots = make([]FileRoot, 0, len(message.GetFileRoots()))
	for _, root := range message.GetFileRoots() {
		spec.FileRoots = append(spec.FileRoots, fileRootFromProto(root))
	}
	return spec
}

// IsEmpty reports whether this spec asks for nothing at all.
//
// A legitimate instruction, and the one a drained node is given. It is not the same as
// having no spec: no spec means the panel has never spoken to this node and the reconciler
// must remove nothing, while an empty spec means everything managed here is to go.
// internal/state distinguishes the two with ErrNoSpec; this method is for after that.
func (s Spec) IsEmpty() bool {
	return len(s.Workloads) == 0 && len(s.Routes) == 0 && len(s.Engines) == 0 &&
		len(s.Grants) == 0 && len(s.Cron) == 0 && len(s.FileRoots) == 0
}
