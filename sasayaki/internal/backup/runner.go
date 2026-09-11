package backup

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"path/filepath"
	"sync"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/rpc"
)

// Building a Runner, and refusing to build one that cannot finish a backup.
//
// Every collaborator is required. A nil workload runtime would not fail here; it would fail
// at three in the morning during the first scheduled backup, at the exact moment nobody is
// watching and the customer's application is about to be paused.

// The contract with the control stream, checked by the compiler. rpc.BackupRunner is declared
// by its consumer, Go style, and implemented here.
var _ rpc.BackupRunner = (*Runner)(nil)

const (
	// defaultTimeout applies when the panel sends none. Two hours is long enough for a large
	// volume over a modest uplink and short enough that a stuck backup does not still be
	// running when the next night's one starts (backup.proto, RunBackup.timeout_seconds).
	defaultTimeout = 2 * time.Hour

	// responseHeaderTimeout is how long the object store gets to begin answering. Separate
	// from any timeout on the whole request, which must not exist: a single part can
	// legitimately take minutes, and a client-side deadline over the body is how an upload
	// dies at 90% every time on a slow line.
	responseHeaderTimeout = 60 * time.Second
)

// Options is everything a Runner is made of.
type Options struct {
	// StateDir is /var/lib/wisper. Volumes are read from it, archives are staged in it, and
	// nothing outside it is ever written.
	StateDir string

	// Store is the node's SQLite: idempotency, stages, and the record a crash leaves behind.
	Store Store

	// Workloads is the container runtime. A volume backup pauses through it and a restore
	// stops and starts through it.
	Workloads Workloads

	// Databases is the logical half, implemented by the dbengine package.
	Databases DatabaseDumps

	// HTTP is the client S3 destinations are reached through. Left nil, one is built with no
	// overall request timeout and a header timeout instead.
	HTTP *http.Client

	// Retry is how a transient failure at the destination is handled. The zero value is the
	// default schedule; tests replace Sleep so a retry costs no wall-clock time.
	Retry retryPolicy

	// PartSize is the multipart part size. Zero means 16 MiB.
	//
	// Every real S3 implementation refuses a part below five mebibytes unless it is the last
	// one, so production must not lower it. It is settable because a test that exercised the
	// multipart path at the real size would have to write eighty megabytes to do it.
	PartSize int64

	// Logger defaults to slog.Default. The daemon passes one that knows the node id.
	Logger *slog.Logger

	// Now defaults to time.Now, replaced in tests so an archive can be placed at an exact
	// moment - which matters here more than anywhere, because the object name carries the
	// time and retention is arithmetic over it.
	Now func() time.Time
}

func (o Options) validate() error {
	missing := make([]string, 0, 3)
	if o.Store == nil {
		missing = append(missing, "Store")
	}
	if o.Workloads == nil {
		missing = append(missing, "Workloads")
	}
	if o.Databases == nil {
		missing = append(missing, "Databases")
	}
	if len(missing) > 0 {
		return fmt.Errorf("backup.Options is missing %v: a node that answers RunBackup without "+
			"all of these is one that reports a backup it did not take", missing)
	}
	if o.StateDir == "" {
		return errors.New("backup: the state directory is empty, so no volume could be resolved safely")
	}
	if !filepath.IsAbs(o.StateDir) {
		return fmt.Errorf("backup: the state directory %q is not absolute, and a relative one "+
			"resolves against whatever directory the daemon happens to have been started in",
			o.StateDir)
	}
	return nil
}

// Runner takes snapshots and puts them back. Safe for concurrent use; one subject at a time.
type Runner struct {
	stateDir  string
	store     Store
	workloads Workloads
	databases DatabaseDumps
	http      *http.Client
	retry     retryPolicy
	partSize  int64
	log       *slog.Logger
	now       func() time.Time

	// running serialises work on one subject. Two backups of one volume would pause the same
	// application twice and race over one staging file; a backup and a restore of it at once
	// would copy a directory that is being overwritten.
	running sync.Map
}

// New opens a runner. Nothing is created on disk until a backup runs.
func New(options Options) (*Runner, error) {
	if err := options.validate(); err != nil {
		return nil, err
	}

	runner := &Runner{
		stateDir:  options.StateDir,
		store:     options.Store,
		workloads: options.Workloads,
		databases: options.Databases,
		http:      options.HTTP,
		retry:     options.Retry,
		partSize:  options.PartSize,
		log:       options.Logger,
		now:       options.Now,
	}
	if runner.log == nil {
		runner.log = slog.Default()
	}
	if runner.now == nil {
		runner.now = time.Now
	}
	if runner.partSize <= 0 {
		runner.partSize = defaultPartSize
	}
	if runner.retry.Attempts == 0 {
		runner.retry = defaultRetry()
	}
	if runner.http == nil {
		runner.http = newHTTPClient()
	}
	return runner, nil
}

// newHTTPClient is the client an S3 destination is reached through.
//
// Deliberately without a Timeout. http.Client.Timeout covers reading the body, and a
// sixteen-megabyte part on a slow line takes as long as it takes; a client that gave up on it
// would fail every backup on exactly the connections that need multipart uploads most. The
// deadlines that do exist are on the parts of a request that cannot legitimately be slow:
// opening the connection, the TLS handshake, and the store's first response header.
func newHTTPClient() *http.Client {
	return &http.Client{
		Transport: &http.Transport{
			Proxy:                 http.ProxyFromEnvironment,
			DialContext:           (&net.Dialer{Timeout: 30 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
			TLSHandshakeTimeout:   15 * time.Second,
			ResponseHeaderTimeout: responseHeaderTimeout,
			ExpectContinueTimeout: 5 * time.Second,
			MaxIdleConnsPerHost:   4,
			ForceAttemptHTTP2:     true,
		},
	}
}

// lockSubject takes the per-subject lock, waiting for the context rather than forever: a
// backup queued behind one that is stuck must fail with its own timeout instead of holding a
// command goroutine until the daemon restarts.
func (r *Runner) lockSubject(ctx context.Context, subjectID string) (func(), error) {
	value, _ := r.running.LoadOrStore(subjectID, make(chan struct{}, 1))
	gate, ok := value.(chan struct{})
	if !ok {
		return nil, fmt.Errorf("backup: the lock for subject %s is not a lock", subjectID)
	}

	select {
	case gate <- struct{}{}:
		return func() { <-gate }, nil
	case <-ctx.Done():
		return nil, fmt.Errorf("backup: waited for the operation already running on subject %s: %w",
			subjectID, ctx.Err())
	}
}
