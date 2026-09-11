package reconcile

import (
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

func TestANewDomainReloadsTheEdgeAndAnUnchangedTableDoesNot(t *testing.T) {
	h := newHarness(t)
	h.publish(t, appSpec(7))
	h.pass(t)
	h.pass(t)

	if h.edge.syncCount() != 1 {
		t.Fatalf("the edge was loaded %d times for one route table", h.edge.syncCount())
	}

	changed := appSpec(8)
	changed.Routes = append(changed.Routes, &wisperpb.Route{
		Domain:     "www.example.test",
		WorkloadId: "wl-api",
		Port:       8080,
		TlsMode:    wisperpb.TlsMode_TLS_MODE_ON_DEMAND,
	})
	h.publish(t, changed)
	h.pass(t)

	if h.edge.syncCount() != 2 {
		t.Fatalf("a new hostname loaded the edge %d times, want a second load", h.edge.syncCount())
	}
}

func TestANewSiteReleaseReloadsTheEdge(t *testing.T) {
	// The edge serves a site's files straight off the disk, so pointing `current` somewhere
	// else is precisely the change it has to be told about.
	h := newHarness(t)
	site := appSpec(7)
	site.Routes = []*wisperpb.Route{{
		Domain:     "marketing.example.test",
		WorkloadId: "wl-site",
		TlsMode:    wisperpb.TlsMode_TLS_MODE_ON_DEMAND,
	}}
	h.publish(t, site)
	h.pass(t)

	deployed := appSpec(8)
	deployed.Routes = site.Routes
	workloadOf(deployed, "wl-site").ReleaseId = "dep-1099"
	h.publish(t, deployed)
	h.pass(t)

	if h.edge.syncCount() != 2 {
		t.Fatalf("the edge was loaded %d times across a deployment, want twice", h.edge.syncCount())
	}
}

func TestARebuiltContainerReloadsTheEdgeEvenThoughTheRoutesAreTheSame(t *testing.T) {
	// An edge holding a resolved address for a backend would otherwise go on proxying to a
	// container that no longer exists.
	h := newHarness(t)
	h.publish(t, appSpec(7))
	h.pass(t)

	changed := appSpec(8)
	workloadOf(changed, "wl-api").Env = []*wisperpb.EnvVar{{Name: "NODE_ENV", Value: "staging"}}
	h.publish(t, changed)
	h.pass(t)

	if h.edge.syncCount() != 2 {
		t.Fatalf("the edge was loaded %d times, want a reload after the container was rebuilt",
			h.edge.syncCount())
	}
}

func TestEveryDomainInTheSpecIsReportedEvenBeforeTheEdgeKnowsIt(t *testing.T) {
	// The customer is staring at the domain waiting for it to come up. Reporting only what
	// the edge has loaded would leave the panel showing nothing at all.
	h := newHarness(t)
	h.publish(t, appSpec(7))
	h.pass(t)

	routes := h.panel.latest(t).GetRoutes()
	if len(routes) != 1 || routes[0].GetDomain() != "api.example.test" {
		t.Fatalf("reported routes %v, want the one hostname in the spec", routes)
	}
	if routes[0].GetServing() {
		t.Fatal("a hostname the edge has not answered for was reported as serving")
	}
}

func TestAHostnameTheEdgeStillHoldsAndTheSpecDroppedIsNotReported(t *testing.T) {
	// The panel writes this list into its certificate table; carrying a withdrawn domain
	// would recreate the row the withdrawal removed.
	h := newHarness(t)
	h.edge.statuses = []spec.RouteStatus{
		{Domain: "api.example.test", Serving: true, Certificate: spec.CertificateValid},
		{Domain: "gone.example.test", Serving: true, Certificate: spec.CertificateValid},
	}
	h.publish(t, appSpec(7))
	h.pass(t)

	for _, route := range h.panel.latest(t).GetRoutes() {
		if route.GetDomain() == "gone.example.test" {
			t.Fatal("a hostname that is no longer in the spec was reported to the panel")
		}
	}
}

func TestAHostnameWithTlsOffIsNotReportedAsWaitingForACertificate(t *testing.T) {
	// "No certificate" is the finished state there, and showing it as pending would have
	// the panel warn about something that is working exactly as asked.
	h := newHarness(t)
	plain := appSpec(7)
	plain.Routes[0].TlsMode = wisperpb.TlsMode_TLS_MODE_DISABLED
	h.publish(t, plain)
	h.pass(t)

	got := h.panel.latest(t).GetRoutes()[0].GetCertificate()
	if got != wisperpb.CertificateState_CERTIFICATE_STATE_NONE {
		t.Fatalf("a plain-HTTP hostname reported its certificate as %s, want NONE", got)
	}
}

func TestAnEdgeThatRefusesTheTableIsRetriedOnTheNextPass(t *testing.T) {
	h := newHarness(t)
	h.edge.syncErr = errEngineDown
	h.publish(t, appSpec(7))

	h.failingPass(t)
	if h.edge.syncCount() != 0 {
		t.Fatalf("the edge recorded %d loads after refusing one", h.edge.syncCount())
	}

	h.edge.syncErr = nil
	h.pass(t)
	if h.edge.syncCount() != 1 {
		t.Fatal("the route table was not offered again after the edge refused it")
	}
}
