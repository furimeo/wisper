package lhqm.furimeo.wisper.domain;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * A hostname pointed at a service.
 *
 * <p>Every column here is the panel's: {@code domain} is absent from the ownership split
 * in schema.md §2 because nothing on it is node-reported. What the node has to say about a
 * hostname lands in {@link Certificate}, which is entirely node-written. Keeping the two
 * apart is what stops a status report overwriting a customer's intent.
 *
 * <p>The state changes are methods returning a new record, because two of the CHECKs pair
 * columns that a caller could otherwise set half of. {@code domain_verified_consistent}
 * makes {@code verification_state = 'VERIFIED'} and a non-null {@code verified_at} the
 * same fact; {@code domain_wildcard_shape} makes {@code kind = 'WILDCARD'} and a
 * {@code *.} prefix the same fact. Neither can be half-applied through this record.
 *
 * @param hostname          lower-case IDNA A-label, no trailing dot, globally unique
 *                          ({@link Hostname})
 * @param verificationToken the value the customer publishes in a TXT record when they
 *                          cannot point A/AAAA at the node yet. Minted on creation, never
 *                          rotated: rotating it would silently invalidate a record the
 *                          customer has already published.
 * @param lastCheckedAt     when the panel last asked a resolver, whatever the answer
 * @param lastCheckError    why the last check did not verify, ready to be shown verbatim
 * @param redirectToHostname serve a permanent redirect instead of the service. An alias
 *                          that only redirects still needs a certificate, which is why it
 *                          is a domain row and not a flag on another one.
 * @param targetPort        overrides {@code service.container_port} for this hostname;
 *                          null means the service's own port
 * @param version           null means new (schema.md §1)
 */
public record Domain(
        @Id UUID id,
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

        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    /**
     * A hostname a customer has just added, before anybody has checked anything.
     *
     * @param kind {@link DomainKind#PRIMARY} for a service's first hostname,
     *             {@link DomainKind#ALIAS} for the rest. {@link DomainKind#WILDCARD} is
     *             refused here as well as in {@link Hostname}, because a row whose kind and
     *             shape disagree is rejected by the database and the failure would name a
     *             constraint rather than the mistake.
     */
    public static Domain of(UUID id, UUID serviceId, String hostname, DomainKind kind,
                            DomainTlsMode tlsMode, String verificationToken, boolean forceHttps,
                            Integer targetPort, String redirectToHostname) {
        if (kind == DomainKind.WILDCARD || Hostname.isWildcard(hostname)) {
            throw new IllegalArgumentException("Wildcard hostnames cannot be created in v1; "
                    + "Hostname.require refuses them before this point");
        }
        return new Domain(id, serviceId, hostname, kind, tlsMode,
                DomainVerification.PENDING, verificationToken, null, null, null,
                redirectToHostname, forceHttps, targetPort,
                null, null, null);
    }

    /** Whether the node will be asked to obtain a certificate for this hostname. */
    public boolean wantsCertificate() {
        return tlsMode.wantsCertificate();
    }

    public boolean isPrimary() {
        return kind == DomainKind.PRIMARY;
    }

    public boolean isVerified() {
        return verificationState == DomainVerification.VERIFIED;
    }

    /**
     * Proven: both halves of {@code domain_verified_consistent} at once.
     *
     * <p>{@code verified_at} is kept if it was already set, so re-proving a hostname does
     * not move the date on which it was first proven.
     */
    public Domain verified(Instant at) {
        return new Domain(id, serviceId, hostname, kind, tlsMode,
                DomainVerification.VERIFIED, verificationToken,
                verifiedAt == null ? at : verifiedAt, at, null,
                redirectToHostname, forceHttps, targetPort, createdAt, updatedAt, version);
    }

    /**
     * Checked, and the answer was not this node.
     *
     * <p>Clears {@code verified_at} along with the state, because leaving it set would
     * violate {@code domain_verified_consistent} and, worse, would make the screen say a
     * hostname was verified on a date while showing it as failing.
     */
    public Domain failedCheck(Instant at, String error) {
        return new Domain(id, serviceId, hostname, kind, tlsMode,
                DomainVerification.FAILED, verificationToken, null, at, error,
                redirectToHostname, forceHttps, targetPort, createdAt, updatedAt, version);
    }

    /**
     * Checked, and there was nothing to compare against - no node holds the service, or
     * the node has no public address recorded.
     *
     * <p>Deliberately not {@link #failedCheck}: neither situation is something the customer
     * did wrong or can fix by editing DNS, and telling them their record is wrong when it
     * is not is worse than telling them nothing.
     */
    public Domain awaitingCheck(Instant at, String reason) {
        return new Domain(id, serviceId, hostname, kind, tlsMode,
                DomainVerification.PENDING, verificationToken, null, at, reason,
                redirectToHostname, forceHttps, targetPort, createdAt, updatedAt, version);
    }

    /**
     * The same row as a different kind.
     *
     * @throws IllegalArgumentException for {@link DomainKind#WILDCARD}, which would break
     *                                  {@code domain_wildcard_shape} on a hostname with no
     *                                  {@code *.} prefix
     */
    public Domain as(DomainKind newKind) {
        if (newKind == DomainKind.WILDCARD) {
            throw new IllegalArgumentException("A stored hostname has no wildcard prefix, so it "
                    + "cannot become a WILDCARD row");
        }
        return new Domain(id, serviceId, hostname, newKind, tlsMode,
                verificationState, verificationToken, verifiedAt, lastCheckedAt, lastCheckError,
                redirectToHostname, forceHttps, targetPort, createdAt, updatedAt, version);
    }
}
