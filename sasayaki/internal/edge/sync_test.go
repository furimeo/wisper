package edge

import (
	"context"
	"errors"
	"net/http"
	"slices"
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// Turning a spec into a table.
//
// Most of these are about the routes that cannot be served, because those are the ones a
// customer is looking at the panel about. Every one of them still gets a hostname in the
// table: withdrawing it would take the certificate with it and replace a page that
// explains the problem with a connection that is simply refused.

func TestSyncServesExactlyWhatTheSpecNames(t *testing.T) {
	h := newHarness(t)
	h.publishSite("7", map[string]string{"index.html": "home"})
	h.backends.at("42", "127.0.0.1:9")

	h.sync([]spec.Route{
		siteRoute("shop.example", "7"),
		appRoute("api.example", "42", 3000),
	}, []spec.Workload{
		siteWorkload("7", spec.SiteOptions{}),
		appWorkload("42"),
	})

	published := h.edge.routes.load()
	if !slices.Equal(published.domains, []string{"shop.example", "api.example"}) {
		t.Fatalf("the table holds %v, want the two hostnames in spec order", published.domains)
	}
	if published.known("gone.example") {
		t.Fatal("the table knows a hostname the spec never mentioned")
	}
}

func TestSyncLowercasesTheHostnameItIsGiven(t *testing.T) {
	h := newHarness(t)
	h.publishSite("7", map[string]string{"index.html": "home"})
	h.sync([]spec.Route{siteRoute("Shop.Example.", "7")},
		[]spec.Workload{siteWorkload("7", spec.SiteOptions{})})

	if !h.edge.routes.load().known("shop.example") {
		t.Fatal("a hostname the panel sent in mixed case was not matchable")
	}
}

func TestSyncLoadsARouteWhoseWorkloadIsMissing(t *testing.T) {
	h := newHarness(t)
	h.sync([]spec.Route{appRoute("shop.example", "42", 3000)}, nil)

	published := h.edge.routes.load()
	matched, found := published.match("shop.example", "/")
	if !found {
		t.Fatal("the hostname was dropped, so the visitor gets a refused connection " +
			"instead of an explanation")
	}
	if matched.serving {
		t.Fatal("a route to a workload that is not in the spec was reported as serving")
	}
	if !strings.Contains(matched.detail, "42") {
		t.Fatalf("the reason does not name the workload: %q", matched.detail)
	}

	recorder := get(t, h, "http://shop.example/")
	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("answered %d, want 503", recorder.Code)
	}
}

func TestSyncRefusesAWorkloadKindItDoesNotUnderstand(t *testing.T) {
	// A spec from a newer panel. Guessing between "start a container" and "serve a
	// directory" is the guess the unknown kind exists to prevent.
	h := newHarness(t)
	unknown := spec.Workload{ID: "42", Kind: spec.KindUnknown, Desired: spec.DesiredRunning}
	h.sync([]spec.Route{appRoute("shop.example", "42", 3000)}, []spec.Workload{unknown})

	matched, _ := h.edge.routes.load().match("shop.example", "/")
	if matched.serving {
		t.Fatal("a workload of an unknown kind was proxied to anyway")
	}
	if !strings.Contains(matched.detail, "kind") {
		t.Fatalf("the reason does not say the kind was the problem: %q", matched.detail)
	}
}

func TestSyncRefusesARouteWithNoPort(t *testing.T) {
	h := newHarness(t)
	route := appRoute("shop.example", "42", 0)
	h.sync([]spec.Route{route}, []spec.Workload{appWorkload("42")})

	matched, _ := h.edge.routes.load().match("shop.example", "/")
	if matched.serving {
		t.Fatal("a route with no port was reported as serving")
	}
}

func TestSyncIgnoresARouteWithNoHostname(t *testing.T) {
	h := newHarness(t)
	h.sync([]spec.Route{{WorkloadID: "42", TLSMode: spec.TLSOnDemand}}, []spec.Workload{appWorkload("42")})

	if got := len(h.edge.routes.load().domains); got != 0 {
		t.Fatalf("the table holds %d hostnames, want none", got)
	}
}

func TestSyncForgetsTheCertificatesOfWithdrawnHostnames(t *testing.T) {
	h := newHarness(t)
	h.publishSite("7", map[string]string{"index.html": "home"})
	h.sync([]spec.Route{
		siteRoute("shop.example", "7"),
		siteRoute("old.example", "7"),
	}, []spec.Workload{siteWorkload("7", spec.SiteOptions{})})

	h.sync([]spec.Route{siteRoute("shop.example", "7")},
		[]spec.Workload{siteWorkload("7", spec.SiteOptions{})})

	last := h.store.pruned[len(h.store.pruned)-1]
	if !slices.Equal(last, []string{"shop.example"}) {
		t.Fatalf("the store was told to keep %v, want only the hostname that is still routed", last)
	}
	if _, still := h.edge.certificates.snapshot("old.example"); still {
		t.Fatal("what was known about a withdrawn hostname's certificate was kept, so a " +
			"customer who removes a domain and adds it back gets the old failure with it")
	}
}

func TestSyncSwapsTheTableEvenWhenTheStoreRefuses(t *testing.T) {
	// SQLite being unwritable is a reporting problem. It must not stop the node serving
	// what the panel just told it to serve.
	h := newHarness(t)
	h.publishSite("7", map[string]string{"index.html": "home"})
	h.store.pruneErr = errors.New("the database is locked")

	err := h.edge.Sync(context.Background(), spec.Spec{
		Routes:    []spec.Route{siteRoute("shop.example", "7")},
		Workloads: []spec.Workload{siteWorkload("7", spec.SiteOptions{})},
	})
	if err == nil {
		t.Fatal("a store that refused the prune was not reported, so nothing would ever retry it")
	}
	if !h.edge.routes.load().known("shop.example") {
		t.Fatal("the route table was not swapped, so a store failure became an outage")
	}
	if body := get(t, h, "http://shop.example/").Body.String(); body != "home" {
		t.Fatalf("the site is not being served: %q", body)
	}
}

func TestSyncToAnEmptySpecServesNothing(t *testing.T) {
	// What a drained node is given. Not the same as having no spec, which the reconcile
	// loop handles by not calling here at all.
	h := newHarness(t)
	h.publishSite("7", map[string]string{"index.html": "home"})
	h.sync([]spec.Route{siteRoute("shop.example", "7")},
		[]spec.Workload{siteWorkload("7", spec.SiteOptions{})})

	h.sync(nil, nil)

	if got := len(h.edge.routes.load().domains); got != 0 {
		t.Fatalf("the table still holds %d hostnames after an empty spec", got)
	}
}
