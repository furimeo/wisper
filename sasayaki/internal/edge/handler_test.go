package edge

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/caddyserver/caddy/v2"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// The plain listener, which has one decision to make and two ways to get it wrong.
//
// Redirect a hostname whose DNS has not arrived and the customer is sent to a handshake
// that fails, turning "not set up yet" into "broken". Do not redirect one that is set up
// and the site is served over plain HTTP forever, because nothing else on the path will
// ever upgrade it. The panel decides per hostname; this is where the decision is applied.

func plain(t *testing.T, h *harness, target string) *httptest.ResponseRecorder {
	t.Helper()
	recorder := httptest.NewRecorder()
	h.edge.serve(recorder, httptest.NewRequest(http.MethodGet, target, nil), true)
	return recorder
}

func TestPlainListenerRedirectsWhenTheRouteAsksForIt(t *testing.T) {
	h := newHarness(t)
	h.publishSite("7", map[string]string{"index.html": "home"})
	h.sync([]spec.Route{siteRoute("shop.example", "7")}, []spec.Workload{siteWorkload("7", spec.SiteOptions{})})

	recorder := plain(t, h, "http://shop.example/orders?page=2")
	if recorder.Code != http.StatusPermanentRedirect {
		t.Fatalf("answered %d, want 308: a 301 or 302 lets a browser turn a POST into a GET",
			recorder.Code)
	}
	// The harness runs on a non-standard port, so the redirect has to carry it - otherwise
	// every test of this would pass on a developer's machine and fail on a node.
	if location := recorder.Header().Get("Location"); location != "https://shop.example:18443/orders?page=2" {
		t.Fatalf("redirected to %q, want the same request on the secure listener", location)
	}
}

func TestPlainListenerServesWhenTheRouteDoesNotAskForHTTPS(t *testing.T) {
	h := newHarness(t)
	h.publishSite("7", map[string]string{"index.html": "home"})
	route := siteRoute("shop.example", "7")
	route.ForceHTTPS = false
	h.sync([]spec.Route{route}, []spec.Workload{siteWorkload("7", spec.SiteOptions{})})

	recorder := plain(t, h, "http://shop.example/")
	if recorder.Code != http.StatusOK {
		t.Fatalf("answered %d, want the site over plain HTTP", recorder.Code)
	}
	if body := recorder.Body.String(); body != "home" {
		t.Fatalf("served %q", body)
	}
}

func TestPlainListenerDoesNotRedirectAHostnameItDoesNotServe(t *testing.T) {
	h := newHarness(t)
	h.sync(nil, nil)

	recorder := plain(t, h, "http://someone-elses.example/")
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("answered %d, want 404: redirecting an unknown hostname to HTTPS would send "+
			"a visitor to a handshake this node will refuse", recorder.Code)
	}
}

func TestTheHandlerRefusesToProvisionWithoutItsEdge(t *testing.T) {
	// A configuration naming an edge that is not in this process. It would produce a
	// handler that answered nothing, so it fails the load instead.
	handler := &dispatcher{Edge: "edge-that-does-not-exist"}
	err := handler.Provision(caddy.Context{})
	if err == nil {
		t.Fatal("a handler bound to no edge was provisioned")
	}
	if !strings.Contains(err.Error(), "edge-that-does-not-exist") {
		t.Fatalf("the error does not name what it could not find: %v", err)
	}
}
