package spec

import (
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

func TestRouteFromProto(t *testing.T) {
	route := routeFromProto(&wisperpb.Route{
		Domain:     "acme.example",
		WorkloadId: "1e9d",
		Port:       8080,
		TlsMode:    wisperpb.TlsMode_TLS_MODE_ON_DEMAND,
		PathPrefix: "/api",
		ForceHttps: true,
	})

	if route.Domain != "acme.example" || route.WorkloadID != "1e9d" || route.Port != 8080 {
		t.Errorf("route = %+v", route)
	}
	if !route.WantsCertificate() {
		t.Error("an on-demand route wants a certificate")
	}
	if route.PathPrefix != "/api" || !route.ForceHTTPS {
		t.Errorf("route = %+v, want /api and forced HTTPS", route)
	}
}

// TLS off is the only way to get plain HTTP, and it has to survive the conversion: a
// hostname whose DNS points elsewhere would otherwise have ACME failing at the customer.
func TestTLSDisabledIsPreserved(t *testing.T) {
	route := routeFromProto(&wisperpb.Route{
		Domain:  "cutover.example",
		TlsMode: wisperpb.TlsMode_TLS_MODE_DISABLED,
	})

	if route.TLSMode != TLSDisabled {
		t.Errorf("tlsMode = %q, want DISABLED", route.TLSMode)
	}
	if route.WantsCertificate() {
		t.Error("a disabled route must not ask for a certificate")
	}
}

// Serving plain HTTP for something that asked for TLS is a silent downgrade, so the
// unspecified default goes the other way.
func TestUnspecifiedTLSModeIsOnDemand(t *testing.T) {
	route := routeFromProto(&wisperpb.Route{Domain: "acme.example"})

	if route.TLSMode != TLSOnDemand {
		t.Errorf("tlsMode = %q, want ON_DEMAND", route.TLSMode)
	}
}

func TestRouteFromProtoAcceptsNil(t *testing.T) {
	if got := routeFromProto(nil); got.Domain != "" {
		t.Errorf("a nil route gave %+v", got)
	}
}
