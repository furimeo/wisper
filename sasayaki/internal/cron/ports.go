package cron

import (
	"context"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/runtime"
	"github.com/furimeo/wisper/sasayaki/internal/state"
)

// What this package needs from the rest of the daemon, declared here by the consumer.
//
// Two collaborators, each behind an interface for a reason that is specific rather than
// habitual. The engine, because everything worth testing here is about what happens when a
// command times out, exits non-zero or finds no container to run in - and arranging those
// against a real Docker daemon means a unit test that needs a machine. The disk, because the
// interesting cases are a run interrupted by a restart and an entry that left the spec while
// its command was still running, and both are assertions about rows.
//
// The clock is not an interface: it is a function on Options, so a test can put a firing at
// an exact moment without anything sleeping to get there.

// Containers is the container engine, as a scheduled command needs it.
//
// ContainerFor is answered from the engine rather than from a cache, and the pair it returns
// is the whole reason this package can be honest about why a job did not run: false means
// there is no such container, which is a fact about the customer's workload, and an error
// means the engine could not be asked, which is a fact about the node. Reporting the second
// as the first would tell a customer their app is stopped every time Docker is restarted
// (AGENTS.md section 4.5).
type Containers interface {
	ContainerFor(ctx context.Context, workloadID string) (reconcile.Container, bool, error)

	// Run executes argv inside a running container and waits for it.
	//
	// Called with no Stdout or Stderr, deliberately: that is what makes runtime capture into
	// its own bounded buffer instead of streaming a job's output into this process's memory
	// for as long as the job feels like printing.
	Run(ctx context.Context, containerID string, options runtime.RunOptions) (runtime.RunResult, error)
}

// Store is the node's disk, as scheduling needs it.
//
// The spec is read back rather than pushed in, for the same reason the reconcile loop reads
// it: the panel being unreachable must not stop the node doing what it was last told, and a
// schedule that only exists in the memory of a process that has just been upgraded is a
// schedule that stops on every deploy.
//
// The five writes are the whole history a CronStatus is built from. state.ErrNoSpec from
// LoadSpec means the panel has never spoken to this node, which is not a failure: there is
// nothing scheduled yet, and nothing is pruned on that path either.
type Store interface {
	LoadSpec(ctx context.Context) (state.StoredSpec, error)

	StartCronRun(ctx context.Context, cronID, workloadID string, at time.Time) error
	FinishCronRun(ctx context.Context, cronID string, exitCode int32, at time.Time, detail string) error
	SkipCronRun(ctx context.Context, cronID, workloadID string, at time.Time) error
	ScheduleCronRun(ctx context.Context, cronID, workloadID string, next time.Time) error

	// PruneCronRuns forgets the entries that are no longer in the spec. An entry a customer
	// deleted has to stop being reported, or the panel keeps a row nothing on the machine
	// answers for.
	PruneCronRuns(ctx context.Context, keep []string) (int64, error)
}

// The one implementation of each, asserted here rather than discovered in the composition
// root. Both interfaces are written in the other packages' own signatures precisely so that
// no adapter is needed; these lines are what keep that true when either side moves.
var (
	_ Containers = (*runtime.Docker)(nil)
	_ Store      = (*state.Store)(nil)
)
