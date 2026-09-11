package edge

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/caddyserver/caddy/v2"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// What the panel is told about each hostname.
//
// The failure text is the part worth defending. "DNS does not point here yet" and "too
// many certificates already issued" are both the customer's to fix and neither is
// fixable from a message that says the word "failed", so the certificate authority's own
// words are carried through unchanged and survive a restart.

func certificateEvent(t *testing.T, h *harness, name string, data map[string]any) {
	t.Helper()
	event, err := caddy.NewEvent(caddy.Context{}, name, data)
	if err != nil {
		t.Fatalf("building a %s event: %v", name, err)
	}
	if err := h.edge.certificates.Handle(context.Background(), event); err != nil {
		t.Fatalf("handling a %s event: %v", name, err)
	}
}

func statusOf(t *testing.T, h *harness, domain string) spec.RouteStatus {
	t.Helper()
	statuses, err := h.edge.Statuses(context.Background())
	if err != nil {
		t.Fatalf("reading statuses: %v", err)
	}
	for _, status := range statuses {
		if status.Domain == domain {
			return status
		}
	}
	t.Fatalf("no status was reported for %s: %+v", domain, statuses)
	return spec.RouteStatus{}
}

func servingHarness(t *testing.T) *harness {
	t.Helper()
	h := newHarness(t)
	h.publishSite("7", map[string]string{"index.html": "home"})
	h.sync([]spec.Route{siteRoute("shop.example", "7")},
		[]spec.Workload{siteWorkload("7", spec.SiteOptions{})})
	return h
}

func TestStatusesReportOneRowPerHostnameInSpecOrder(t *testing.T) {
	h := newHarness(t)
	h.publishSite("7", map[string]string{"index.html": "home"})
	h.backends.at("42", "127.0.0.1:9")
	h.sync([]spec.Route{
		siteRoute("b.example", "7"),
		appRoute("a.example", "42", 3000),
	}, []spec.Workload{siteWorkload("7", spec.SiteOptions{}), appWorkload("42")})

	statuses, err := h.edge.Statuses(context.Background())
	if err != nil {
		t.Fatalf("reading statuses: %v", err)
	}
	if len(statuses) != 2 {
		t.Fatalf("reported %d statuses, want one per hostname", len(statuses))
	}
	if statuses[0].Domain != "b.example" || statuses[1].Domain != "a.example" {
		t.Fatalf("reported %s then %s, want spec order so the panel's list does not reshuffle",
			statuses[0].Domain, statuses[1].Domain)
	}
	for _, status := range statuses {
		if !status.Serving {
			t.Errorf("%s was reported as not serving: %+v", status.Domain, status)
		}
	}
}

func TestStatusReportsNoCertificateForARouteWithTLSOff(t *testing.T) {
	h := newHarness(t)
	h.publishSite("7", map[string]string{"index.html": "home"})
	route := siteRoute("shop.example", "7")
	route.TLSMode = spec.TLSDisabled
	h.sync([]spec.Route{route}, []spec.Workload{siteWorkload("7", spec.SiteOptions{})})

	status := statusOf(t, h, "shop.example")
	if status.Certificate != spec.CertificateNone {
		t.Fatalf("reported %s, want NONE: no certificate is the finished state for a route "+
			"that asked for plain HTTP, not a pending one", status.Certificate)
	}
	if _, written := h.store.saved("shop.example"); written {
		t.Fatal("a hostname with TLS switched off was written to the certificate table")
	}
}

func TestStatusCarriesTheCertificateAuthoritysOwnWords(t *testing.T) {
	h := servingHarness(t)
	certificateEvent(t, h, "cert_failed", map[string]any{
		"identifier": "shop.example",
		"error":      errors.New("no valid A records found for shop.example"),
	})

	status := statusOf(t, h, "shop.example")
	if status.Certificate != spec.CertificateFailed {
		t.Fatalf("reported %s, want FAILED", status.Certificate)
	}
	if status.LastError != "no valid A records found for shop.example" {
		t.Fatalf("reported %q, want what the certificate authority said, verbatim",
			status.LastError)
	}

	saved, written := h.store.saved("shop.example")
	if !written {
		t.Fatal("the failure was not written down, so a restart would lose the explanation")
	}
	if saved.LastError != status.LastError {
		t.Fatalf("the stored row says %q and the reported one says %q",
			saved.LastError, status.LastError)
	}
}

