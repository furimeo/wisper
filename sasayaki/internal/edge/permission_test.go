package edge

import (
	"context"
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// The on-demand TLS decision.
//
// Three cases decide whether this platform works when the panel is down, whether it can
// be used to burn somebody's certificate authority rate limit, and whether a domain a
// customer removed keeps working afterwards. Everything here runs with no panel, no
// network and no Caddy, which is the property being asserted as much as the answers are.

func askedFor(t *testing.T, h *harness, name string) error {
	t.Helper()
	module := &permission{Edge: h.edge.id, edge: h.edge}
	return module.CertificateAllowed(context.Background(), name)
}

func TestPermissionAllowsAKnownHostname(t *testing.T) {
	h := newHarness(t)
	h.backends.at("42", "172.20.0.5:3000")
	h.sync([]spec.Route{appRoute("shop.example", "42", 3000)}, []spec.Workload{appWorkload("42")})

	if err := askedFor(t, h, "shop.example"); err != nil {
		t.Fatalf("a hostname in the route table was refused a certificate: %v", err)
	}
}

func TestPermissionAllowsAKnownHostnameThatIsNotServingYet(t *testing.T) {
	// The container is not up. The certificate still has to be obtainable, because the
	// order a customer does things in is: point DNS, wait for the certificate, then
	// notice the application is crash-looping. Refusing here would make the first
	// problem look like the second.
	h := newHarness(t)
	h.sync([]spec.Route{appRoute("shop.example", "42", 3000)}, []spec.Workload{appWorkload("42")})

	if err := askedFor(t, h, "shop.example"); err != nil {
		t.Fatalf("a routed hostname whose container is down was refused a certificate: %v", err)
	}
}

func TestPermissionRefusesAnUnknownHostname(t *testing.T) {
	h := newHarness(t)
	h.sync([]spec.Route{appRoute("shop.example", "42", 3000)}, []spec.Workload{appWorkload("42")})

	err := askedFor(t, h, "someone-elses.example")
	if err == nil {
		t.Fatal("a hostname this node does not serve was allowed a certificate, which is how " +
			"a public address becomes somebody's rate-limit problem")
	}
	if !strings.Contains(err.Error(), "someone-elses.example") {
		t.Fatalf("the refusal does not name the hostname it refused: %v", err)
	}
}

func TestPermissionRefusesAHostnameThatWasJustRemoved(t *testing.T) {
	h := newHarness(t)
	h.backends.at("42", "172.20.0.5:3000")
	h.sync([]spec.Route{
		appRoute("shop.example", "42", 3000),
		appRoute("old.example", "42", 3000),
	}, []spec.Workload{appWorkload("42")})

	if err := askedFor(t, h, "old.example"); err != nil {
		t.Fatalf("the hostname was not allowed before it was removed: %v", err)
	}

	// The customer deleted the domain. The next spec simply does not carry it.
	h.sync([]spec.Route{appRoute("shop.example", "42", 3000)}, []spec.Workload{appWorkload("42")})

	if err := askedFor(t, h, "old.example"); err == nil {
		t.Fatal("a hostname withdrawn from the spec was still allowed a certificate: the " +
			"decision is supposed to come from the table that was just swapped, with nothing " +
			"cached in front of it")
	}
	if err := askedFor(t, h, "shop.example"); err != nil {
		t.Fatalf("removing one hostname refused another: %v", err)
	}
}

func TestPermissionRefusesAHostnameWithTLSSwitchedOff(t *testing.T) {
	h := newHarness(t)
	route := appRoute("staging.example", "42", 3000)
	route.TLSMode = spec.TLSDisabled
	h.sync([]spec.Route{route}, []spec.Workload{appWorkload("42")})

	err := askedFor(t, h, "staging.example")
	if err == nil {
		t.Fatal("a route that asked for plain HTTP was still sent to a certificate authority")
	}
	if !strings.Contains(err.Error(), "TLS switched off") {
		t.Fatalf("the refusal does not say why, so the operator cannot tell it from an "+
			"unknown hostname: %v", err)
	}
}

func TestPermissionNormalisesWhatTheHandshakeSent(t *testing.T) {
	h := newHarness(t)
	h.sync([]spec.Route{siteRoute("shop.example", "7")}, []spec.Workload{siteWorkload("7", spec.SiteOptions{})})

	for _, sent := range []string{"SHOP.example", "shop.example.", "Shop.Example."} {
		if err := askedFor(t, h, sent); err != nil {
			t.Fatalf("the handshake name %q was refused, although it is the same hostname: %v",
				sent, err)
		}
	}
}

func TestPermissionRefusesEverythingBeforeTheFirstSync(t *testing.T) {
	// A node that has enrolled and not yet been given a spec serves nothing, so it must
	// ask for nothing. The empty table is what makes that true without a special case.
	h := newHarness(t)

	if err := askedFor(t, h, "shop.example"); err == nil {
		t.Fatal("an edge with no route table allowed a certificate")
	}
}
