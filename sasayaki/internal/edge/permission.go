package edge

import (
	"context"
	"fmt"

	"github.com/caddyserver/caddy/v2"
	"github.com/caddyserver/caddy/v2/modules/caddyevents"
	"github.com/caddyserver/caddy/v2/modules/caddytls"
)

// The on-demand TLS decision, answered in this process.
//
// This is the single most load-bearing thing in the package. Caddy will not obtain a
// certificate for a hostname it has never been told about without asking permission
// first, and the stock way to ask is an HTTP request to somewhere else. On this platform
// "somewhere else" would be the panel, behind a tunnel, and the day the tunnel is down is
// the day no node can issue a certificate for anybody - a control-plane outage becoming a
// data-plane outage, which is the exact failure the whole architecture is arranged to
// avoid (design section 5.4).
//
// So the answer comes from the route table this daemon already holds. It is a map lookup
// against a snapshot that was published atomically: no socket, no timeout, no dependency
// that can be unreachable, and no possibility of the handshake being allowed for a
// hostname the request handler would not serve, because both read the same snapshot.
//
// The abuse this guards against is worth naming, because it is why Caddy insists on it:
// :443 on a public address will be reached by anyone who points a DNS record at it, and a
// node that tried to obtain a certificate for every name in every handshake would burn a
// certificate authority's rate limit within the hour and be locked out for the customers
// who actually own domains here.

func init() {
	caddy.RegisterModule(permission{})
}

// permission is `tls.permission.wisper_edge` in a Caddy configuration.
type permission struct {
	// Edge names the edge whose route table decides. Same bridge as the handler's.
	Edge string `json:"edge"`

	edge *Edge
}

func (permission) CaddyModule() caddy.ModuleInfo {
	return caddy.ModuleInfo{
		ID:  "tls.permission.wisper_edge",
		New: func() caddy.Module { return new(permission) },
	}
}

// Provision binds the module to its edge and, while it has a context to do it with,
// subscribes that edge to the certificate events certmagic emits.
//
// The subscription lives here rather than anywhere else for a reason that is not just
// convenience: this module is the one part of the configuration that exists exactly once
// and exists only when on-demand issuance is configured, which is exactly when there are
// issuance events to observe. Subscriptions can only be made while the events app is
// still being provisioned, so there is no later moment to do it in.
func (p *permission) Provision(ctx caddy.Context) error {
	edge, err := lookup(p.Edge)
	if err != nil {
		return err
	}
	p.edge = edge

	app, err := ctx.App("events")
	if err != nil {
		return fmt.Errorf("edge: reach the events app to observe certificate issuance: %w", err)
	}
	events, ok := app.(*caddyevents.App)
	if !ok {
		return fmt.Errorf("edge: the events app is a %T, which is not something this daemon "+
			"can subscribe to", app)
	}

	err = events.Subscribe(&caddyevents.Subscription{
		Events:   []string{"cert_obtaining", "cert_obtained", "cert_failed"},
		Handlers: []caddyevents.Handler{edge.certificates},
	})
	if err != nil {
		return fmt.Errorf("edge: subscribe to certificate issuance events: %w", err)
	}
	return nil
}

// CertificateAllowed is the decision.
//
// Called during a TLS handshake, possibly on every one of them, so it does exactly one
// thing: normalise the server name and look it up. A hostname is allowed a certificate
// when this node has a route for it that asked for TLS - which means a hostname that has
// just been withdrawn from the spec is refused from the moment the table was swapped,
// with no cache to expire and no webhook to notice.
func (p *permission) CertificateAllowed(_ context.Context, name string) error {
	host := normaliseHost(name)
	published := p.edge.routes.load()

	if published.wantsCertificate(host) {
		return nil
	}
	if published.known(host) {
		// Routed here, but the panel asked for plain HTTP - usually because the customer
		// is checking the site before moving DNS, and failing ACME at them in the
		// meantime would be noise they cannot act on.
		return fmt.Errorf("edge: %s is served by this node with TLS switched off, so no "+
			"certificate is obtained for it", host)
	}
	return fmt.Errorf("edge: %s is not a hostname this node serves", host)
}

var (
	_ caddy.Provisioner           = (*permission)(nil)
	_ caddytls.OnDemandPermission = (*permission)(nil)
	_ caddyevents.Handler         = (*certificates)(nil)
)
