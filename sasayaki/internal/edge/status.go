package edge

import (
	"context"
	"log/slog"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// What the edge is really doing, per hostname.
//
// Read on every reconcile pass and sent to the panel, where it becomes the certificate
// table and the answer to the only question a customer who has just pointed DNS at this
// node is asking: is it working yet, and if not, what did the certificate authority say.
//
// LastError is the certificate authority's message and nothing else. It lands in the
// panel's certificate row (docs/contracts/node-spec.md section 5), so putting "this
// container is stopped" in it would file an application's problem under TLS - and the
// panel already learns that from the workload's own status, which is where a customer
// will look for it.

// Statuses is one status per hostname the edge is serving, in the order the spec listed
// them.
func (e *Edge) Statuses(ctx context.Context) ([]spec.RouteStatus, error) {
	at := e.now()
	published := e.routes.load()

	statuses := make([]spec.RouteStatus, 0, len(published.domains))
	for _, domain := range published.domains {
		statuses = append(statuses, e.statusOf(published, domain, at))
	}

	e.persist(ctx, statuses, at)
	return statuses, nil
}

// statusOf is one hostname's status.
func (e *Edge) statusOf(published *table, domain string, at time.Time) spec.RouteStatus {
	status := spec.RouteStatus{Domain: domain, Serving: true}

	// Every route for the hostname has to be serving for the hostname to be. A domain
	// that serves a site at / and an API at /api is not working when half of it answers,
	// and reporting it as fine would leave the customer looking at a green tick while
	// their application is down.
	for _, candidate := range published.hosts[domain] {
		if !candidate.serving {
			status.Serving = false
			break
		}
	}

	if !published.wantsCertificate(domain) {
		// TLS is off for this hostname on purpose. NONE is the finished state, not a
		// pending one, and reporting anything else would have the panel warn about
		// something that is working exactly as asked.
		status.Certificate = spec.CertificateNone
		return status
	}

	observed := e.certificates.observe(domain, e.leaf(domain), at)
	status.Certificate = certificateState(observed.state)
	status.NotAfter = observed.notAfter
	status.LastError = observed.lastError
	return status
}

// leaf reads Caddy's certificate cache, when there is one to read.
//
// The cache is created while the TLS app is provisioned, so before Start there is nothing
// there and asking would be a nil dereference inside a dependency. Statuses is called by
// the reconcile loop, which begins its first pass the instant the daemon starts - so this
// is a race that would happen on roughly every cold boot rather than a theoretical one.
func (e *Edge) leaf(domain string) certificateFacts {
	if !e.started.Load() {
		return certificateFacts{}
	}
	return leafFor(domain)
}

// persist writes the certificate rows the panel will be told about.
//
// Best effort, and deliberately not part of the returned error: the statuses are correct
// whether or not SQLite accepted them, and a pass that reported the truth to the panel and
// failed to write it down has still reported the truth. What the write buys is the next
// cold start, where it is the difference between a domain showing its history and showing
// a blank.
func (e *Edge) persist(ctx context.Context, statuses []spec.RouteStatus, at time.Time) {
	for _, status := range statuses {
		if status.Certificate == spec.CertificateNone {
			// Nothing to keep: a hostname that never wanted a certificate has no history
			// worth surviving a restart, and writing one would leave a row behind after
			// the customer switched TLS on and the state moved somewhere real.
			continue
		}

		record := observation{
			state:      certificateStateToProto(status.Certificate),
			notAfter:   status.NotAfter,
			lastError:  status.LastError,
			observedAt: at,
		}
		if known, found := e.certificates.snapshot(status.Domain); found {
			record.issuer = known.issuer
			record.serial = known.serial
		}
		if !e.certificates.changed(status.Domain, record) {
			continue
		}

		if err := e.store.SaveCertificate(ctx, record.record(status.Domain)); err != nil {
			e.log.Warn("could not write down what is known about a certificate",
				slog.String("domain", status.Domain),
				slog.String("error", err.Error()))
			// One bad row does not stop the rest: the next domain's history is not the
			// broken one's fault.
		}
	}
}

// snapshot is what is currently recorded for a hostname.
func (c *certificates) snapshot(domain string) (observation, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	current, found := c.byDomain[domain]
	return current, found
}

func certificateState(value wisperpb.CertificateState) spec.CertificateState {
	switch value {
	case wisperpb.CertificateState_CERTIFICATE_STATE_NONE:
		return spec.CertificateNone
	case wisperpb.CertificateState_CERTIFICATE_STATE_ISSUING:
		return spec.CertificateIssuing
	case wisperpb.CertificateState_CERTIFICATE_STATE_VALID:
		return spec.CertificateValid
	case wisperpb.CertificateState_CERTIFICATE_STATE_FAILED:
		return spec.CertificateFailed
	default:
		return spec.CertificateUnspecified
	}
}

func certificateStateToProto(value spec.CertificateState) wisperpb.CertificateState {
	switch value {
	case spec.CertificateNone:
		return wisperpb.CertificateState_CERTIFICATE_STATE_NONE
	case spec.CertificateIssuing:
		return wisperpb.CertificateState_CERTIFICATE_STATE_ISSUING
	case spec.CertificateValid:
		return wisperpb.CertificateState_CERTIFICATE_STATE_VALID
	case spec.CertificateFailed:
		return wisperpb.CertificateState_CERTIFICATE_STATE_FAILED
	default:
		return wisperpb.CertificateState_CERTIFICATE_STATE_UNSPECIFIED
	}
}
