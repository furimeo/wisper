package edge

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strconv"
	"time"
)

// Proxying a hostname to a customer's container.
//
// The standard library's reverse proxy rather than Caddy's, for one reason that decides
// it: Caddy's is a configuration module with a lifecycle, and it registers its upstreams
// in a process-wide pool that has to be unwound when the route goes away. This package
// creates and discards backends whenever a deployment happens, which is exactly the
// pattern that pool is not built for. What is left - forwarding headers, upgrades,
// streaming, error handling - is a hundred lines the standard library already has.

// Timeouts for the hop between this daemon and a container on the same machine.
//
// Short where the failure is a machine that is not there, generous where the wait is
// legitimate work. A dial to a container on a local bridge either succeeds in
// milliseconds or is never going to, so five seconds is already patient; a response, on
// the other hand, may be a customer's slow report and gets no deadline at all beyond the
// header, because cutting off a working download is a worse failure than holding a
// connection open.
const (
	backendDialTimeout     = 5 * time.Second
	backendResponseTimeout = 5 * time.Minute
	backendIdleTimeout     = 90 * time.Second
	backendMaxIdlePerHost  = 32
)

// newProxy builds the backend for one workload's port.
//
// The address is not resolved here. The transport's dialer resolves it on every new
// connection, which is what keeps a container that the engine restarted - same id, new
// address on the tenant bridge - reachable without waiting for the route table to be
// rebuilt for some unrelated reason.
func (e *Edge) newProxy(workloadID string, port uint16) *liveBackend {
	transport := &http.Transport{
		DialContext:           e.dialWorkload(workloadID, port),
		ResponseHeaderTimeout: backendResponseTimeout,
		IdleConnTimeout:       backendIdleTimeout,
		MaxIdleConns:          backendMaxIdlePerHost,
		MaxIdleConnsPerHost:   backendMaxIdlePerHost,
		// Containers speak plain HTTP/1.1 on the tenant bridge. h2c would have to be
		// negotiated out of band and nothing on this platform asks for it, so attempting
		// it would only add a failure mode to every request.
		ForceAttemptHTTP2: false,
		// The dialer ignores the address it is given, so there is nothing for a proxy
		// environment variable to redirect. Saying so explicitly stops a node's
		// HTTP_PROXY from silently sending a customer's traffic somewhere else.
		Proxy: nil,
	}

	proxy := &httputil.ReverseProxy{
		Rewrite:      rewriteToWorkload(workloadID, port),
		Transport:    transport,
		ErrorHandler: e.proxyFailed(workloadID, port),
		ErrorLog:     nil,
	}

	return &liveBackend{
		key:   "app|" + workloadID + "|" + strconv.FormatUint(uint64(port), 10),
		serve: proxy,
		release: func() {
			// Idle connections only, which is precisely what "no longer routed" means:
			// anything still in flight is finishing a request that started while this
			// backend was published, and it is entitled to finish.
			transport.CloseIdleConnections()
		},
	}
}

// dialWorkload turns "the container behind this workload" into a socket.
//
// The address argument the transport passes is ignored: it is the synthetic authority
// from rewriteToWorkload, whose only job is to key the connection pool. The real address
// comes from Backends, on every dial.
func (e *Edge) dialWorkload(workloadID string, port uint16) func(context.Context, string, string) (net.Conn, error) {
	dialer := &net.Dialer{Timeout: backendDialTimeout, KeepAlive: 30 * time.Second}

	return func(ctx context.Context, network, _ string) (net.Conn, error) {
		address, err := e.backends.Address(ctx, workloadID, port)
		if err != nil {
			return nil, fmt.Errorf("no address for %s: %w", workloadID, err)
		}
		connection, err := dialer.DialContext(ctx, network, address.String())
		if err != nil {
			return nil, fmt.Errorf("connect to %s at %s: %w", workloadID, address, err)
		}
		return connection, nil
	}
}

// rewriteToWorkload points the outbound request at the container.
func rewriteToWorkload(workloadID string, port uint16) func(*httputil.ProxyRequest) {
	// A synthetic authority, never resolved by anything: the dialer ignores it and the
	// Host header is overwritten below. It exists because the transport keys its
	// connection pool by scheme and authority, and one transport serves one workload.
	target := &url.URL{
		Scheme: "http",
		Host:   net.JoinHostPort(workloadID, strconv.FormatUint(uint64(port), 10)),
	}

	return func(request *httputil.ProxyRequest) {
		request.SetURL(target)

		// The customer's application decides what to do with the hostname - generate
		// absolute URLs, pick a virtual host, set a cookie domain - and it has to be the
		// one the visitor typed, not the internal name of a container.
		request.Out.Host = request.In.Host

		// X-Forwarded-For, -Host and -Proto, with anything the client sent discarded.
		// SetXForwarded is the form that does not append: a header arriving from the
		// internet is a claim, and an application that trusts an appended chain can be
		// told any address at all.
		request.SetXForwarded()
	}
}

// proxyFailed is the page a visitor gets when the container did not answer.
//
// It says which of the two things went wrong, because they need different actions: an
// address that could not be resolved means nothing is deployed or running, and a
// connection that was refused means the process is up and not listening on that port.
// A bare "502" tells the person who deployed it neither.
func (e *Edge) proxyFailed(workloadID string, port uint16) func(http.ResponseWriter, *http.Request, error) {
	return func(w http.ResponseWriter, r *http.Request, err error) {
		e.log.Warn("the edge could not reach a container",
			slog.String("workload", workloadID),
			slog.Int("port", int(port)),
			slog.String("host", r.Host),
			slog.String("error", err.Error()))

		writePage(w, http.StatusBadGateway, "This application is not responding",
			fmt.Sprintf("The node reached for it on port %d and got no answer. "+
				"If it was just deployed, give it a moment; if it keeps happening, its logs "+
				"in the panel will say why it is not listening.", port))
	}
}
