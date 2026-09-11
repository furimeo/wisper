package reconcile

import (
	"context"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// What this package needs from the rest of the daemon.
//
// Every interface below is declared here, by the consumer, and implemented in the package
// that owns the work: containers in runtime, the release symlink in build, hostnames in
// edge, the disk in state, the panel in rpc, engines in dbengine. Declaring them at the
// point of use is what keeps the dependency pointing one way - this package knows what
// convergence is and knows nothing about the Docker API, Caddy or SQL - and it is what
// makes the whole loop testable against fakes, with no Docker anywhere.
//
// None of them is optional. Options.validate refuses a nil field at construction, because
// a collaborator the loop calls on every pass and nobody provides is a nil dereference
// fifteen seconds after the daemon starts.

// The labels every container this daemon creates carries. They are the vocabulary the
// reconciler and the runtime share, so they live here, next to the interface that reads
// them back, rather than being spelled out twice.
//
// LabelManaged is what makes the container ours. Anything on the machine without it was
// put there by somebody else and is never touched - a node is allowed to run containers
// that have nothing to do with wisper, and eating them would be the worst possible first
// impression.
const (
	// LabelManaged is set to "true" on everything the daemon creates.
	LabelManaged = "wisper.managed"
	// LabelWorkload carries Workload.ID. A managed container without it belongs to another
	// part of the daemon - a database engine, a build - and this loop leaves it alone.
	LabelWorkload = "wisper.workload"
	// LabelFingerprint carries the hash of the workload the container was created from.
	// It is how drift is noticed at all: see fingerprint.go.
	LabelFingerprint = "wisper.fingerprint"
	// LabelManagedValue is the value LabelManaged is set to.
	LabelManagedValue = "true"
)

// ContainerHealth is what Docker's own health check last said.
//
// Four states rather than a bool because "no check configured" and "the check has not run
// yet" are both not-unhealthy, and reporting either as a failure would have the panel show
// every freshly started container as broken for its first thirty seconds.
type ContainerHealth string

const (
	// HealthNone means the workload has no health check.
	HealthNone ContainerHealth = "NONE"
	// HealthStarting is inside the check's start period, where failures do not count.
	HealthStarting ContainerHealth = "STARTING"
	HealthPassing  ContainerHealth = "PASSING"
	HealthFailing  ContainerHealth = "FAILING"
)

// Container is one container as the reconciler needs to see it: enough to decide whether
// it matches the spec, and enough to describe it to the panel.
//
// It is a flattened view rather than the Engine API's own inspect result on purpose. The
// diff has to be readable, and a diff written against a hundred-field struct is a diff
// where the one field that matters is impossible to find.
type Container struct {
	// The engine's id, which is what every other method here takes.
	ID string
	// From LabelWorkload. Empty means this container is managed by the daemon but not by
	// this loop, and it is skipped rather than removed.
	WorkloadID string
	Name       string
	// From LabelFingerprint. Empty on a container this reconciler did not create, which is
	// treated as drift: something else made it, and the spec is the only authority.
	Fingerprint string
	Image       string
	// What is really running, which is how a moved tag becomes visible instead of a
	// mystery restart.
	ImageDigest string
	Running     bool
	Health      ContainerHealth
	// How many times the engine has restarted it since it was created. The counter behind
	// PhaseCrashLooping.
	RestartCount int32
	// Of the last exit. Meaningless while running.
	ExitCode  int32
	OOMKilled bool
	StartedAt time.Time
	// When it last exited. Zero while running or never started.
	FinishedAt time.Time
	// The runtime actually in effect, not the one the spec asked for. A node without runsc
	// falls back to runc and the panel has to say so out loud.
	Runtime spec.Runtime
	// The engine's own description of the state - "Exited (137) 4 seconds ago", the reason
	// a start failed. Shown to a person, so it is carried verbatim.
	Status string
}

// ImageState is how far the runtime has got with a workload's image.
//
// Pulling is asynchronous and this is why. A reconcile pass must finish in far less time
// than the systemd watchdog's window, and a cold pull of a two-gigabyte image does not.
// So the runtime starts the pull, this says "not yet", the workload is reported as
// PULLING - which is exactly the phase a customer is waiting on - and the next pass a
// fifteen seconds later asks again.
type ImageState struct {
	// True when the image is on this node and a container can be created from it.
	Ready bool
	// What was resolved, when it was. Reported so a moved tag is visible.
	Digest string
	// Human-readable progress, shown while Ready is false: "pulling 42%", "extracting".
	Progress string
}

// Runtime is the container engine, as convergence needs it.
//
// The one rule that outranks everything else in this interface: an error from Containers
// means the engine could not be asked, never that the machine is empty. Nothing in this
// package deletes anything on that path (AGENTS.md section 4.5).
type Runtime interface {
	// Containers is every container carrying LabelManaged and LabelWorkload.
	//
	// Engine containers, build workspaces and anything else the daemon runs are excluded
	// by the second label: they belong to other packages, and a loop that removed
	// everything it did not recognise would take out the shared PostgreSQL on its first
	// pass.
	Containers(ctx context.Context) ([]Container, error)

	// EnsureImage makes a workload's image available locally.
	//
	// It must return promptly whether or not the pull has finished - a pass that blocked
	// on a cold pull would miss its watchdog window and get the daemon restarted halfway
	// through. An error means the pull failed and the workload is reported FAILED with it;
	// ImageState.Ready false with no error means it is still running.
	EnsureImage(ctx context.Context, workload spec.Workload) (ImageState, error)

	// Create builds the container for a workload, stamped with fingerprint in
	// LabelFingerprint, and returns it stopped. Volumes, the tenant network and the
	// quota the limits ask for are created here too.
	Create(ctx context.Context, workload spec.Workload, fingerprint string) (Container, error)

	Start(ctx context.Context, containerID string) error

	// Stop sends SIGTERM, waits grace, then SIGKILL.
	Stop(ctx context.Context, containerID string, grace time.Duration) error

	// Remove deletes the container and its logs.
	//
	// It does not delete the workload's volumes. Omission from the spec means the panel no
	// longer wants the workload running here; it does not mean a customer agreed to lose
	// their data, and a volume left behind can be collected deliberately while one that is
	// gone cannot be brought back.
	Remove(ctx context.Context, containerID string) error
}

// Sites is the half of convergence that has no process (design section 5.5).
//
// A static site is a directory and a symlink: publishing is pointing `current` at a
// release, and rolling back is pointing it at an older one. Both are the panel naming a
// different release_id in the next generation, which is why there is no publish command
// and no rollback command that could disagree with the spec.
type Sites interface {
	// Published is the release `current` resolves to, or "" when nothing is published yet.
	Published(ctx context.Context, workloadID string) (string, error)

	// Publish points `current` at releaseID atomically, so a visitor arriving mid-swap
	// gets one release or the other and never a half-written directory.
	Publish(ctx context.Context, workloadID string, releaseID string) error

	// Discard removes the site's whole tree, for a workload that has left the spec.
	Discard(ctx context.Context, workloadID string) error
}

// Edge is the embedded Caddy, as convergence needs it.
type Edge interface {
	// Sync makes the edge serve exactly the routes in this spec and nothing else.
	//
	// The whole spec rather than just the routes, because serving a hostname needs the
	// workload behind it: whether it is a container to proxy to or a release directory to
	// read, which port, which tenant network, and the file_server options for a site.
	//
	// It is called only when the route table has actually changed, so an implementation
	// may treat it as expensive.
	Sync(ctx context.Context, desired spec.Spec) error

	// Statuses is what the edge is really doing, per hostname: whether the route is
	// loaded, how far the certificate got, and what the certificate authority said the
	// last time it refused.
	Statuses(ctx context.Context) ([]spec.RouteStatus, error)
}

// Databases is the shared engine layer, for reporting only.
//
// This loop does not create engines or grants - dbengine owns that - but a status batch is
// one snapshot of the whole node at one moment, so the sizes have to be collected here to
// travel with everything else. The panel writes managed_database.used_bytes from them
// (docs/contracts/node-spec.md section 5).
type Databases interface {
	Statuses(ctx context.Context) ([]spec.DatabaseStatus, error)
}

// Reporter is the panel, as convergence needs it. Implemented by *rpc.Client.
type Reporter interface {
	// ReportStatus hands over one pass's observations. It is best effort: a pass that
	// converged the machine and could not tell the panel still converged the machine, and
	// the next pass fifteen seconds later carries a fresher batch anyway.
	ReportStatus(ctx context.Context, batch *wisperpb.StatusBatch) (*wisperpb.Ack, error)

	// Emit reports a transition neither neighbouring batch would show: Docker went away,
	// Docker came back, a container the kernel killed. Queued while the panel is
	// unreachable, so the reason a node went quiet arrives with it when it returns.
	Emit(event *wisperpb.NodeEvent)
}

// Store is the node's disk, as convergence needs it. Implemented by *state.Store.
//
// Eight methods, which is more than an interface usually wants, and they are all here for
// the same reason: this loop is the only thing that reads the desired state, and the only
// thing that writes what was observed. Splitting them into four interfaces would name four
// things that are always passed together.
type Store interface {
	// LoadSpec is where a cold start begins. state.ErrNoSpec means the panel has never
	// spoken to this node, which is not a failure and is answered by removing nothing.
	LoadSpec(ctx context.Context) (state.StoredSpec, error)
	Convergence(ctx context.Context) (state.Convergence, error)
	MarkApplied(ctx context.Context, generation uint64, at time.Time) error
	MarkPassFailed(ctx context.Context, at time.Time, detail string) error

	SaveWorkloadStatuses(ctx context.Context, statuses []*wisperpb.WorkloadStatus, observedAt time.Time) error
	// WorkloadStatuses is what was last observed. Read on the path where Docker did not
	// answer: reporting last-known beats reporting nothing, because an empty batch would
	// tell the panel that every workload had gone.
	WorkloadStatuses(ctx context.Context) ([]state.WorkloadObservation, error)
	PruneWorkloadStatuses(ctx context.Context, keep []string) (int64, error)

	// CronRuns is the history of each scheduled command, which is what a CronStatus is
	// built from. The schedule is evaluated on the node, so this is the only place the
	// panel can learn what happened last night.
	CronRuns(ctx context.Context) ([]state.CronRun, error)
}

// The one implementation, asserted here rather than discovered in the composition root.
// Store is written in terms of the state package's own signatures precisely so that
// *state.Store satisfies it with no adapter in between; this line is what keeps that true
// when either side is edited.
var _ Store = (*state.Store)(nil)
