package lhqm.furimeo.wisper.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One hostname as the domains page sees it, matching {@code DomainView} in pages.md §6.
 *
 * <p>Carries the two things a customer stuck on "why is nothing happening" needs and the
 * {@code domain} row alone cannot answer: the certificate, and whether the node is actually
 * serving the name.
 *
 * @param certificate the live certificate, or the most recent attempt when there is no live
 *                    one. pages.md describes this as "the live one, or null"; a hostname
 *                    whose issuance keeps failing has no live one and its error is the whole
 *                    reason the customer opened the page, so the last attempt is shown
 *                    instead of nothing.
 * @param serving     whether the node's edge is answering for this hostname.
 *                    <strong>Derived</strong>: the schema has no boolean for it -
 *                    {@code certificate} is the only node-written table keyed by domain -
 *                    so it is read off the state a node's report leaves behind. A hostname
 *                    is serving when a node holds its service and, unless TLS is switched
 *                    off, that node has a live certificate for it. The one case this
 *                    misreads is a route the edge has loaded whose container has crashed;
 *                    that is a workload status and the page shows it separately.
 * @param challengeRecordName  what the customer creates the TXT record at, when they cannot
 *                    point A/AAAA yet
 * @param challengeRecordValue what they put in it
 */
public record DomainView(
        UUID id,
        UUID serviceId,
        String hostname,
        DomainKind kind,
        DomainTlsMode tlsMode,

        DomainVerification verificationState,
        String verificationToken,
        Instant verifiedAt,
        Instant lastCheckedAt,
        String lastCheckError,

        String redirectToHostname,
        boolean forceHttps,
        Integer targetPort,
        Instant createdAt,

        CertificateView certificate,
        boolean serving,
        String challengeRecordName,
        String challengeRecordValue) {

    /**
     * @param certificate the live row, or the newest one, or null when the node has never
     *                    said anything about this hostname
     * @param serving     computed by {@link ListServiceDomains}, which is the only place
     *                    that has the placement and the certificate in one query
     */
    public static DomainView of(Domain domain, Certificate certificate, boolean serving) {
        return new DomainView(domain.id(), domain.serviceId(), domain.hostname(), domain.kind(),
                domain.tlsMode(), domain.verificationState(), domain.verificationToken(),
                domain.verifiedAt(), domain.lastCheckedAt(), domain.lastCheckError(),
                domain.redirectToHostname(), domain.forceHttps(), domain.targetPort(),
                domain.createdAt(),
                certificate == null ? null : CertificateView.of(certificate), serving,
                VerificationToken.recordName(domain.hostname()),
                VerificationToken.recordValue(domain.verificationToken()));
    }

    public boolean isPrimary() {
        return kind == DomainKind.PRIMARY;
    }

    public boolean isVerified() {
        return verificationState == DomainVerification.VERIFIED;
    }

    /**
     * Whether the page should be telling the customer to do something. Serialised as the
     * derived prop {@code atRisk}.
     *
     * <p>An unverified hostname, or a certificate that is failing. Computed here rather than
     * in the component, so the badge and the sort order cannot disagree about what counts as
     * a problem. Expiry is deliberately not part of it: {@code notAfter} is a prop and a
     * certificate that renews normally a fortnight before it lapses is not a problem.
     */
    public boolean isAtRisk() {
        if (!isVerified()) {
            return true;
        }
        return certificate != null
                && (certificate.state() == CertificateState.FAILED
                        || certificate.renewalFailureCount() > 0);
    }
}
