package edge

import (
	"encoding/json"
	"fmt"
	"strconv"
	"time"

	"github.com/caddyserver/caddy/v2"
	"github.com/caddyserver/caddy/v2/caddyconfig"
	_ "github.com/caddyserver/caddy/v2/modules/caddyevents"
	"github.com/caddyserver/caddy/v2/modules/caddyhttp"
	_ "github.com/caddyserver/caddy/v2/modules/caddyhttp/encode"
	_ "github.com/caddyserver/caddy/v2/modules/caddyhttp/encode/gzip"
	_ "github.com/caddyserver/caddy/v2/modules/caddyhttp/encode/zstd"
	"github.com/caddyserver/caddy/v2/modules/caddytls"
	_ "github.com/caddyserver/caddy/v2/modules/filestorage"
	_ "github.com/caddyserver/caddy/v2/modules/logging"
)

// The Caddy configuration, written once and never reloaded.
//
// This is the whole reason the route table is a table. Caddy's configuration is a
// document that is provisioned as a unit: changing one route means rebuilding every
// route, re-evaluating every automation policy and replacing the whole handler tree. On a
// node where a deployment happens every few minutes that would be constant churn for the
// benefit of one hostname. So none of what changes is in here. What is in here is two
// listeners, compression, an access log and the on-demand certificate policy - and all
// four of those are the same on the first day and the thousandth.
//
// The blank imports above are how Caddy learns that a module exists. Its configuration
// names modules by string, and a module whose package was never linked in is a
// configuration that fails to load with a message about an unknown module rather than a
// compile error, so the list is deliberately explicit rather than the catch-all
// modules/standard import: everything wisper's edge can be configured to do is on it.

// Timeouts.
//
// Only the ones with an unambiguous right answer are set. A header that has not finished
// arriving in ten seconds is a slow-loris or a broken client, and an idle keep-alive held
// for more than two minutes is a file descriptor doing nothing - both are worth cutting.
// A whole-request or whole-response deadline is not set, and that is the deliberate half:
// this edge carries a customer's file upload over a phone's 4G and a customer's log
// stream over server-sent events, and both of those are indistinguishable from a stall
// until the moment they finish.
const (
	readHeaderTimeout = 10 * time.Second
	idleTimeout       = 2 * time.Minute
	maxHeaderBytes    = 1 << 20
)

// caddyConfig builds the document Caddy runs.
func (e *Edge) caddyConfig() (*caddy.Config, error) {
	secure, err := e.server(e.httpsPort, false)
	if err != nil {
		return nil, err
	}
	insecure, err := e.server(e.httpPort, true)
	if err != nil {
		return nil, err
	}

	// A default connection policy with no matcher, so every handshake is served from the
	// automation below. Without one Caddy would not terminate TLS on this listener at
	// all, because automatic HTTPS - which normally writes this policy - is off: it
	// derives its subject list from the routes in the configuration, and every hostname
	// this node serves is in the route table instead.
	secure.TLSConnPolicies = caddytls.ConnectionPolicies{{}}
	secure.AutoHTTPS = &caddyhttp.AutoHTTPSConfig{Disabled: true}
	insecure.AutoHTTPS = &caddyhttp.AutoHTTPSConfig{Disabled: true}

	httpApp, err := json.Marshal(caddyhttp.App{
		Servers: map[string]*caddyhttp.Server{
			"wisper_secure":   secure,
			"wisper_insecure": insecure,
		},
	})
	if err != nil {
		return nil, fmt.Errorf("edge: render the web server configuration: %w", err)
	}

	tlsApp, err := e.tlsApp()
	if err != nil {
		return nil, err
	}

	// Caddy writes a copy of every configuration it loads into an application data
	// directory derived from the environment, so that a `caddy run --resume` can pick it
	// up. Nothing here resumes: this configuration is built from scratch in this process
	// on every start. Left on, it would be a write outside the state directory - which a
	// hardened systemd unit with ReadWritePaths=/var/lib/wisper /etc/wisper forbids -
	// producing an error in the log of every node, every boot, for a file nobody reads.
	noPersist := false

	return &caddy.Config{
		// No admin endpoint. It is a socket that can change this daemon's entire
		// configuration, it defaults to a fixed local port, and nothing here uses it:
		// the configuration is built in this process and never edited from outside it.
		Admin: &caddy.AdminConfig{
			Disabled: true,
			Config:   &caddy.ConfigSettings{Persist: &noPersist},
		},
		Logging:    e.logging(),
		StorageRaw: e.storageForCertificates(),
		AppsRaw: caddy.ModuleMap{
			"http": httpApp,
			"tls":  tlsApp,
		},
	}, nil
}

