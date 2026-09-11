package edge

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// The route table, and the swap.
//
// The concurrency test at the bottom is the one that matters. Everything in this package
// is arranged around the claim that a reconcile pass can replace what the node serves
// while it is serving it, and the only way to believe that claim is to do both at once,
// under the race detector, and check that no request ever saw anything but a complete
// answer from one table or the other.

func TestMatchPrefersTheLongerPathPrefix(t *testing.T) {
	h := newHarness(t)
	h.publishSite("site", map[string]string{"index.html": "the site"})
	h.backends.at("api", "127.0.0.1:1")

	api := appRoute("shop.example", "api", 3000)
	api.PathPrefix = "/api"
	h.sync(
		[]spec.Route{siteRoute("shop.example", "site"), api},
		[]spec.Workload{siteWorkload("site", spec.SiteOptions{}), appWorkload("api")},
	)

	published := h.edge.routes.load()
	for _, at := range []struct {
		path   string
		wanted string
	}{
		{"/", "site"},
		{"/about", "site"},
		{"/apixyz", "site"},
		{"/api", "api"},
		{"/api/", "api"},
		{"/api/orders", "api"},
	} {
		matched, found := published.match("shop.example", at.path)
		if !found {
			t.Fatalf("%s matched nothing at all", at.path)
		}
		if matched.route.WorkloadID != at.wanted {
			t.Errorf("%s went to %s, want %s", at.path, matched.route.WorkloadID, at.wanted)
		}
	}
}

func TestNormaliseHost(t *testing.T) {
	for sent, wanted := range map[string]string{
		"shop.example":       "shop.example",
		"SHOP.Example":       "shop.example",
		"shop.example.":      "shop.example",
		"shop.example:443":   "shop.example",
		"SHOP.example.:8443": "shop.example",
		"[2001:db8::1]:443":  "2001:db8::1",
		"[2001:db8::1]":      "2001:db8::1",
		"2001:db8::1":        "2001:db8::1",
		"":                   "",
	} {
		if got := normaliseHost(sent); got != wanted {
			t.Errorf("normaliseHost(%q) = %q, want %q", sent, got, wanted)
		}
	}
}

func TestARequestKeepsTheTableItStartedWith(t *testing.T) {
	// The guarantee a swap has to make, stated without any goroutines: whatever a request
	// resolved to is still valid after the table under it has been replaced.
	h := newHarness(t)
	h.publishSite("first", map[string]string{"index.html": "first release"})
	h.publishSite("second", map[string]string{"index.html": "second release"})

	h.sync([]spec.Route{siteRoute("shop.example", "first")},
		[]spec.Workload{siteWorkload("first", spec.SiteOptions{})})

	// What a request in flight would be holding.
	inFlight, found := h.edge.routes.load().match("shop.example", "/")
	if !found {
		t.Fatal("the hostname was not in the table it had just been put into")
	}

	h.sync([]spec.Route{siteRoute("shop.example", "second")},
		[]spec.Workload{siteWorkload("second", spec.SiteOptions{})})

	recorder := httptest.NewRecorder()
	inFlight.backend.serve.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "http://shop.example/", nil))
	if body := recorder.Body.String(); body != "first release" {
		t.Fatalf("a request that started before the swap was served %q, want the release it "+
			"resolved to", body)
	}

	// And a new request gets the new one.
	fresh := httptest.NewRecorder()
	h.edge.serve(fresh, httptest.NewRequest(http.MethodGet, "http://shop.example/", nil), false)
	if body := fresh.Body.String(); body != "second release" {
		t.Fatalf("a request after the swap was served %q, want the new release", body)
	}
}

func TestRouteSwapsUnderConcurrentRequests(t *testing.T) {
	h := newHarness(t)
	h.publishSite("first", map[string]string{"index.html": "first release"})
	h.publishSite("second", map[string]string{"index.html": "second release"})

	first := []spec.Route{siteRoute("shop.example", "first")}
	second := []spec.Route{siteRoute("shop.example", "second")}
	workloads := []spec.Workload{
		siteWorkload("first", spec.SiteOptions{}),
		siteWorkload("second", spec.SiteOptions{}),
	}
	h.sync(first, workloads)

	const (
		readers = 8
		swaps   = 120
	)

	var (
		stop     = make(chan struct{})
		running  sync.WaitGroup
		failures sync.Map
	)

	for range readers {
		running.Add(1)
		go func() {
			defer running.Done()
			for {
				select {
				case <-stop:
					return
				default:
				}

				recorder := httptest.NewRecorder()
				request := httptest.NewRequest(http.MethodGet, "http://shop.example/", nil)
				h.edge.serve(recorder, request, false)

				body := recorder.Body.String()
				switch {
				case recorder.Code != http.StatusOK:
					failures.Store("status", recorder.Code)
				case body != "first release" && body != "second release":
					failures.Store("body", body)
				}

				// The on-demand decision reads the same snapshot, and it is asked during
				// a handshake that can land in the middle of any of this.
				module := &permission{Edge: h.edge.id, edge: h.edge}
				if err := module.CertificateAllowed(context.Background(), "shop.example"); err != nil {
					failures.Store("permission", err.Error())
				}
			}
		}()
	}

	for i := range swaps {
		routes := first
		if i%2 == 1 {
			routes = second
		}
		if err := h.edge.Sync(context.Background(), spec.Spec{Routes: routes, Workloads: workloads}); err != nil {
			t.Errorf("sync %d failed: %v", i, err)
			break
		}
	}
	close(stop)
	running.Wait()

	failures.Range(func(kind, value any) bool {
		t.Errorf("a request during a route swap saw a bad %v: %v", kind, value)
		return true
	})
}

func TestSwappingAwayAHostnameStopsServingIt(t *testing.T) {
	h := newHarness(t)
	h.publishSite("first", map[string]string{"index.html": "hello"})
	h.sync([]spec.Route{siteRoute("shop.example", "first")},
		[]spec.Workload{siteWorkload("first", spec.SiteOptions{})})

	h.sync(nil, nil)

	recorder := httptest.NewRecorder()
	h.edge.serve(recorder, httptest.NewRequest(http.MethodGet, "http://shop.example/", nil), false)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("a withdrawn hostname answered %d, want 404", recorder.Code)
	}
	if !strings.Contains(recorder.Body.String(), "shop.example") {
		t.Fatalf("the page for an unserved hostname does not name it, so a visitor cannot "+
			"tell it from a broken node: %q", recorder.Body.String())
	}
}
