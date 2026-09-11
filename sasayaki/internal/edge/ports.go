package edge

import (
	"context"
	"net/netip"

	"github.com/furimeo/wisper/sasayaki/internal/state"
)

// What this package needs from the rest of the daemon.
//
// Declared here, by the consumer, because that is what keeps the dependency pointing one
// way: the edge knows what a route is and knows nothing about the Docker Engine API or
// SQL, and a test can serve a request with neither of them on the machine.

// Backends is how the edge finds the container behind an app route.
//
// It exists because sasayaki runs on the host and the container it is proxying to lives
// on a tenant bridge. The host can reach that bridge by address but cannot use Docker's
// embedded resolver, which only answers inside a container's own network namespace - so
// the name in the spec has to become an address somewhere, and this is that somewhere.
//
// Called on every dial rather than once per reconcile pass. A container that the engine
// restarts keeps its id and can come back on a different address, and a proxy that had
// cached the old one would go on failing until something unrelated changed the route
// table. Implementations are expected to cache briefly; the edge does not cache at all.
type Backends interface {
	// Address is where to send bytes for a workload's container, or an error explaining
	// why there is nowhere to send them - the container is stopped, has not been created
	// yet, or the engine could not be asked. The error reaches the visitor as the text of
	// a 502 page, so it is written to be read by the person who deployed the thing.
	Address(ctx context.Context, workloadID string, port uint16) (netip.AddrPort, error)
}

// Store is the node's disk, as the edge needs it. Implemented by *state.Store.
//
// Only certificate bookkeeping. The certificates themselves belong to certmagic, which
// keeps them on disk under the node's state root and needs nothing from here; what is
// stored is the metadata the panel shows - whether a hostname is serving, when its
// certificate runs out, and what the certificate authority said the last time it refused.
//
// That last one is the reason this is persisted at all rather than recomputed. A hostname
// whose DNS has not been pointed here yet has no certificate file to read, and "no
// certificate" and "the authority rate-limited us four hours ago" need completely
// different actions from the customer. Losing the difference across a daemon restart
// would leave the panel showing a blank where the explanation belongs.
type Store interface {
	SaveCertificate(ctx context.Context, certificate state.Certificate) error
	// Certificates is every hostname the node knows about. Read once at Start, so the
	// first status batch after a restart carries what was known before it rather than
	// making every domain flicker back to "unknown" until a visitor arrives.
	Certificates(ctx context.Context) ([]state.Certificate, error)
	// PruneCertificates forgets every hostname that is not in keep. Removing the row does
	// not revoke anything: a domain deleted and re-added within the hour reuses its
	// certificate instead of spending the customer's rate limit again.
	PruneCertificates(ctx context.Context, keep []string) (int64, error)
}

// The one implementation, asserted here rather than discovered at wiring time.
var _ Store = (*state.Store)(nil)
