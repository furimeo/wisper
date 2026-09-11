package state

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

func issued(domain string, at time.Time) Certificate {
	return Certificate{
		Domain:     domain,
		State:      wisperpb.CertificateState_CERTIFICATE_STATE_VALID,
		Issuer:     "Let's Encrypt",
		Serial:     "03:9a:1f",
		NotBefore:  at,
		NotAfter:   at.Add(90 * 24 * time.Hour),
		ObservedAt: at,
	}
}

func TestCertificateRoundTrips(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()
	wanted := issued("api.example.test", noon)

	if err := store.SaveCertificate(ctx, wanted); err != nil {
		t.Fatalf("save the certificate record: %v", err)
	}
	read, err := store.Certificate(ctx, "api.example.test")
	if err != nil {
		t.Fatalf("read the certificate record: %v", err)
	}
	if read != wanted {
		t.Fatalf("the record differs:\n got %+v\nwant %+v", read, wanted)
	}
}

func TestAFailureIsRememberedAcrossARestart(t *testing.T) {
	// "No file" and "the certificate authority rate-limited us four hours ago" need
	// completely different actions from the customer, and only one of them can be recomputed
	// from the certificate directory.
	store := openStore(t)
	ctx := context.Background()

	failure := Certificate{
		Domain:     "new.example.test",
		State:      wisperpb.CertificateState_CERTIFICATE_STATE_FAILED,
		LastError:  "acme: rate limited, retry after 04:00",
		ObservedAt: noon,
	}
	if err := store.SaveCertificate(ctx, failure); err != nil {
		t.Fatalf("save the failure: %v", err)
	}

	read, err := store.Certificate(ctx, "new.example.test")
	if err != nil {
		t.Fatalf("read the record: %v", err)
	}
	if read.LastError != failure.LastError {
		t.Fatalf("the explanation was lost: %q", read.LastError)
	}
	// A domain with no certificate has no expiry, and a zero time must not read back as 1970.
	if !read.NotAfter.IsZero() || !read.NotBefore.IsZero() {
		t.Fatalf("a domain with no certificate has validity %s..%s", read.NotBefore, read.NotAfter)
	}
	if read.Expired(noon) {
		t.Fatal("a domain that never had a certificate reported an expired one")
	}
}

func TestExpiredComparesAgainstTheGivenMoment(t *testing.T) {
	certificate := issued("api.example.test", noon)
	if certificate.Expired(noon.Add(24 * time.Hour)) {
		t.Fatal("a certificate valid for ninety days reported as expired after one")
	}
	if !certificate.Expired(noon.Add(91 * 24 * time.Hour)) {
		t.Fatal("a certificate that ran out a day ago did not report as expired")
	}
}

func TestSavingACertificateAgainReplacesIt(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	if err := store.SaveCertificate(ctx, Certificate{
		Domain:     "api.example.test",
		State:      wisperpb.CertificateState_CERTIFICATE_STATE_ISSUING,
		ObservedAt: noon,
	}); err != nil {
		t.Fatalf("record the issuance: %v", err)
	}
	renewed := issued("api.example.test", noon.Add(time.Minute))
	if err := store.SaveCertificate(ctx, renewed); err != nil {
		t.Fatalf("record the issued certificate: %v", err)
	}

	all, err := store.Certificates(ctx)
	if err != nil {
		t.Fatalf("read the records: %v", err)
	}
	if len(all) != 1 {
		t.Fatalf("re-observing one domain produced %d rows", len(all))
	}
	if all[0].State != wisperpb.CertificateState_CERTIFICATE_STATE_VALID {
		t.Fatalf("state = %s, want VALID", all[0].State)
	}
}

func TestCertificatesComeBackSortedByDomain(t *testing.T) {
	// A status batch built from these is the same batch between two passes that observed the
	// same thing, which is what stops the panel seeing phantom changes.
	store := openStore(t)
	ctx := context.Background()

	for _, domain := range []string{"www.example.test", "api.example.test", "cdn.example.test"} {
		if err := store.SaveCertificate(ctx, issued(domain, noon)); err != nil {
			t.Fatalf("save %s: %v", domain, err)
		}
	}

	all, err := store.Certificates(ctx)
	if err != nil {
		t.Fatalf("read the records: %v", err)
	}
	want := []string{"api.example.test", "cdn.example.test", "www.example.test"}
	for i, domain := range want {
		if all[i].Domain != domain {
			t.Fatalf("record %d is %s, want %s", i, all[i].Domain, domain)
		}
	}
}

func TestAnUnknownDomainIsNotFound(t *testing.T) {
	_, err := openStore(t).Certificate(context.Background(), "nobody.example.test")
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("an unknown domain returned %v, want ErrNotFound", err)
	}
}

func TestACertificateRecordNeedsADomain(t *testing.T) {
	if err := openStore(t).SaveCertificate(context.Background(), Certificate{}); err == nil {
		t.Fatal("a certificate record with no domain was stored")
	}
}

func TestPruningKeepsOnlyTheDomainsInTheSpec(t *testing.T) {
	store := openStore(t)
	ctx := context.Background()

	for _, domain := range []string{"api.example.test", "www.example.test", "removed.example.test"} {
		if err := store.SaveCertificate(ctx, issued(domain, noon)); err != nil {
			t.Fatalf("save %s: %v", domain, err)
		}
	}

	removed, err := store.PruneCertificates(ctx, []string{"api.example.test", "www.example.test"})
	if err != nil {
		t.Fatalf("prune: %v", err)
	}
	if removed != 1 {
		t.Fatalf("pruned %d records, want 1", removed)
	}

	all, err := store.Certificates(ctx)
	if err != nil {
		t.Fatalf("read the records: %v", err)
	}
	if len(all) != 2 {
		t.Fatalf("%d records survived, want 2", len(all))
	}

	emptied, err := store.PruneCertificates(ctx, nil)
	if err != nil {
		t.Fatalf("prune everything: %v", err)
	}
	if emptied != 2 {
		t.Fatalf("an empty keep list dropped %d records, want 2", emptied)
	}
}
