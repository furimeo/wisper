package dbengine

import (
	"context"

	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/rpc"
	"github.com/furimeo/wisper/sasayaki/internal/runtime"
	"github.com/furimeo/wisper/sasayaki/internal/state"
)

// What this package needs from the rest of the daemon, declared here by the consumer.
//
// Three collaborators and no more. The engine's own API is needed because a database server
// is a container this package creates and the runtime package deliberately refuses to make
// one - everything it builds carries wisper.workload and would be removed by the reconcile
// loop as an orphan on its first pass. Running a command inside a container is the runtime
// package's job and is not duplicated here. The disk is needed for one thing: the spec says
// which engines this node must run and which grants live on them.

// Engine is the part of the Docker Engine API a database server needs.
//
// Listing the methods rather than embedding a client interface is a written statement of
// the surface: this package creates long-lived containers, starts, stops and removes them,
// pulls their images, and joins them to the tenant networks whose workloads have to reach
// them. It never execs through this interface - that is Commands - and it never builds an
// image, touches a volume plugin or reads a log.
//
// *client.Client satisfies it.
type Engine interface {
	ContainerCreate(ctx context.Context, options client.ContainerCreateOptions) (client.ContainerCreateResult, error)
	// ContainerList is filtered to this package's own label. A database server is
	// deliberately invisible to the reconcile loop, so this is the only place on the node
	// that can enumerate them.
	ContainerList(ctx context.Context, options client.ContainerListOptions) (client.ContainerListResult, error)
	ContainerInspect(ctx context.Context, container string, options client.ContainerInspectOptions) (client.ContainerInspectResult, error)
	ContainerStart(ctx context.Context, container string, options client.ContainerStartOptions) (client.ContainerStartResult, error)
	ContainerStop(ctx context.Context, container string, options client.ContainerStopOptions) (client.ContainerStopResult, error)
	ContainerRemove(ctx context.Context, container string, options client.ContainerRemoveOptions) (client.ContainerRemoveResult, error)

	ImageInspect(ctx context.Context, image string, _ ...client.ImageInspectOption) (client.ImageInspectResult, error)
	ImagePull(ctx context.Context, ref string, options client.ImagePullOptions) (client.ImagePullResponse, error)

	// NetworkConnect joins the server to one tenant's network. Customers reach their
	// database by the server's name on the network they are already on; the alternative -
	// a port published on the host - is dropped by the egress filter every tenant bridge
	// carries (runtime/egress.go), so it would not work even if it were safe.
	NetworkConnect(ctx context.Context, network string, options client.NetworkConnectOptions) (client.NetworkConnectResult, error)

	// Close releases the connection. Nothing on the engine is tidied up by it: a database
	// server this daemon created is meant to outlive the daemon.
	Close() error
}

// Commands runs one non-interactive command inside a container and waits for it.
//
// Implemented by *runtime.Docker, whose Run was written for exactly this: argv in, exit code
// and captured output back, standard input fed from a byte slice and closed. Reusing it
// rather than opening a second exec path means the engine's stream multiplexing is decoded
// in one place on this node, and a command that runs past its deadline is killed by pid in
// one place too.
type Commands interface {
	Run(ctx context.Context, containerID string, options runtime.RunOptions) (runtime.RunResult, error)
}

// The one implementation, asserted here rather than discovered in the composition root.
var _ Commands = (*runtime.Docker)(nil)

// Store is the node's disk, as this package needs it: one method.
//
// The spec is the only durable state a database server has here. Which engines to run and
// which grants exist are both in it, and everything else - what is really on the server, how
// big it is, which version it turned out to be - is asked of the server itself. That is why
// there is no dbengine table in SQLite: a second copy of the truth would be a second thing
// to get out of step with the engine.
type Store interface {
	// LoadSpec returns state.ErrNoSpec when the panel has never spoken to this node, which
	// is answered by running nothing rather than by failing.
	LoadSpec(ctx context.Context) (state.StoredSpec, error)
}

// The one implementation, asserted so the composition root needs no adapter.
var _ Store = (*state.Store)(nil)

// The contracts with the rest of the daemon, checked by the compiler.
//
// rpc.DatabaseOperator is the three commands the panel can send; reconcile.Databases is the
// measurement that travels in every status batch. Both are declared by their consumers, Go
// style, and asserting them here turns a signature drifting apart into a build failure
// rather than one confusing line at daemon start.
var (
	_ rpc.DatabaseOperator = (*Engines)(nil)
	_ reconcile.Databases  = (*Engines)(nil)
)

// The labels every container this package creates carries.
//
// LabelManaged is shared with the reconcile loop and means "wisper put this here". What is
// absent matters more: reconcile.LabelWorkload is *not* set, and that is the whole reason a
// shared PostgreSQL survives. The loop lists containers carrying both labels and removes any
// it does not find in the spec, so a database server that looked like a workload would be
// taken out on the first pass after it was created (runtime/list.go).
const (
	// labelInstance carries the engine instance id, which is DatabaseEngineSpec.data_volume_id -
	// the panel's own row id for this server.
	labelInstance = "wisper.database.instance"
	// labelKind is POSTGRES or MYSQL, so an operator reading `docker ps --filter` does not
	// have to resolve an id to know what is running.
	labelKind = "wisper.database.kind"
	// labelFingerprint is the hash of the instance the container was created from, and is
	// how drift is noticed. Shared with the reconcile loop's constant because it means the
	// same thing and an operator should not have to learn two names for it.
	labelFingerprint = reconcile.LabelFingerprint
)
