package lhqm.furimeo.wisper.node;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;

/**
 * The single-use bootstrap token an operator hands to {@code install.sh} (design §7.1).
 *
 * <p>A row rather than a column on {@code node}, because the history is what an operator
 * needs: a token that expired, a token used from an address nobody recognises and a token
 * reissued after a failed install are three different situations and one column cannot
 * tell them apart.
 *
 * <p>Only the hash is stored. The panel shows the token exactly once, at creation, and can
 * never show it again - which is the property that makes "this token was used" a fact and
 * not a guess.
 *
 * <p>The class name keeps the table's American spelling so Spring Data JDBC's
 * class-to-{@code snake_case} default lands on {@code node_enrollment_token} with no
 * {@code @Table} annotation. Prose in this package says "enrolment"; the identifier
 * follows the schema.
 *
 * @param nodeId            the one node record this token may enrol, and no other
 * @param tokenHash         SHA-256 of the token text
 * @param issuedByAccountId who created it; {@code SET NULL} if that account is deleted
 * @param expiresAt         {@code createdAt + wisper.node.enrollment-token-ttl}
 * @param usedAt            set in the same transaction that writes
 *                          {@code node.credential_hash}, so a replay finds it spent
 * @param usedFromAddress   where it was spent from, for the operator who has to work out
 *                          whether that was them
 * @param revokedAt         withdrawn before use. A row cannot be both used and revoked.
 */
public record NodeEnrollmentToken(
        @Id UUID id,
        UUID nodeId,
        String tokenHash,
        UUID issuedByAccountId,
        Instant expiresAt,
        Instant usedAt,
        String usedFromAddress,
        Instant revokedAt,
        @CreatedDate Instant createdAt,
        @Version Long version) {

    /** A freshly minted token, unused and unrevoked. */
    public static NodeEnrollmentToken issued(UUID id, UUID nodeId, String tokenHash,
                                             UUID issuedByAccountId, Instant expiresAt) {
        return new NodeEnrollmentToken(id, nodeId, tokenHash, issuedByAccountId, expiresAt,
                null, null, null, null, null);
    }

    /** Neither spent nor withdrawn nor past its expiry at this instant. */
    public boolean isUsable(Instant at) {
        return usedAt == null && revokedAt == null && at.isBefore(expiresAt);
    }

    public boolean isSpent() {
        return usedAt != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean hasExpired(Instant at) {
        return !at.isBefore(expiresAt);
    }

    /**
     * Marks it spent.
     *
     * <p>Written in the same transaction as {@code node.credential_hash}. If the enrolment
     * rolls back, so does this, and the operator's token still works - which is the
     * behaviour that stops a transient database error from costing somebody a reinstall.
     */
    public NodeEnrollmentToken spent(Instant at, String fromAddress) {
        return new NodeEnrollmentToken(id, nodeId, tokenHash, issuedByAccountId, expiresAt,
                at, fromAddress, revokedAt, createdAt, version);
    }

    /** Withdrawn before anybody used it. */
    public NodeEnrollmentToken revoked(Instant at) {
        return new NodeEnrollmentToken(id, nodeId, tokenHash, issuedByAccountId, expiresAt,
                usedAt, usedFromAddress, at, createdAt, version);
    }

    @Override
    public String toString() {
        // Not the hash. It is not the token, but it is the value a replay check compares
        // against, and there is no reason for it to be in a log line.
        return "NodeEnrollmentToken[" + id + " node=" + nodeId + " expires=" + expiresAt + "]";
    }
}
