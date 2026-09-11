package edge

import (
	"fmt"
	"net"
	"net/http"
	"strconv"

	"github.com/caddyserver/caddy/v2"
	"github.com/caddyserver/caddy/v2/modules/caddyhttp"
)

// The Caddy handler that turns a request into a backend.
//
// One module, used by both listeners, because the lookup is the same on each and only the
// tail differs: on :443 the matched route is served, on :80 it is either redirected or
// served depending on what the panel said about that hostname. Two modules would be two
// copies of the host matching, and the day they disagreed would be the day one listener
// started serving a hostname the other had withdrawn.
//
// Everything it needs is behind one atomic load. No lock is taken on the request path,
// and the snapshot it reads cannot change underneath it.

func init() {
	caddy.RegisterModule(dispatcher{})
}

// dispatcher is `http.handlers.wisper_edge` in a Caddy configuration.
type dispatcher struct {
	// Edge names the edge this handler routes for. Caddy builds modules from JSON and
	// there is no way to hand it a Go value, so the connection is made by name through
	// the registry.
	Edge string `json:"edge"`

	// Plaintext marks the handler on :80. It is the listener that has to decide between
	// redirecting and serving, because a hostname whose DNS has not arrived yet is told
	// not to redirect: sending a visitor to a handshake that will fail turns "not set up
	// yet" into "broken".
	Plaintext bool `json:"plaintext,omitempty"`

	edge *Edge
}

func (dispatcher) CaddyModule() caddy.ModuleInfo {
	return caddy.ModuleInfo{
		ID:  "http.handlers.wisper_edge",
		New: func() caddy.Module { return new(dispatcher) },
	}
}

func (d *dispatcher) Provision(caddy.Context) error {
	edge, err := lookup(d.Edge)
	if err != nil {
		return err
	}
	d.edge = edge
	return nil
}

func (d *dispatcher) ServeHTTP(w http.ResponseWriter, r *http.Request, _ caddyhttp.Handler) error {
	d.edge.serve(w, r, d.Plaintext)
	return nil
}

// serve is the request path, and it is short on purpose.
//
// Two map lookups and a call. Anything expensive - resolving a container address,
// deciding whether a release exists - happened when the table was built, because this
// runs on every request on the node and the table is rebuilt only when something changed.
func (e *Edge) serve(w http.ResponseWriter, r *http.Request, plaintext bool) {
	host := normaliseHost(r.Host)
	matched, found := e.routes.load().match(host, r.URL.Path)
	if !found {
		unknownHostPage(w, host)
		return
	}

	if plaintext && matched.route.ForceHTTPS {
		e.redirectToHTTPS(w, r, host)
		return
	}
	matched.backend.serve.ServeHTTP(w, r)
}

// redirectToHTTPS sends a plain request to the secure listener.
//
// 308 rather than 301: it is the redirect that forbids a client from turning a POST into
// a GET, and a form posted over plain HTTP that silently loses its body on the way to
// HTTPS is a bug that only shows up in production.
func (e *Edge) redirectToHTTPS(w http.ResponseWriter, r *http.Request, host string) {
	authority := host
	if e.httpsPort != DefaultHTTPSPort {
		authority = net.JoinHostPort(host, strconv.Itoa(e.httpsPort))
	}

	target := "https://" + authority + r.URL.RequestURI()
	w.Header().Set("Location", target)
	// A permanent redirect a browser caches for a hostname whose TLS is not working yet
	// would be very hard for a customer to undo, so it is explicitly not cached.
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(http.StatusPermanentRedirect)
	if r.Method != http.MethodHead {
		fmt.Fprintln(w, target)
	}
}

var (
	_ caddy.Provisioner           = (*dispatcher)(nil)
	_ caddyhttp.MiddlewareHandler = (*dispatcher)(nil)
)
