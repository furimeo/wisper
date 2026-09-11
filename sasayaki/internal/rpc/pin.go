package rpc

import (
	"crypto/sha256"
	"crypto/subtle"
	"crypto/x509"
	"encoding/hex"
	"fmt"
	"strings"
	"sync"
)

// Trust on first use, and never again.
//
// The panel sits behind a tunnel. There is no certificate authority a node can be told
// to trust that would mean anything - whoever owns the tunnel can get a valid public
// certificate for the same name, and a private CA is one more thing to distribute and
// rotate by hand, which is the mistake this project's predecessor made and never
// recovered from.
//
// So the node looks at exactly one certificate, once, during enrolment, writes down its
// SHA-256, and from then on refuses to speak to anything presenting a different one.
// That is strictly stronger than chain validation for this shape of deployment: a
// hijacked DNS record or a stolen tunnel gets a connection refused rather than a
// machine-in-the-middle (design section 7.1).
//
// The consequence, which is deliberate and must not be "fixed": rotating the panel's
// certificate means re-enrolling the nodes. A pin that can be replaced by whoever is on
// the other end of the wire is not a pin.

// FingerprintMismatch is the error a pinned dial fails with. It is a type rather than a
// sentinel because an operator staring at a node that will not connect needs both
// halves: what was expected and what actually answered.
type FingerprintMismatch struct {
	Expected string
	Observed string
}

func (e *FingerprintMismatch) Error() string {
	return fmt.Sprintf(
		"panel certificate changed: pinned %s, saw %s. "+
			"Either the panel's certificate was replaced - re-enrol this node - or "+
			"something is terminating TLS in the middle",
		e.Expected, e.Observed)
}

// Fingerprint is the hex SHA-256 of a DER certificate: lower case, no separators. It is
// the exact form EnrollResponse.panel_certificate_sha256 carries, so the two are
// comparable as strings without either side normalising the other's spelling.
func Fingerprint(der []byte) string {
	sum := sha256.Sum256(der)
	return hex.EncodeToString(sum[:])
}

// FingerprintOf is Fingerprint for a parsed certificate, which is what a test or an
// enrolment already holds.
func FingerprintOf(certificate *x509.Certificate) string {
	return Fingerprint(certificate.Raw)
}

// NormalisePin accepts the shapes a fingerprint gets copied in - upper case from a
// browser, colon-separated from openssl, prefixed with "sha256:" - and returns the one
// shape everything else here compares.
func NormalisePin(pin string) (string, error) {
	cleaned := strings.ToLower(strings.TrimSpace(pin))
	cleaned = strings.TrimPrefix(cleaned, "sha256:")
	cleaned = strings.ReplaceAll(cleaned, ":", "")
	cleaned = strings.ReplaceAll(cleaned, " ", "")
	if cleaned == "" {
		return "", nil
	}
	raw, err := hex.DecodeString(cleaned)
	if err != nil {
		return "", fmt.Errorf("certificate fingerprint %q is not hexadecimal: %w", pin, err)
	}
	if len(raw) != sha256.Size {
		return "", fmt.Errorf("certificate fingerprint %q is %d bytes, want %d", pin, len(raw), sha256.Size)
	}
	return cleaned, nil
}

// verifyAgainstPin is tls.Config.VerifyPeerCertificate for a node that already knows
// which certificate it is talking to.
//
// The chain is deliberately not walked: InsecureSkipVerify is set on the config that
// installs this, because the pin - not a public root - is the trust anchor. Turning
// chain validation back on would break every panel behind a tunnel with a private
// certificate and would add nothing, since a valid chain for the wrong host is exactly
// what the pin exists to reject.
//
// report is called with the mismatch before the handshake is failed. gRPC turns a
// handshake failure into a plain Unavailable status with the reason flattened into a
// string, so without this hook the one thing an operator needs to be told - that the
// certificate changed, not that the network is down - would be indistinguishable from
// an unplugged cable.
func verifyAgainstPin(pin string, report func(*FingerprintMismatch)) func([][]byte, [][]*x509.Certificate) error {
	return func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
		if len(rawCerts) == 0 {
			return fmt.Errorf("panel presented no certificate")
		}
		observed := Fingerprint(rawCerts[0])
		if subtle.ConstantTimeCompare([]byte(observed), []byte(pin)) != 1 {
			mismatch := &FingerprintMismatch{Expected: pin, Observed: observed}
			if report != nil {
				report(mismatch)
			}
			return mismatch
		}
		return nil
	}
}

// certificateObserver records the leaf of whatever handshake actually happened.
//
// Used for the one connection that has no pin yet - enrolment - so the fingerprint the
// node stores is the certificate it genuinely spoke to, not one fetched by a second
// connection that could have landed somewhere else.
type certificateObserver struct {
	mu          sync.Mutex
	fingerprint string
}

func (o *certificateObserver) verify(rawCerts [][]byte, _ [][]*x509.Certificate) error {
	if len(rawCerts) == 0 {
		return fmt.Errorf("panel presented no certificate")
	}
	o.mu.Lock()
	defer o.mu.Unlock()
	o.fingerprint = Fingerprint(rawCerts[0])
	return nil
}

func (o *certificateObserver) seen() string {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.fingerprint
}
