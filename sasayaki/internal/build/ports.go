package build

import (
	"context"
	"io"
	"time"

	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// What this package needs from the rest of the daemon, declared here by the consumer.
//
// The direction is the point: build knows what a build is and knows nothing about gRPC,
// SQLite or the file manager's root table. Each interface is implemented in the package
// that owns the work, and each is small enough that a test can stand in for it with no
// Docker and no disk beyond a temporary directory.

// Engine is the part of the Docker Engine API a build uses.
//
// Listing the methods rather than embedding a client interface is a written statement of
// the surface: this package creates short-lived containers, reads their output, waits for
// them and removes them, and it builds and tags images. It does not touch networks, does
// not exec into anything and never asks the engine to copy a file into a container.
//
// *client.Client satisfies it. runtime.Docker deliberately does not: that package's own
// engine interface says "this daemon does not build images", which is true of every
// container a customer runs and false of exactly this one job.
type Engine interface {
	ContainerCreate(ctx context.Context, options client.ContainerCreateOptions) (client.ContainerCreateResult, error)
	// ContainerList is used for exactly one thing: sweeping the build containers a killed
	// daemon left running (workspace.go). It is filtered to this package's own label.
	ContainerList(ctx context.Context, options client.ContainerListOptions) (client.ContainerListResult, error)
	ContainerStart(ctx context.Context, container string, options client.ContainerStartOptions) (client.ContainerStartResult, error)
	ContainerLogs(ctx context.Context, container string, options client.ContainerLogsOptions) (client.ContainerLogsResult, error)
	ContainerWait(ctx context.Context, container string, options client.ContainerWaitOptions) client.ContainerWaitResult
	ContainerRemove(ctx context.Context, container string, options client.ContainerRemoveOptions) (client.ContainerRemoveResult, error)

	ImageInspect(ctx context.Context, image string, _ ...client.ImageInspectOption) (client.ImageInspectResult, error)
	ImagePull(ctx context.Context, ref string, options client.ImagePullOptions) (client.ImagePullResponse, error)
	ImageBuild(ctx context.Context, context io.Reader, options client.ImageBuildOptions) (client.ImageBuildResult, error)
	ImageTag(ctx context.Context, options client.ImageTagOptions) (client.ImageTagResult, error)

	Info(ctx context.Context, options client.InfoOptions) (client.SystemInfoResult, error)
}

// Store is the node's disk, as a build needs it.
//
// Two jobs. It makes a resent StartBuild idempotent - the control stream drops, the panel
// sends the command again, and a second clone and compile of the same commit is minutes
// taken from the customers sharing this machine. And it holds the retention policy the
// panel published, which is how many releases this node keeps.
type Store interface {
	// BeginBuild records that a build has started, or returns state.ErrAlreadyExists when
	// that id has been seen before.
	BeginBuild(ctx context.Context, run state.BuildRun) error
	RecordBuildStage(ctx context.Context, buildID string, stage wisperpb.BuildStage, at time.Time, detail string) error
	FinishBuild(ctx context.Context, buildID string, completed *wisperpb.BuildCompleted, at time.Time) error
	// Build is one run, for replaying the answer to a command the panel sent twice.
	Build(ctx context.Context, buildID string) (state.BuildRun, error)
	// UnfinishedBuilds is every run that was in flight, which after a restart means every
	// run that died with the daemon (recover.go).
	UnfinishedBuilds(ctx context.Context) ([]state.BuildRun, error)
	// LoadSpec carries RetentionPolicy. state.ErrNoSpec means the panel has never spoken
	// to this node, which is answered with the built-in floor rather than with a failure.
	LoadSpec(ctx context.Context) (state.StoredSpec, error)
}

// The one implementation, asserted here rather than discovered in the composition root.
// Store is written in the state package's own signatures precisely so that *state.Store
// satisfies it with no adapter; this line is what keeps that true when either side moves.
var _ Store = (*state.Store)(nil)

// Uploads resolves a finished chunked upload to the file it produced.
//
// A zip reaches the node the way every other file does - a resumable chunked upload into
// the staging FileRoot - and the mapping from a root id to a directory belongs to the
// files package, which owns that tree. This package is handed the path and opens it; it
// does not learn where roots live, because a second opinion about that is a second place
// for a path-traversal bug to live (design section 8.2).
type Uploads interface {
	// ArchivePath is the absolute path of the file a completed upload session produced.
	// An error for a session that does not exist, is not finished, or whose bytes are no
	// longer on disk.
	ArchivePath(ctx context.Context, sessionID string) (string, error)
}

// LogSink is where build output goes while it is happening.
//
// Implemented by *rpc.Client. The boolean is not decoration: a queue that is full drops
// the chunk, and the next one that gets through carries dropped_bytes so the customer is
// told there is a gap rather than being shown one (stats.proto, LogChunk).
type LogSink interface {
	SendLog(chunk *wisperpb.LogChunk) bool
}