// server is one listener.
func (e *Edge) server(port int, plaintext bool) (*caddyhttp.Server, error) {
	handlers := []json.RawMessage{
		// Compression before the handler, so it applies to a proxied application and a
		// static release alike without either having to opt in. gzip and zstd: between
		// them every browser and every HTTP client in use is covered, and adding brotli
		// would mean a dependency outside Caddy's own module set for a few per cent.
		caddyconfig.JSON(map[string]any{
			"handler":   "encode",
			"encodings": map[string]any{"zstd": map[string]any{}, "gzip": map[string]any{}},
			// Below about a kilobyte the compressed form is usually bigger, and always
			// costs a round of CPU on both ends.
			"minimum_length": 1024,
		}, nil),
		caddyconfig.JSON(map[string]any{
			"handler":   "wisper_edge",
			"edge":      e.id,
			"plaintext": plaintext,
		}, nil),
	}

	for index, handler := range handlers {
		if handler == nil {
			return nil, fmt.Errorf("edge: handler %d of the request chain could not be "+
				"rendered as configuration", index)
		}
	}

	return &caddyhttp.Server{
		Listen:            []string{":" + strconv.Itoa(port)},
		ReadHeaderTimeout: caddy.Duration(readHeaderTimeout),
		IdleTimeout:       caddy.Duration(idleTimeout),
		MaxHeaderBytes:    maxHeaderBytes,
		// One route matching everything. Which hostname goes where is the route table's
		// business, not Caddy's, so there is nothing here to match on.
		Routes: caddyhttp.RouteList{{HandlersRaw: handlers, Terminal: true}},
		Logs:   &caddyhttp.ServerLogConfig{DefaultLoggerName: accessLoggerName},
	}, nil
}

// tlsApp is the certificate policy: on-demand for everything, decided in-process.
func (e *Edge) tlsApp() (json.RawMessage, error) {
	issuer := map[string]any{"module": "acme"}
	if e.acmeEmail != "" {
		issuer["email"] = e.acmeEmail
	}
	if e.acmeDirectory != "" {
		issuer["ca"] = e.acmeDirectory
	}

	policy := map[string]any{
		// No "subjects": the policy applies to every name, and which names are allowed is
		// the permission module's answer rather than a list that would have to be kept in
		// step with the route table.
		"on_demand": true,
		"issuers":   []any{issuer},
	}

	app := map[string]any{
		"automation": map[string]any{
			"policies": []any{policy},
			"on_demand": map[string]any{
				"permission": map[string]any{
					"module": "wisper_edge",
					"edge":   e.id,
				},
			},
		},
	}

	rendered, err := json.Marshal(app)
	if err != nil {
		return nil, fmt.Errorf("edge: render the certificate policy: %w", err)
	}
	return rendered, nil
}

// logging sends the access log to a file the daemon can ship.
//
// A file rather than this process's own logger, for two reasons. It is one line per
// request on a machine serving every customer on the node, which would drown the daemon's
// own log; and shipping it is somebody else's job, done by reading a file, which works
// across a restart in a way an in-memory buffer does not.
//
// Caddy's own runtime log is left where it is - standard error, which under systemd is
// the journal - because it is operator output, and an operator reading `journalctl -u
// sasayaki` should see the daemon and its edge in one stream.
func (e *Edge) logging() *caddy.Logging {
	writer := caddyconfig.JSON(map[string]any{
		"output":       "file",
		"filename":     e.accessLogPath(),
		"roll":         true,
		"roll_size_mb": accessLogRollSizeMB,
		"roll_keep":    accessLogRollKeep,
		"roll_gzip":    true,
		// 0600: an access log names every hostname this node serves and every address
		// that visited them.
		"mode": "0600",
	}, nil)

	return &caddy.Logging{
		Logs: map[string]*caddy.CustomLog{
			accessLoggerName: {
				BaseLog: caddy.BaseLog{
					WriterRaw:  writer,
					EncoderRaw: caddyconfig.JSON(map[string]any{"format": "json"}, nil),
					Level:      "INFO",
				},
				Include: []string{"http.log.access." + accessLoggerName},
			},
			// Without this the default log would also carry every access line, because a
			// named access logger is additive rather than exclusive.
			"default": {
				BaseLog: caddy.BaseLog{Level: "INFO"},
				Exclude: []string{"http.log.access." + accessLoggerName},
			},
		},
	}
}

// storageForCertificates is where certmagic keeps keys and certificates.
//
// Under the node's state directory rather than Caddy's own default, which is a
// per-user path in $HOME or $XDG_DATA_HOME: a daemon that runs as root under systemd and
// then gets restarted with a different HOME would quietly start asking a certificate
// authority for everything again.
func (e *Edge) storageForCertificates() json.RawMessage {
	return caddyconfig.JSON(map[string]any{
		"module": "file_system",
		"root":   e.certificateDir(),
	}, nil)
}
