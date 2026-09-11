package lhqm.furimeo.wisper.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A certificate as the domains page may see it, matching {@code CertificateView} in
 * pages.md §6.
 *
 * <p>A separate record from {@link Certificate} for the same reason every other view record
 * exists: a controller's model attributes are serialised straight into the page's props, so
 * what a page can show is decided once, here, rather than in each controller. There is
 * nothing secret on a certificate row - the private key is on the node and there is no
 * column for it - but the id and the row version are noise on a screen, and a shape the
 * TypeScript declaration can be checked against is worth having anyway.
 *
 * @param state              where issuance stands, node-reported
 * @param notAfter           when it stops being valid; the expiry warning reads this
 * @param renewalFailureCount how many times issuance has gone wrong in a row. Zero the
 *                           moment one succeeds, so a number here always means "failing
 *                           now".
 * @param lastError          the ACME failure, verbatim. "DNS does not point here yet" and
 *                           "rate limited" need completely different actions from the
 *                           customer, so it is not summarised.
 */
public record CertificateView(
        CertificateState state,
        String issuer,
        String subjectCommonName,
        List<String> subjectAlternativeNames,
        String fingerprintSha256,
        Instant notBefore,
        Instant notAfter,
        Instant obtainedAt,
        Instant lastRenewalAttemptAt,
        int renewalFailureCount,
        String lastError,
        UUID nodeId) {

    public static CertificateView of(Certificate certificate) {
        return new CertificateView(certificate.state(), certificate.issuer(),
                certificate.subjectCommonName(), certificate.alternativeNames(),
                certificate.fingerprintSha256(), certificate.notBefore(), certificate.notAfter(),
                certificate.obtainedAt(), certificate.lastRenewalAttemptAt(),
                certificate.renewalFailureCount(), certificate.lastError(), certificate.nodeId());
    }

    /** Whether this is the certificate visitors are being served right now. */
    public boolean isLive() {
        return state.isLive();
    }
}
