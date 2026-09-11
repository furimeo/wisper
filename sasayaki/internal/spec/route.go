package spec

import "github.com/furimeo/wisper/sasayaki/internal/wisperpb"

// TLSMode is how the edge terminates TLS for one hostname.
type TLSMode string

const (
	// TLSOnDemand fetches a certificate the first time a visitor arrives for a hostname the
	// in-process route table already knows, which is what makes it work while the panel is
	// down (design section 5.4). It is also what an unspecified mode becomes: the
	// alternative default serves plain HTTP for something that asked for TLS, and a silent
	// downgrade is worse than a certificate attempt that fails loudly.
	TLSOnDemand TLSMode = "ON_DEMAND"
	// TLSDisabled is plain HTTP, for a hostname whose DNS still points somewhere else so
	// the customer can check the site before cutting over - and so ACME is not failing at
	// them in the meantime.
	TLSDisabled TLSMode = "DISABLED"
)

// Route is one hostname pointing at one workload.
//
// In v1 a hostname belongs to exactly one node, so no certificate is ever shared between
// machines. The route table is part of the desired state rather than something pushed
// separately, because the on-demand-TLS `ask` handler answers from it in-process and has to
// keep answering when the panel is unreachable.
type Route struct {
	// Lower-cased IDNA A-label, globally unique. Caddy matches it byte for byte.
	Domain     string
	WorkloadID string
	// The container port to proxy to. Ignored when the workload is a site: there is no
	// process and the edge serves the release directory directly.
	Port    uint32
	TLSMode TLSMode
	// Empty means the whole host. Lets one hostname serve an API from one workload and a
	// site from another.
	PathPrefix string
	// Redirect plain HTTP to HTTPS. The panel leaves it off for a hostname whose DNS has
	// not arrived yet, because redirecting to a handshake that will fail turns "not set up
	// yet" into "broken".
	ForceHTTPS bool
}

// WantsCertificate reports whether the edge should be prepared to obtain one for this
// hostname.
func (r Route) WantsCertificate() bool { return r.TLSMode == TLSOnDemand }

func routeFromProto(message *wisperpb.Route) Route {
	mode := TLSOnDemand
	if message.GetTlsMode() == wisperpb.TlsMode_TLS_MODE_DISABLED {
		mode = TLSDisabled
	}
	return Route{
		Domain:     message.GetDomain(),
		WorkloadID: message.GetWorkloadId(),
		Port:       message.GetPort(),
		TLSMode:    mode,
		PathPrefix: message.GetPathPrefix(),
		ForceHTTPS: message.GetForceHttps(),
	}
}
