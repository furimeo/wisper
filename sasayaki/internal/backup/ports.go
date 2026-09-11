package backup

import (
	"context"
	"io"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// What this package needs from the rest of the daemon, declared here by the consumer.
//
// The direction is the whole point. backup knows what a snapshot is and knows nothing about
// gRPC, about the Docker Engine API or about how a database server is run; each interface is
// implemented by the package that owns that work, and each is small enough that a test
// stands in for it with a temporary directory and no daemon of any kind.

// Store is the node's SQLite, as a backup needs it.
//
// Three jobs, and the first is the one backup.proto is explicit about: "a repeated command
// with the same id is the same backup, not a second one". Without a durable record a panel
// that resent RunBackup after a dropped stream would get two archives, two uploads of the
// same bytes and a retention rule counting them as two generations. The second is the stage,
// which is how a daemon that died during QUIESCE can be found afterwards and the customer's
// application let go. The third is the same for restores, where a crash halfway leaves a
// volume swung aside and a workload stopped.
type Store interface {
	BeginBackup(ctx context.Context, run state.BackupRun) error
	RecordBackupStage(ctx context.Context, backupID string, stage wisperpb.BackupStage, at time.Time, detail string) error
	FinishBackup(ctx context.Context, backupID string, completed *wisperpb.BackupCompleted, at time.Time) error
	Backup(ctx context.Context, backupID string) (state.BackupRun, error)
	UnfinishedBackups(ctx context.Context) ([]state.BackupRun, error)

	BeginRestore(ctx context.Context, run state.RestoreRun) error
	RecordRestoreProgress(ctx context.Context, restoreID string, at time.Time, detail string) error
	FinishRestore(ctx context.Context, restoreID string, completed *wisperpb.RestoreCompleted, at time.Time) error
	Restore(ctx context.Context, restoreID string) (state.RestoreRun, error)
	UnfinishedRestores(ctx context.Context) ([]state.RestoreRun, error)
}

// The one implementation, asserted here rather than discovered in the composition root.
// Store is written in the state package's own signatures precisely so that *state.Store
// satisfies it with no adapter; this line is what keeps that true when either side moves.
var _ Store = (*state.Store)(nil)

// Workloads is the container runtime, as a backup needs it, and no more of it than that.
//
// Five methods, keyed by workload id rather than by container id, because a backup is asked
// for in the panel's vocabulary and resolving the container is the runtime's job. The
// composition root adapts runtime.Docker onto this: ContainerFor turns a workload id into a
// container, and Start and Stop already take one.
//
// Pause and Unpause are the pair that make a volume snapshot a backup. They are the engine's
// freezer, not SIGTERM: an application that is paused for four hundred milliseconds notices
// nothing, while one that is stopped and started loses every connection it had open. A
// runtime that cannot pause must say so by returning an error rather than by quietly doing
// nothing - a snapshot of a directory that was being written to is not something to report
// as a success.
type Workloads interface {
	// Running reports whether this workload has a container that is running right now. A
	// workload that is not running needs no pause, and false with no error is the normal
	// answer for one the customer has scaled to zero.
	Running(ctx context.Context, workloadID string) (bool, error)

	// Pause suspends every process in the workload's container.
	Pause(ctx context.Context, workloadID string) error

	// Unpause lets them go again. It is called from a deferred function on every path,
	// including the one where the snapshot failed, so it must succeed for a container that
	// is already running.
	Unpause(ctx context.Context, workloadID string) error

	// Stop is the heavier pair, used by a restore: overwriting a volume underneath a running
	// process gives it a filesystem that changed while it was not looking.
	Stop(ctx context.Context, workloadID string) error
	Start(ctx context.Context, workloadID string) error
}

// DatabaseDumps is the logical half, implemented by the dbengine package.
//
// Deliberately three narrow methods rather than a handle on the engine. This package knows
// that a dump is a stream of bytes it can compress, hash and upload, and nothing about
// pg_dump, mysqldump, unix sockets inside a container or which administrative login is used
// to reach them - all of which belong where the engines are run.
//
// The engine and the database name come from the command rather than from a lookup, because
// RunBackup carries both and a second opinion about which database a backup is of is a way
// to dump the wrong one.
type DatabaseDumps interface {
	// DumpDatabase writes a logical dump of one database to out. It must be consistent
	// without the caller pausing anything: a dump taken inside a transaction is the whole
	// reason a database backup does not need a quiesce window.
	DumpDatabase(ctx context.Context, engine wisperpb.DatabaseEngine, database string, out io.Writer) error

	// RestoreDatabase replays a dump into a database that already exists. The database is
	// emptied of what the dump recreates by the dump itself; this method does not create it.
	RestoreDatabase(ctx context.Context, engine wisperpb.DatabaseEngine, database string, in io.Reader) error

	// CreateEmptyDatabase makes a database with no login of its own, for the dry run that
	// restores beside the live one instead of over it. It is an error if the name is already
	// taken, so a rehearsal can never land in a database somebody is using.
	CreateEmptyDatabase(ctx context.Context, engine wisperpb.DatabaseEngine, database string) error
}
