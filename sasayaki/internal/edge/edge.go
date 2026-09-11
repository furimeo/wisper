package edge

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	"github.com/caddyserver/caddy/v2"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
)

// The reconcile loop's Edge, asserted here rather than discovered at wiring time. Sync
// and Statuses are written against that interface's signatures precisely so that *Edge
// satisfies it with no adapter in between, and this line is what keeps that true when
// either side is edited.
var _ reconcile.Edge = (*Edge)(nil)

// Edge is the embedded web server: one per daemon.
//
// Safe for concurrent use, and it has to be - a reconcile pass calls Sync while visitors
// are being served and TLS handshakes are asking whether a hostname is ours. The
// concurrency is deliberately concentrated in one place: routes holds an immutable
// snapshot behind an atomic pointer, and everything else here is either written once at
// construction or guarded by its own small lock.
type Edge struct {
	// id is how the Caddy modules built from JSON find this instance (registry.go).
	id string

	stateDir string
	backends Backends
	store    Store
	log      *slog.Logger
	now      func() time.Time

	httpPort      int
	httpsPort     int
	acmeEmail     string
	acmeDirectory string

	// routes is the published table. The only thing on the request path.
	routes *router
	// certificates is what issuance has been observed doing, per hostname.
	certificates *certificates

	// building serialises Sync against itself. Two passes never overlap in the reconcile
	// loop, but Sync also runs from Start, and building two tables at once would leak the
	// backends of whichever one lost the swap.
	building sync.Mutex

	// started is whether Caddy is running this edge's configuration. Read by Statuses,
	// which must not touch Caddy's certificate cache before the TLS app has made one.
	started atomic.Bool
}

// running is the edge whose configuration Caddy currently holds.
//
// Caddy's configuration is process-global: caddy.Run replaces whatever was loaded before,
// wherever it came from. That is fine for a daemon, which has exactly one edge, but it
// means a second Start would silently take the first one's listeners away. Refusing is
// the only honest behaviour, and it turns a confusing outage into an error at startup.
var running struct {
	sync.Mutex
	edge *Edge
}

// Start brings up the listeners and begins serving whatever the route table holds.
//
// The Caddy configuration it loads is fixed for the life of the process. Everything that
// changes about what this node serves changes in the route table instead, which is why
// Sync is cheap enough to call on every reconcile pass that touched a container.
func (e *Edge) Start(ctx context.Context) error {
	running.Lock()
	defer running.Unlock()
	if running.edge != nil {
		return fmt.Errorf("edge: %s is already serving on this process's listeners, and "+
			"Caddy's configuration is process-wide, so starting %s would take them away from it",
			running.edge.id, e.id)
	}

	if err := e.prepareDirectories(); err != nil {
		return err
	}
	if err := e.seedCertificates(ctx); err != nil {
		return err
	}

	config, err := e.caddyConfig()
	if err != nil {
		return err
	}
	if err := caddy.Run(config); err != nil {
		return fmt.Errorf("edge: start the embedded web server on :%d and :%d: %w",
			e.httpPort, e.httpsPort, err)
	}

	running.edge = e
	e.started.Store(true)
	e.log.Info("the edge is listening",
		slog.Int("http_port", e.httpPort),
		slog.Int("https_port", e.httpsPort),
		slog.String("certificates", e.certificateDir()),
		slog.String("access_log", e.accessLogPath()))
	return nil
}

// Stop takes the listeners down.
//
// Called on a clean shutdown so that an upgrade's restart does not have to wait for the
// kernel to release :443. It is not cleanup in the crash-only sense: nothing here has to
// happen for the node to be correct after being killed, because every byte the edge owns
// is either on disk already or a connection that was going to end anyway.
func (e *Edge) Stop(context.Context) error {
	running.Lock()
	defer running.Unlock()
	if running.edge != e {
		// Never started, or already stopped. Both are the state the caller asked for.
		return nil
	}

	e.started.Store(false)
	running.edge = nil
	if err := caddy.Stop(); err != nil {
		return fmt.Errorf("edge: stop the embedded web server: %w", err)
	}

	// The published table's backends are holding keep-alive connections to containers
	// that are about to be someone else's problem.
	e.routes.swap(emptyTable()).releaseBackendsAbsentFrom(emptyTable())
	e.log.Info("the edge has stopped listening")
	return nil
}

// prepareDirectories makes the three directories the edge writes into.
//
// 0700 on all of them. Certificate private keys are under the first one, and an access
// log names every hostname on the node, which is a list of a competitor's customers.
func (e *Edge) prepareDirectories() error {
	for _, directory := range []string{
		e.sitesDir(),
		e.certificateDir(),
		filepath.Dir(e.accessLogPath()),
	} {
		if err := os.MkdirAll(directory, 0o700); err != nil {
			return fmt.Errorf("edge: create %s: %w", directory, err)
		}
	}
	return nil
}

// seedCertificates loads what was known about each hostname before the daemon restarted.
//
// Without it the first status batch after a restart would report every domain as unknown
// until a visitor happened to arrive, and a customer watching their domain come up would
// see it go backwards. A store that cannot be read is not a reason to refuse to serve:
// the certificates themselves are certmagic's and are on disk regardless, so this is
// logged and the edge starts anyway.
func (e *Edge) seedCertificates(ctx context.Context) error {
	stored, err := e.store.Certificates(ctx)
	if err != nil {
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			return fmt.Errorf("edge: read the stored certificate records: %w", err)
		}
		e.log.Warn("starting without the certificate records from the last run, so hostnames "+
			"will be reported as unknown until a visitor arrives",
			slog.String("error", err.Error()))
		return nil
	}
	e.certificates.seed(stored)
	return nil
}
