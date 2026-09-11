package dbengine

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"path/filepath"
	"sync"
	"time"

	"github.com/moby/moby/client"
)

// Building the database layer, and refusing to build one that cannot answer a customer.
//
// Every collaborator is required. A nil one would not fail here; it would fail the first time
// somebody pressed "create database", minutes after the daemon came up clean, which is both
// the least useful moment to find out and the one where a person is watching a spinner.

// DefaultInterval is how often the servers are reconciled when the spec does not say.
//
// The same fifteen seconds as the workload loop, for the same reason: a node converges on a
// timer whether or not anything happened, because that is what makes it self-healing rather
// than dependent on a message it may not have received.
const DefaultInterval = 15 * time.Second

// Options is everything the database layer is made of.
type Options struct {
	// StateDir is /var/lib/wisper. Every data directory and every dump in flight is resolved
	// under it, and nothing outside it is ever a mount source.
	StateDir string

	// Engine is the Docker Engine API. Left nil, one is opened from the environment, which is
	// what the daemon wants and what a test replaces.
	Engine Engine

	// Commands runs a statement inside a server's container. Implemented by *runtime.Docker.
	Commands Commands

	// Store is the node's SQLite, for the spec.
	Store Store

	// Logger defaults to slog.Default. The daemon passes one that knows the node id.
	Logger *slog.Logger

	// Now defaults to time.Now, replaced in tests so a measurement can be placed at an exact
	// moment without anything having to sleep.
	Now func() time.Time

	// Interval is the reconcile tick for the servers. Zero means the interval the spec asked
	// for, and DefaultInterval when it asked for nothing.
	Interval time.Duration
}

func (o Options) validate() error {
	missing := make([]string, 0, 2)
	if o.Commands == nil {
		missing = append(missing, "Commands")
	}
	if o.Store == nil {
		missing = append(missing, "Store")
	}
	if len(missing) > 0 {
		return fmt.Errorf("dbengine.Options is missing %v: every database operation uses all of "+
			"these, so a missing one is a customer watching a spinner rather than a daemon that "+
			"refuses to start", missing)
	}
	if o.StateDir == "" {
		return errors.New("dbengine: the state directory is empty, so no data directory could be " +
			"resolved safely")
	}
	if !filepath.IsAbs(o.StateDir) {
		return fmt.Errorf("dbengine: the state directory %q is not absolute, and a relative one "+
			"resolves against whatever directory the daemon happened to be started in", o.StateDir)
	}
	return nil
}

// Engines is the database layer of one node.
//
// Safe for concurrent use: the reconcile loop asks it for sizes every fifteen seconds while
// the control stream may be provisioning, rotating and dropping at the same time. Convergence
// is serialised by one mutex - two passes creating the same container would have one of them
// fail on a name conflict - and everything else is serialised only against convergence, so a
// slow image pull does not hold up a measurement.
type Engines struct {
	engine   Engine
	commands Commands
	store    Store
	stateDir string
	log      *slog.Logger
	now      func() time.Time
	interval time.Duration

	// converging is held for the length of one convergence pass. Provisioning takes it too,
	// because a customer pressing the button on a node whose server has not been created yet
	// should get a database rather than an error telling them to wait.
	converging sync.Mutex

	// hardened records the container id each instance was last hardened against, so the
	// idempotent lockdown runs once per container rather than once every fifteen seconds. A
	// recreated container has a new id and is hardened again.
	hardened struct {
		sync.Mutex
		byInstance map[string]string
	}

	// failures is the last thing that went wrong per grant, which is what DatabaseStatus
	// carries back so the panel can show a customer why their database is not there. Kept in
	// memory on purpose: after a restart the node has no opinion until it has looked, and an
	// error remembered across a restart would outlive the condition that caused it.
	failures struct {
		sync.Mutex
		byGrant map[string]string
	}
}

// New opens the database layer. Nothing is created on disk or on the engine until the first
// convergence.
func New(options Options) (*Engines, error) {
	if err := options.validate(); err != nil {
		return nil, err
	}

	engines := &Engines{
		engine:   options.Engine,
		commands: options.Commands,
		store:    options.Store,
		stateDir: options.StateDir,
		log:      options.Logger,
		now:      options.Now,
		interval: options.Interval,
	}
	engines.hardened.byInstance = make(map[string]string)
	engines.failures.byGrant = make(map[string]string)

	if engines.log == nil {
		engines.log = slog.Default()
	}
	if engines.now == nil {
		engines.now = time.Now
	}
	if engines.engine == nil {
		api, err := client.NewClientWithOpts(client.FromEnv, client.WithAPIVersionNegotiation())
		if err != nil {
			return nil, fmt.Errorf("dbengine: prepare a Docker client: %w", err)
		}
		engines.engine = api
	}
	return engines, nil
}

// recordFailure remembers why a grant is not what the panel asked for.
func (e *Engines) recordFailure(grantID, detail string) {
	if grantID == "" {
		return
	}
	e.failures.Lock()
	defer e.failures.Unlock()
	e.failures.byGrant[grantID] = detail
}

// clearFailure forgets a grant's last problem, which is what a successful operation does.
func (e *Engines) clearFailure(grantID string) {
	e.failures.Lock()
	defer e.failures.Unlock()
	delete(e.failures.byGrant, grantID)
}

// failure is the last thing that went wrong for one grant, or "".
func (e *Engines) failure(grantID string) string {
	e.failures.Lock()
	defer e.failures.Unlock()
	return e.failures.byGrant[grantID]
}

// forgetFailures drops the record for every grant that is no longer in the spec, so a node
// that has been running for a month is not holding errors about databases that were deleted
// three weeks ago.
func (e *Engines) forgetFailures(keep map[string]bool) {
	e.failures.Lock()
	defer e.failures.Unlock()
	for id := range e.failures.byGrant {
		if !keep[id] {
			delete(e.failures.byGrant, id)
		}
	}
}

// needsHardening reports whether this container has had the one-time lockdown applied, and
// records that it is about to be.
func (e *Engines) needsHardening(instanceID, containerID string) bool {
	e.hardened.Lock()
	defer e.hardened.Unlock()
	if e.hardened.byInstance[instanceID] == containerID {
		return false
	}
	e.hardened.byInstance[instanceID] = containerID
	return true
}

// hardeningFailed forgets that a container was hardened, so the next pass tries again. Called
// when the statements did not land; leaving the record in place would mean a server whose
// administrative database is open to every login on it and nothing that ever retries.
func (e *Engines) hardeningFailed(instanceID string) {
	e.hardened.Lock()
	defer e.hardened.Unlock()
	delete(e.hardened.byInstance, instanceID)
}

// Close releases the engine connection.
//
// Nothing is tidied up on the engine itself. sasayaki is crash-only: a database server it
// created is meant to outlive it, and a daemon that stopped every customer's database on the
// way out would make an upgrade an outage (AGENTS.md section 4.4).
func (e *Engines) Close() error {
	if err := e.engine.Close(); err != nil {
		return fmt.Errorf("dbengine: close the Docker connection: %w", err)
	}
	return nil
}

// tick is the interval to converge on, honouring the spec's own figure when it has one.
func (e *Engines) tick(ctx context.Context) time.Duration {
	if e.interval > 0 {
		return e.interval
	}
	desired, err := e.desired(ctx)
	if err != nil || desired.ReconcileInterval <= 0 {
		return DefaultInterval
	}
	return desired.ReconcileInterval
}
