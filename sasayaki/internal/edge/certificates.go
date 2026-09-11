package edge

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/caddyserver/caddy/v2"

	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// What issuance is doing, per hostname.
//
// certmagic knows all of this and keeps none of it anywhere the panel can see, so it is
// caught here as it happens. Three events are enough to describe the whole life of a
// certificate: one when an attempt starts, one when it succeeds, one when it fails - and
// the third is the one that matters, because its payload is the certificate authority's
// own words. "DNS does not point at this server yet" and "too many certificates already
// issued for this registered domain" are both the customer's to fix and neither is
// fixable from a message that says "failed".
//
// Kept in memory and written through to the node's SQLite by status.go, so a daemon
// restart does not lose the explanation for a domain that has been failing quietly.

// observation is one hostname's certificate, as last seen.
type observation struct {
	state    wisperpb.CertificateState
	issuer   string
	serial   string
	notAfter time.Time
	// lastError is the certificate authority's message, verbatim, kept even after a
	// later attempt succeeds is not: a success clears it, because leaving yesterday's
	// failure attached to a working certificate would have the panel warn about nothing.
	lastError  string
	observedAt time.Time
}

// certificates is the observer. Also a caddyevents.Handler.
type certificates struct {
	mu       sync.Mutex
	byDomain map[string]observation
	// written is the last row handed to the store for each hostname, so a status pass
	// that learned nothing does not write to SQLite once per domain. On a node with two
	// hundred hostnames that is two hundred writes every fifteen seconds, forever, to
	// record that nothing changed.
	written map[string]observation
	now     func() time.Time
}

func newCertificates(now func() time.Time) *certificates {
	return &certificates{
		byDomain: make(map[string]observation),
		written:  make(map[string]observation),
		now:      now,
	}
}

// seed loads what was known before the daemon restarted.
func (c *certificates) seed(stored []state.Certificate) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, record := range stored {
		c.byDomain[normaliseHost(record.Domain)] = observation{
			state:      record.State,
			issuer:     record.Issuer,
			serial:     record.Serial,
			notAfter:   record.NotAfter,
			lastError:  record.LastError,
			observedAt: record.ObservedAt,
		}
	}
}

// Handle records one certmagic event.
//
// It never returns an error and never blocks on anything: events are dispatched
// synchronously inside certmagic's issuance path, so a handler that took a slow lock here
// would slow down every certificate on the node.
func (c *certificates) Handle(_ context.Context, event caddy.Event) error {
	domain := normaliseHost(stringField(event.Data, "identifier"))
	if domain == "" {
		return nil
	}

	c.mu.Lock()
	defer c.mu.Unlock()
	current := c.byDomain[domain]
	current.observedAt = c.now()

	switch event.Name() {
	case "cert_obtaining":
		current.state = wisperpb.CertificateState_CERTIFICATE_STATE_ISSUING
	case "cert_obtained":
		current.state = wisperpb.CertificateState_CERTIFICATE_STATE_VALID
		current.issuer = stringField(event.Data, "issuer")
		current.lastError = ""
	case "cert_failed":
		current.state = wisperpb.CertificateState_CERTIFICATE_STATE_FAILED
		current.lastError = errorField(event.Data, "error")
	default:
		return nil
	}

	c.byDomain[domain] = current
	return nil
}

// observe is what is known about one hostname right now.
//
// It merges two sources, and the order is deliberate: whatever is in the cache is the
// truth about the certificate that is being served, and the event history is the truth
// about what happened while trying to get it. A hostname with a valid certificate in the
// cache and a failure recorded three hours ago is valid and has a warning attached, not
// failed - the failure was a renewal attempt, and the certificate it would have replaced
// is still working.
func (c *certificates) observe(domain string, leaf certificateFacts, at time.Time) observation {
	c.mu.Lock()
	defer c.mu.Unlock()

	current := c.byDomain[domain]
	switch {
	case leaf.present:
		current.state = wisperpb.CertificateState_CERTIFICATE_STATE_VALID
		current.issuer = leaf.issuer
		current.serial = leaf.serial
		current.notAfter = leaf.notAfter
		current.observedAt = at
	case !leaf.readable:
		// Nobody could look, so nothing was learned. The events are still the truth.
	case current.state == wisperpb.CertificateState_CERTIFICATE_STATE_VALID:
		// It was valid and the cache no longer has it: the certificate was removed
		// because the hostname stopped being managed, or it expired and could not be
		// renewed. Either way, claiming it is still valid would be the one report a
		// customer cannot act on.
		current.state = wisperpb.CertificateState_CERTIFICATE_STATE_UNSPECIFIED
		current.notAfter = time.Time{}
		current.serial = ""
		current.observedAt = at
	}

	c.byDomain[domain] = current
	return current
}

// forget drops the hostnames that are no longer routed, so a domain a customer removed
// and re-added does not come back wearing its old failure.
func (c *certificates) forget(keep []string) {
	wanted := make(map[string]struct{}, len(keep))
	for _, domain := range keep {
		wanted[domain] = struct{}{}
	}

	c.mu.Lock()
	defer c.mu.Unlock()
	for domain := range c.byDomain {
		if _, kept := wanted[domain]; !kept {
			delete(c.byDomain, domain)
			delete(c.written, domain)
		}
	}
}

// changed reports whether a row is worth writing, and remembers it if it is.
//
// Optimistic: the row is marked written before the store has accepted it, so a failed
// write is not retried until something about the certificate changes. That is the right
// way round - the store is a convenience for the next cold start, and retrying a write
// that is failing for a structural reason every fifteen seconds forever would turn a
// harmless problem into a log nobody can read.
func (c *certificates) changed(domain string, record observation) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	if previous, known := c.written[domain]; known && previous.sameAs(record) {
		return false
	}
	c.written[domain] = record
	return true
}

// record turns an observation into the row the store keeps.
func (o observation) record(domain string) state.Certificate {
	return state.Certificate{
		Domain:     domain,
		State:      o.state,
		Issuer:     o.issuer,
		Serial:     o.serial,
		NotAfter:   o.notAfter,
		LastError:  o.lastError,
		ObservedAt: o.observedAt,
	}
}

// sameAs reports whether two observations say the same thing, so a status pass that
// learned nothing does not write to SQLite once per domain.
func (o observation) sameAs(other observation) bool {
	return o.state == other.state &&
		o.issuer == other.issuer &&
		o.serial == other.serial &&
		o.notAfter.Equal(other.notAfter) &&
		o.lastError == other.lastError
}

func stringField(data map[string]any, key string) string {
	value, _ := data[key].(string)
	return value
}

// errorField reads the "error" field, which certmagic sets to an error value rather than
// a string. Anything else that turns up is rendered rather than dropped: a message that
// reads oddly is still better than a blank where the reason should be.
func errorField(data map[string]any, key string) string {
	switch value := data[key].(type) {
	case nil:
		return ""
	case error:
		return value.Error()
	case string:
		return value
	default:
		return fmt.Sprint(value)
	}
}
