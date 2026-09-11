package lhqm.furimeo.wisper.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * What the panel knows about one TLS certificate. The node owns the certificate itself.
 *
 * <p>Every field is node-reported fact (schema.md §2): the only writer is
 * {@link RecordCertificateStatus}, called from the {@code grpc} adapter with a
 * {@code RouteStatus}. No controller and no customer-facing use-case may touch a row.
 *
 * <p><strong>There is no private key column and there will not be one.</strong> Caddy runs
 * inside sasayaki, obtains the certificate over ACME and keeps the key on the node's disk
 * (design §11.3). The panel keeps this record so it can show an expiry date, warn before a
 * renewal that is quietly failing runs out, and answer "why is this hostname serving a
 * warning" without asking a machine that may be offline.
 *
 * @param nodeId    which machine holds the key. {@code SET NULL} on the node's deletion,
 *                  so the history outlives the hardware.
 * @param issuer    whatever the node's ACME client used - {@code letsencrypt},
 *                  {@code zerossl}, {@code internal}. Null until a report carries it;
 *                  {@code RouteStatus} does not, so in v1 it stays null and the screen
 *                  shows nothing rather than a guess.
 * @param renewalFailureCount consecutive failed attempts. Reset the moment one succeeds,
 *                  so a large number always means "failing right now" and never "failed
 *                  a lot last year".
 */
public record Certificate(
        @Id UUID id,
        UUID domainId,
        UUID nodeId,

        CertificateState state,
        String issuer,
        String serialNumber,
        String subjectCommonName,
        String[] subjectAlternativeNames,
        String fingerprintSha256,
        Instant notBefore,
        Instant notAfter,

        Instant obtainedAt,
        Instant lastRenewalAttemptAt,
        int renewalFailureCount,
        String lastError,

        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    private static final String[] NO_NAMES = new String[0];

    /** The first record of a hostname the node has begun working on. */
    public static Certificate pendingFor(UUID id, UUID domainId, UUID nodeId) {
        return new Certificate(id, domainId, nodeId, CertificateState.PENDING, null, null, null,
                NO_NAMES, null, null, null, null, null, 0, null, null, null, null);
    }

    /** The alternative names as a list, because callers render them and do not index. */
    public List<String> alternativeNames() {
        return subjectAlternativeNames == null ? List.of() : List.of(subjectAlternativeNames);
    }

    public boolean isLive() {
        return state.isLive();
    }

    /**
     * The certificate the node says it now holds.
     *
     * <p>{@code certificate_issued_has_validity} requires both bounds on an issued row, so
     * the caller has to supply a {@code notBefore}; {@link RecordCertificateStatus} keeps
     * the one already on the row and falls back to the observation time, because a
     * certificate the node is serving is certainly valid at the moment it said so.
     *
     * <p>Clears the error and resets the failure count: those describe attempts, and an
     * attempt that succeeded ends the run.
     */
    public Certificate issued(UUID reportingNodeId, String commonName, Instant validFrom,
                              Instant validUntil, Instant at) {
        boolean firstIssue = !isLive();
        return new Certificate(id, domainId, reportingNodeId, CertificateState.ISSUED, issuer,
                serialNumber, commonName, new String[] {commonName}, fingerprintSha256,
                validFrom, validUntil,
                firstIssue || obtainedAt == null ? at : obtainedAt,
                lastRenewalAttemptAt, 0, null, createdAt, updatedAt, version);
    }

    /**
     * A renewal is under way and the current certificate is still being served.
     *
     * <p>Only legal from {@link CertificateState#ISSUED} or {@link CertificateState#RENEWING},
     * because the validity CHECK applies to both and only a row that already has bounds can
     * satisfy it.
     */
    public Certificate renewing(UUID reportingNodeId, Instant at) {
        return new Certificate(id, domainId, reportingNodeId, CertificateState.RENEWING, issuer,
                serialNumber, subjectCommonName, subjectAlternativeNames, fingerprintSha256,
                notBefore, notAfter, obtainedAt, at, renewalFailureCount, lastError,
                createdAt, updatedAt, version);
    }

    /** The node has started working on a hostname that has no certificate yet. */
    public Certificate issuing(UUID reportingNodeId, Instant at) {
        return new Certificate(id, domainId, reportingNodeId, CertificateState.PENDING, issuer,
                serialNumber, subjectCommonName, subjectAlternativeNames, fingerprintSha256,
                notBefore, notAfter, obtainedAt, at, renewalFailureCount, lastError,
                createdAt, updatedAt, version);
    }

    /**
     * An attempt failed.
     *
     * <p>A row that is live stays live. The visitor is still being served the certificate
     * the node already has, and moving the row to {@code FAILED} would lose the expiry that
     * the warning about this very failure has to be drawn from.
     */
    public Certificate attemptFailed(UUID reportingNodeId, String error, Instant at) {
        CertificateState next = isLive() ? CertificateState.RENEWING : CertificateState.FAILED;
        return new Certificate(id, domainId, reportingNodeId, next, issuer, serialNumber,
                subjectCommonName, subjectAlternativeNames, fingerprintSha256, notBefore, notAfter,
                obtainedAt, at, renewalFailureCount + 1,
                error == null || error.isBlank() ? "The node did not say why." : error,
                createdAt, updatedAt, version);
    }
}
