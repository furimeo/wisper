package reconcile

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// The route table, and getting it into the edge.
//
// Routes are part of the desired state rather than something pushed on their own, because
// the on-demand-TLS `ask` handler answers from the in-process table and has to keep
// answering while the panel is unreachable (design section 5.4). Reconciling them is
// therefore the same act as reconciling a workload: the spec says which hostnames this
// node answers for, and anything else it is serving is withdrawn.

// syncEdge hands the route table to the edge, when it has changed.
//
// Two things make it change. The obvious one is the spec: a new domain, a new release for
// a site, a certificate mode switched off. The other is a container that was rebuilt this
// pass, which is why the caller passes rebuilt: an implementation that keeps a resolved
// address for a backend would otherwise go on proxying to a container that no longer
// exists, and the customer would see a 502 that no amount of waiting fixes.
func (l *Loop) syncEdge(ctx context.Context, desired spec.Spec, rebuilt bool) error {
	fingerprint := routeFingerprint(desired)
	if fingerprint == l.syncedRoutes && !rebuilt {
		return nil
	}

	if err := l.edge.Sync(ctx, desired); err != nil {
		// Left unrecorded so the next pass tries again. A route table the edge refused is
		// not a route table it has.
		l.syncedRoutes = ""
		return fmt.Errorf("load %d routes into the edge: %w", len(desired.Routes), err)
	}
	l.syncedRoutes = fingerprint
	return nil
}

// routeStatuses is one status per hostname in the spec, in spec order.
//
// Driven by the spec rather than by what the edge reports, and that is the point: a
// hostname the customer added and the edge has not loaded yet has to appear as not
// serving. Reporting only what the edge knows would leave the panel showing nothing at all
// for the domain somebody is staring at, waiting for it to come up.
//
// Hostnames the edge still has and the spec no longer names are left out. The panel writes
// this list into its certificate table; carrying a withdrawn domain would recreate the row
// the withdrawal was supposed to remove.
func (l *Loop) routeStatuses(ctx context.Context, desired spec.Spec) ([]spec.RouteStatus, error) {
	reported, err := l.edge.Statuses(ctx)
	if err != nil {
		return nil, fmt.Errorf("read the edge's route statuses: %w", err)
	}

	byDomain := make(map[string]spec.RouteStatus, len(reported))
	for _, status := range reported {
		byDomain[status.Domain] = status
	}

	statuses := make([]spec.RouteStatus, 0, len(desired.Routes))
	for _, route := range desired.Routes {
		if status, known := byDomain[route.Domain]; known {
			statuses = append(statuses, status)
			continue
		}
		statuses = append(statuses, spec.RouteStatus{
			Domain:      route.Domain,
			Serving:     false,
			Certificate: pendingCertificate(route),
		})
	}
	return statuses, nil
}

// pendingCertificate is what to say about a hostname the edge has not answered for yet.
//
// NONE for a route that does not want a certificate at all, because "no certificate" is
// the finished state there and showing it as pending would have the panel warn about
// something that is working as asked.
func pendingCertificate(route spec.Route) spec.CertificateState {
	if !route.WantsCertificate() {
		return spec.CertificateNone
	}
	return spec.CertificateUnspecified
}

// routeFingerprint hashes everything about the spec that the edge serves from.
//
// Deliberately narrower than the whole document. Reloading the edge means rebuilding a
// Caddy configuration and re-evaluating every hostname, and doing that because an
// environment variable changed on an unrelated container would be work for nothing many
// times a day on a busy node.
func routeFingerprint(desired spec.Spec) string {
	c := canonical{digest: sha256.New()}
	c.text(fingerprintVersion)

	c.number(int64(len(desired.Routes)))
	for _, route := range desired.Routes {
		c.text(route.Domain)
		c.text(route.WorkloadID)
		c.number(int64(route.Port))
		c.text(string(route.TLSMode))
		c.text(route.PathPrefix)
		c.flag(route.ForceHTTPS)

		// The backend, as far as the edge can see it. A site's release id is in here
		// because publishing a new one is exactly the change the edge must notice.
		workload, found := desired.Workload(route.WorkloadID)
		c.flag(found)
		if !found {
			continue
		}
		c.text(string(workload.Kind))
		c.text(workload.Name)
		c.text(workload.TenantNetwork)
		c.text(string(workload.Desired))
		c.text(workload.ReleaseID)
		c.flag(workload.Site.SPAFallback)
		c.text(workload.Site.Index())
		c.text(workload.Site.NotFoundFile)
		c.flag(workload.Site.DirectoryListing)
		c.number(int64(len(workload.Ports)))
		for _, port := range workload.Ports {
			c.number(int64(port.Container))
			c.number(int64(port.Host))
			c.text(string(port.Protocol))
			c.text(port.HostIP)
		}
	}

	return hex.EncodeToString(c.digest.Sum(nil))
}
