package edge

import (
	"time"

	"github.com/caddyserver/caddy/v2/modules/caddytls"
)

// Reading the certificate that is actually being served.
//
// certmagic keeps every certificate it has loaded in a cache shared across the process,
// and that cache - not the files on disk and not the event history - is the truth about
// what a visitor's handshake will be given right now. So the expiry the panel warns on is
// read from there, which also means the panel is warning about the certificate in use
// rather than one that happens to still be lying in storage.
//
// The cache is a package-level value inside caddytls, created when the TLS app is
// provisioned. Nothing may read it before that has happened, which is why every call here
// goes through Edge.started.

// certificateFacts is what one hostname's leaf certificate says.
type certificateFacts struct {
	// readable is false when there was no cache to look in, which is the state before
	// Caddy has been started. It is separate from present because the two mean opposite
	// things: "the cache says there is no certificate" is information, and "nobody could
	// ask" is not, and treating the second as the first would have a daemon that has not
	// finished starting report every domain as having lost its certificate.
	readable bool
	// present is false when nothing in the cache covers this hostname. Not a failure: it
	// is the state every domain is in until the first handshake arrives for it.
	present  bool
	issuer   string
	serial   string
	notAfter time.Time
}

// leafFor is the certificate the edge would present for a hostname.
//
// When several match - an old one still cached beside a renewal - the one that lasts
// longest wins, because that is the one certmagic will choose to serve and reporting any
// other would have the panel warn about an expiry that is never going to happen.
func leafFor(host string) certificateFacts {
	facts := certificateFacts{readable: true}

	for _, candidate := range caddytls.AllMatchingCertificates(host) {
		leaf := candidate.Leaf
		if leaf == nil {
			continue
		}
		if facts.present && !leaf.NotAfter.After(facts.notAfter) {
			continue
		}
		facts = certificateFacts{
			readable: true,
			present:  true,
			issuer:   leaf.Issuer.CommonName,
			serial:   leaf.SerialNumber.String(),
			notAfter: leaf.NotAfter,
		}
	}
	return facts
}
