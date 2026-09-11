package state

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// What the edge knows about each domain's certificate.
//
// The certificates themselves are certmagic's, on disk under the node's state directory,
// and this table does not duplicate them - it is the metadata the panel asks for: is this
// domain serving, when does its certificate run out, and what did ACME say the last time
// it failed (workload.proto, RouteStatus).
//
// It is persisted rather than recomputed from the certificate files for the failures. A
// domain whose DNS does not point here yet has no certificate to read, and "no file" and
// "the certificate authority rate-limited us four hours ago" need completely different
// actions from the customer. Losing that on a restart would leave the panel showing a
// blank where the explanation should be.
var certificateSchema = []string{`
CREATE TABLE certificate (
	domain      TEXT PRIMARY KEY,
	state       INTEGER NOT NULL,
	issuer      TEXT    NOT NULL,
	serial      TEXT    NOT NULL,
	not_before  INTEGER,
	not_after   INTEGER,
	last_error  TEXT    NOT NULL,
	observed_at INTEGER NOT NULL
) STRICT`}

// Certificate is one domain's TLS status as the node last observed it.
type Certificate struct {
	Domain string
	State  wisperpb.CertificateState
	// "Let's Encrypt", "ZeroSSL", or whatever certmagic actually used. Which issuer
	// answered matters when one of them is rate-limiting and the other is not.
	Issuer string
	Serial string
	// Zero when there is no certificate. NotAfter is what lets the panel warn before a
	// renewal that has been failing quietly runs out.
	NotBefore time.Time
	NotAfter  time.Time
	// The ACME failure, verbatim. "DNS does not point here yet" and "rate limited" are
	// both the customer's problem to fix and neither is fixable from a generic message.
	LastError  string
	ObservedAt time.Time
}

// Expired reports whether the certificate had run out at the given moment. A certificate
// with no expiry - because none was ever issued - is not expired; it is absent, which the
// State field already says.
func (c Certificate) Expired(at time.Time) bool {
	return !c.NotAfter.IsZero() && at.After(c.NotAfter)
}

// SaveCertificate records what the edge now knows about a domain.
func (s *Store) SaveCertificate(ctx context.Context, certificate Certificate) error {
	if certificate.Domain == "" {
		return errors.New("state: a certificate record needs a domain")
	}
	_, err := s.db.ExecContext(ctx, `
INSERT INTO certificate (domain, state, issuer, serial, not_before, not_after, last_error, observed_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (domain) DO UPDATE SET
	state       = excluded.state,
	issuer      = excluded.issuer,
	serial      = excluded.serial,
	not_before  = excluded.not_before,
	not_after   = excluded.not_after,
	last_error  = excluded.last_error,
	observed_at = excluded.observed_at`,
		certificate.Domain, int64(certificate.State), certificate.Issuer, certificate.Serial,
		nullableMillis(certificate.NotBefore), nullableMillis(certificate.NotAfter),
		certificate.LastError, epochMillis(certificate.ObservedAt))
	if err != nil {
		return fmt.Errorf("state: store the certificate record for %s: %w", certificate.Domain, err)
	}
	return nil
}

// Certificate is one domain's record, or ErrNotFound.
func (s *Store) Certificate(ctx context.Context, domain string) (Certificate, error) {
	row := s.db.QueryRowContext(ctx, certificateColumns+` FROM certificate WHERE domain = ?`, domain)
	certificate, err := scanCertificate(row)
	if errors.Is(err, sql.ErrNoRows) {
		return Certificate{}, fmt.Errorf("state: certificate for %s: %w", domain, ErrNotFound)
	}
	return certificate, err
}

// Certificates is every domain the node knows about, by domain.
//
// This is what a status batch is built from, including the first one after a restart:
// route statuses can be reported before the edge has finished loading, so a customer
// watching a domain come up does not see it flicker back to "unknown".
func (s *Store) Certificates(ctx context.Context) ([]Certificate, error) {
	rows, err := s.db.QueryContext(ctx, certificateColumns+` FROM certificate ORDER BY domain`)
	if err != nil {
		return nil, fmt.Errorf("state: read certificate records: %w", err)
	}
	defer rows.Close()

	var out []Certificate
	for rows.Next() {
		certificate, err := scanCertificate(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, certificate)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("state: read certificate records: %w", err)
	}
	return out, nil
}

// PruneCertificates forgets every domain that is not in keep, and reports how many it
// dropped. keep is the set of domains in the current spec's routes; a domain the customer
// removed should stop being reported, not linger as a route the panel cannot account for.
//
// The certificate files themselves are certmagic's to manage. Removing the row does not
// revoke anything, which is deliberate: a domain that is deleted and re-added within the
// hour reuses the certificate instead of asking the certificate authority again and
// spending the customer's rate limit.
func (s *Store) PruneCertificates(ctx context.Context, keep []string) (int64, error) {
	query := `DELETE FROM certificate`
	var arguments []any
	if len(keep) > 0 {
		query += ` WHERE domain NOT IN (` + placeholders(len(keep)) + `)`
		arguments = anySlice(keep)
	}

	result, err := s.db.ExecContext(ctx, query, arguments...)
	if err != nil {
		return 0, fmt.Errorf("state: prune certificate records: %w", err)
	}
	removed, err := result.RowsAffected()
	if err != nil {
		return 0, fmt.Errorf("state: prune certificate records: %w", err)
	}
	return removed, nil
}

const certificateColumns = `
SELECT domain, state, issuer, serial, not_before, not_after, last_error, observed_at`

func scanCertificate(row scanner) (Certificate, error) {
	var (
		certificate Certificate
		state       int64
		notBefore   sql.NullInt64
		notAfter    sql.NullInt64
		observedAt  int64
	)
	err := row.Scan(&certificate.Domain, &state, &certificate.Issuer, &certificate.Serial,
		&notBefore, &notAfter, &certificate.LastError, &observedAt)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return Certificate{}, err
		}
		return Certificate{}, fmt.Errorf("state: read a certificate record: %w", err)
	}
	certificate.State = wisperpb.CertificateState(state)
	certificate.NotBefore = optionalInstant(notBefore)
	certificate.NotAfter = optionalInstant(notAfter)
	certificate.ObservedAt = instant(observedAt)
	return certificate, nil
}