func TestStatusClearsAFailureOnceACertificateArrives(t *testing.T) {
	h := servingHarness(t)
	certificateEvent(t, h, "cert_failed", map[string]any{
		"identifier": "shop.example",
		"error":      errors.New("rate limited"),
	})
	certificateEvent(t, h, "cert_obtained", map[string]any{
		"identifier": "shop.example",
		"issuer":     "acme-v02.api.letsencrypt.org-directory",
	})

	status := statusOf(t, h, "shop.example")
	if status.Certificate != spec.CertificateValid {
		t.Fatalf("reported %s, want VALID", status.Certificate)
	}
	if status.LastError != "" {
		t.Fatalf("a working certificate still carries %q, which the panel would warn about",
			status.LastError)
	}
}

func TestStatusReportsIssuingWhileAnAttemptIsRunning(t *testing.T) {
	h := servingHarness(t)
	certificateEvent(t, h, "cert_obtaining", map[string]any{"identifier": "shop.example"})

	if got := statusOf(t, h, "shop.example").Certificate; got != spec.CertificateIssuing {
		t.Fatalf("reported %s, want ISSUING", got)
	}
}

func TestStatusIsSeededFromTheLastRunAtStart(t *testing.T) {
	// Without this, every domain flickers back to unknown after a restart and a customer
	// watching one come up sees it go backwards.
	h := newHarness(t)
	h.store.seeded = []state.Certificate{{
		Domain:     "shop.example",
		State:      wisperpb.CertificateState_CERTIFICATE_STATE_FAILED,
		LastError:  "no valid A records found for shop.example",
		NotAfter:   h.clock.Add(24 * time.Hour),
		ObservedAt: h.clock.Add(-time.Hour),
	}}
	if err := h.edge.seedCertificates(context.Background()); err != nil {
		t.Fatalf("seeding: %v", err)
	}

	h.publishSite("7", map[string]string{"index.html": "home"})
	h.sync([]spec.Route{siteRoute("shop.example", "7")},
		[]spec.Workload{siteWorkload("7", spec.SiteOptions{})})

	status := statusOf(t, h, "shop.example")
	if status.Certificate != spec.CertificateFailed {
		t.Fatalf("reported %s after a restart, want what was known before it", status.Certificate)
	}
	if status.LastError != "no valid A records found for shop.example" {
		t.Fatalf("the explanation did not survive the restart: %q", status.LastError)
	}
}

func TestStatusStartsWithoutTheStoreWhenItCannotBeRead(t *testing.T) {
	h := newHarness(t)
	h.store.readErr = errors.New("the database is corrupt")

	if err := h.edge.seedCertificates(context.Background()); err != nil {
		t.Fatalf("an unreadable store stopped the edge from starting: %v", err)
	}
}

func TestStatusWritesOnlyWhatChanged(t *testing.T) {
	h := servingHarness(t)
	certificateEvent(t, h, "cert_obtaining", map[string]any{"identifier": "shop.example"})

	if _, err := h.edge.Statuses(context.Background()); err != nil {
		t.Fatalf("reading statuses: %v", err)
	}
	first, _ := h.store.saved("shop.example")

	// A second pass that learned nothing must not write again: on a node with two hundred
	// hostnames that is two hundred writes every fifteen seconds, forever.
	h.store.writeErr = errors.New("this write should never have been attempted")
	if _, err := h.edge.Statuses(context.Background()); err != nil {
		t.Fatalf("reading statuses: %v", err)
	}
	again, _ := h.store.saved("shop.example")
	if again != first {
		t.Fatalf("the stored row changed from %+v to %+v with nothing having happened",
			first, again)
	}
}

func TestStatusesAreReportedBeforeAnythingIsListening(t *testing.T) {
	// The reconcile loop starts its first pass the instant the daemon does, which is
	// before Caddy has a certificate cache to read. Asking one that does not exist would
	// be a nil dereference on roughly every cold boot.
	h := servingHarness(t)
	if h.edge.started.Load() {
		t.Fatal("the harness started Caddy, which these tests must never do")
	}
	if got := statusOf(t, h, "shop.example").Certificate; got != spec.CertificateUnspecified {
		t.Fatalf("reported %s before anything was listening, want UNSPECIFIED", got)
	}
}
