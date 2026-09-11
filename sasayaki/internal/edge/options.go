package edge

import (
	"fmt"
	"log/slog"
	"path/filepath"
	"sync/atomic"
	"time"
)

// Building an edge, and refusing to build one that cannot serve.
//
// Every collaborator is required. A nil field would not fail here; it would fail on the
// first visitor, on a listener that is already accepting connections, which is both the
// least useful moment to find out and the one where somebody else's customer notices.

// Ports the node listens on. Constants rather than settings because they are not a
// choice: :443 is where browsers go and :80 is where the ACME HTTP-01 challenge is
// answered, so a node that moved either of them would stop being able to serve or stop
// being able to get a certificate. Tests override them to keep off a developer's ports.
const (
	DefaultHTTPPort  = 80
	DefaultHTTPSPort = 443
)

// Directory names under the node's state root.
//
// They are part of the on-disk layout the whole platform agrees on (design section 5.5):
// the builder writes releases into sites/<workload>/releases/<release>/ and points
// sites/<workload>/current at one of them, and this package reads through that symlink.
// Certificates live beside them so that one directory is the whole of a node's state.
const (
	sitesDirName        = "sites"
	publishedLinkName   = "current"
	certificateDirName  = "certificates"
	logDirName          = "logs"
	accessLogFileName   = "access.log"
	accessLoggerName    = "wisper_access"
	accessLogRollSizeMB = 32
	accessLogRollKeep   = 5
)

// Options is everything an edge is built from.
type Options struct {
	// StateDir is /var/lib/wisper. Sites, certificates and access logs hang off it.
	StateDir string

	// Backends resolves an app route to the address of its container.
	Backends Backends

	// Store keeps certificate metadata across a restart.
	Store Store

	// Logger defaults to slog.Default. The daemon passes one that knows the node id.
	Logger *slog.Logger

	// HTTPPort and HTTPSPort default to 80 and 443.
	HTTPPort  int
	HTTPSPort int

	// ACMEEmail is the account contact the certificate authority is given. Empty is
	// allowed and means an anonymous account: Let's Encrypt accepts one, and the address
	// only buys expiry warnings by mail, which this platform sends itself.
	ACMEEmail string

	// ACMEDirectory overrides the certificate authority. Set to the staging directory on
	// a node being tested, because the production rate limit is per registered domain and
	// a rebuild loop will spend it in an afternoon.
	ACMEDirectory string

	// Now defaults to time.Now. Replaced in tests so a certificate can be placed at an
	// exact moment without anything having to sleep.
	Now func() time.Time
}

func (o Options) validate() error {
	missing := make([]string, 0, 3)
	if o.StateDir == "" {
		missing = append(missing, "StateDir")
	}
	if o.Backends == nil {
		missing = append(missing, "Backends")
	}
	if o.Store == nil {
		missing = append(missing, "Store")
	}
	if len(missing) > 0 {
		return fmt.Errorf("edge.Options is missing %v: the edge uses every one of these to "+
			"answer a request, so a missing field is a failure on the first visitor rather "+
			"than at startup", missing)
	}
	if !filepath.IsAbs(o.StateDir) {
		return fmt.Errorf("edge: the state directory %q is not absolute, and a relative one "+
			"resolves against whatever directory the daemon happens to have been started in",
			o.StateDir)
	}
	if o.HTTPPort < 0 || o.HTTPPort > 65535 || o.HTTPSPort < 0 || o.HTTPSPort > 65535 {
		return fmt.Errorf("edge: ports %d and %d are not both port numbers", o.HTTPPort, o.HTTPSPort)
	}
	return nil
}

// edgeSequence names each edge in this process so the Caddy modules Caddy builds from
// JSON can find the one they belong to (registry.go). A counter rather than a random
// value: the number appears in log lines and in a configuration a person may have to
// read, and "edge-1" is friendlier there than sixteen hex digits.
var edgeSequence atomic.Uint64

// New builds an edge. Nothing listens until Start.
func New(options Options) (*Edge, error) {
	if err := options.validate(); err != nil {
		return nil, err
	}

	e := &Edge{
		id:            fmt.Sprintf("edge-%d", edgeSequence.Add(1)),
		stateDir:      options.StateDir,
		backends:      options.Backends,
		store:         options.Store,
		log:           options.Logger,
		httpPort:      options.HTTPPort,
		httpsPort:     options.HTTPSPort,
		acmeEmail:     options.ACMEEmail,
		acmeDirectory: options.ACMEDirectory,
		now:           options.Now,
		routes:        newRouter(),
	}
	if e.log == nil {
		e.log = slog.Default()
	}
	if e.now == nil {
		e.now = time.Now
	}
	if e.httpPort == 0 {
		e.httpPort = DefaultHTTPPort
	}
	if e.httpsPort == 0 {
		e.httpsPort = DefaultHTTPSPort
	}
	e.certificates = newCertificates(e.now)

	register(e)
	return e, nil
}

// sitesDir is where every site's releases live.
func (e *Edge) sitesDir() string { return filepath.Join(e.stateDir, sitesDirName) }

// publishedDir is the directory a site is serving right now: the symlink the builder
// swaps, not a release directory, so a publish that happens between two requests is
// picked up by the second one.
func (e *Edge) publishedDir(workloadID string) string {
	return filepath.Join(e.sitesDir(), workloadID, publishedLinkName)
}

// certificateDir is certmagic's storage root.
func (e *Edge) certificateDir() string { return filepath.Join(e.stateDir, certificateDirName) }

// accessLogPath is the file the daemon can ship.
func (e *Edge) accessLogPath() string {
	return filepath.Join(e.stateDir, logDirName, accessLogFileName)
}
