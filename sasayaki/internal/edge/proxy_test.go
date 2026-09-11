package edge

import (
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// Proxying to a container.
//
// The backend here is an ordinary test server on a loopback port, which is exactly what a
// container on a tenant bridge looks like from this daemon: an address and a port that
// the address resolver produced.

// backendServer stands in for a customer's container and records what reached it.
type backendServer struct {
	*httptest.Server
	lastRequest chan *http.Request
}

func newBackendServer(t *testing.T, body string) *backendServer {
	t.Helper()
	backend := &backendServer{lastRequest: make(chan *http.Request, 16)}
	backend.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		backend.lastRequest <- r.Clone(r.Context())
		w.Header().Set("Content-Type", "text/plain")
		_, _ = io.WriteString(w, body)
	}))
	t.Cleanup(backend.Close)
	return backend
}

func (b *backendServer) received(t *testing.T) *http.Request {
	t.Helper()
	select {
	case request := <-b.lastRequest:
		return request
	default:
		t.Fatal("nothing reached the backend")
		return nil
	}
}

func proxyHarness(t *testing.T, body string) (*harness, *backendServer) {
	t.Helper()
	backend := newBackendServer(t, body)

	h := newHarness(t)
	h.backends.at("42", backend.Listener.Addr().String())
	h.sync([]spec.Route{appRoute("shop.example", "42", 3000)}, []spec.Workload{appWorkload("42")})
	return h, backend
}

func TestProxyReachesTheContainer(t *testing.T) {
	h, backend := proxyHarness(t, "hello from the container")

	recorder := get(t, h, "http://shop.example/orders?page=2")
	if recorder.Code != http.StatusOK {
		t.Fatalf("answered %d, want 200: %s", recorder.Code, recorder.Body.String())
	}
	if body := recorder.Body.String(); body != "hello from the container" {
		t.Fatalf("served %q, want the container's own answer", body)
	}

	forwarded := backend.received(t)
	if forwarded.URL.RequestURI() != "/orders?page=2" {
		t.Errorf("the container was asked for %q, want the path and query the visitor sent",
			forwarded.URL.RequestURI())
	}
	if forwarded.Host != "shop.example" {
		t.Errorf("the container was told Host %q, want the hostname the visitor typed: an "+
			"application that generates absolute URLs would produce the wrong ones",
			forwarded.Host)
	}
	if forwarded.Header.Get("X-Forwarded-Host") != "shop.example" {
		t.Errorf("X-Forwarded-Host was %q", forwarded.Header.Get("X-Forwarded-Host"))
	}
	if forwarded.Header.Get("X-Forwarded-For") == "" {
		t.Error("X-Forwarded-For was not set, so the application cannot see who called it")
	}
}

func TestProxyDiscardsAForwardedHeaderTheClientInvented(t *testing.T) {
	h, backend := proxyHarness(t, "ok")

	request := httptest.NewRequest(http.MethodGet, "http://shop.example/", nil)
	request.Header.Set("X-Forwarded-For", "203.0.113.9")
	request.RemoteAddr = "198.51.100.4:44444"
	h.edge.serve(httptest.NewRecorder(), request, false)

	forwarded := backend.received(t)
	if got := forwarded.Header.Get("X-Forwarded-For"); strings.Contains(got, "203.0.113.9") {
		t.Fatalf("X-Forwarded-For was %q: a value a client sent must not be appended to, "+
			"because an application that trusts the chain can then be told any address at all",
			got)
	}
}

func TestProxyExplainsAContainerThatIsNotThere(t *testing.T) {
	h := newHarness(t)
	h.sync([]spec.Route{appRoute("shop.example", "42", 3000)}, []spec.Workload{appWorkload("42")})

	recorder := get(t, h, "http://shop.example/")
	if recorder.Code != http.StatusBadGateway {
		t.Fatalf("a route to a container that is not running answered %d, want 502",
			recorder.Code)
	}
	if recorder.Body.Len() == 0 {
		t.Fatal("a 502 with an empty body renders as a blank frame")
	}
	if !strings.Contains(recorder.Body.String(), "3000") {
		t.Fatalf("the page does not say which port was tried, which is the one thing the "+
			"person who deployed it needs: %q", recorder.Body.String())
	}
}

func TestProxyIsRebuiltOnlyWhenTheBackendChanges(t *testing.T) {
	// The connection pool to a container survives a sync that had nothing to do with it.
	// Rebuilding it would drop every keep-alive on the node whenever any other workload
	// on the machine was redeployed.
	h, backend := proxyHarness(t, "ok")
	unchanged := h.edge.routes.load().backends["app|42|3000"]
	if unchanged == nil {
		t.Fatal("the app backend was not keyed by workload and port")
	}

	h.sync([]spec.Route{
		appRoute("shop.example", "42", 3000),
		appRoute("other.example", "42", 3000),
	}, []spec.Workload{appWorkload("42")})

	if again := h.edge.routes.load().backends["app|42|3000"]; again != unchanged {
		t.Fatal("adding an unrelated hostname rebuilt the proxy to a container that had " +
			"not changed")
	}

	// A different port is a different backend, and the old one is let go of.
	h.sync([]spec.Route{appRoute("shop.example", "42", 8080)}, []spec.Workload{appWorkload("42")})
	if _, still := h.edge.routes.load().backends["app|42|3000"]; still {
		t.Fatal("the backend for the port that is no longer routed was kept")
	}

	_ = backend
}

func TestProxyRefusesAStoppedApplicationWithoutDialling(t *testing.T) {
	h := newHarness(t)
	h.backends.at("42", "127.0.0.1:1")
	stopped := appWorkload("42")
	stopped.Desired = spec.DesiredStopped
	h.sync([]spec.Route{appRoute("shop.example", "42", 3000)}, []spec.Workload{stopped})

	recorder := get(t, h, "http://shop.example/")
	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("a stopped application answered %d, want 503", recorder.Code)
	}
	if !strings.Contains(recorder.Body.String(), "stopped") {
		t.Fatalf("the page does not say the application is stopped: %q", recorder.Body.String())
	}
}

func TestProxyReportsWhenTheAddressResolverIsBroken(t *testing.T) {
	h := newHarness(t)
	h.backends.failure = errors.New("the container engine is not answering")
	h.sync([]spec.Route{appRoute("shop.example", "42", 3000)}, []spec.Workload{appWorkload("42")})

	statuses, err := h.edge.Statuses(t.Context())
	if err != nil {
		t.Fatalf("reading statuses: %v", err)
	}
	if len(statuses) != 1 || statuses[0].Serving {
		t.Fatalf("a route whose address could not be resolved was reported as serving: %+v",
			statuses)
	}
}
