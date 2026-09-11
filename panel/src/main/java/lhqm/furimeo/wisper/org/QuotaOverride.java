package lhqm.furimeo.wisper.org;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * A per-organization exception to its plan's limit.
 *
 * <p>Kept apart from {@link Quota} so that the plan stays a description of a tier and the
 * override stays a decision somebody made about one customer, with a reason and an author
 * attached to it. An override with no explanation is one nobody later dares remove, which
 * is why {@code reason} is required by the database as well as here.
 *
 * <p>Expiry needs no sweep: a row whose {@code expiresAt} has passed simply stops winning
 * the resolution in {@link EnforceQuota}, and the plan's limit applies again. Deleting it
 * afterwards is tidying, not enforcement.
 *
 * @param id                  generated in Java before the insert
 * @param organizationId      whose exception this is
 * @param resource            which limit is being replaced
 * @param limitValue          the ceiling that applies instead of the plan's; may be lower
 *                            as well as higher, which is how one noisy tenant is reined in
 * @param reason              why this customer is different; required and non-blank
 * @param expiresAt           null stands until somebody deletes it
 * @param grantedByAccountId  who decided; null once that account is deleted
 * @param version             null means new
 */
public record QuotaOverride(
        @Id UUID id,
        UUID organizationId,
        QuotaResource resource,
        long limitValue,
        String reason,
        Instant expiresAt,
        UUID grantedByAccountId,
        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    public QuotaOverride {
        if (limitValue < 0) {
            throw new IllegalArgumentException("A quota limit is never negative");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("An override nobody can explain is one nobody "
                    + "dares remove: give a reason");
        }
    }

    /** A fresh override, ready to be inserted. */
    public static QuotaOverride granted(UUID organizationId, QuotaResource resource,
                                        long limitValue, String reason, Instant expiresAt,
                                        UUID grantedByAccountId) {
        return new QuotaOverride(UUID.randomUUID(), organizationId, resource, limitValue,
                reason, expiresAt, grantedByAccountId, null, null, null);
    }

    /** Whether this override still wins over the plan at the given moment. */
    public boolean isLiveAt(Instant when) {
        return expiresAt == null || expiresAt.isAfter(when);
    }

    /** The same exception with new terms, keeping its identity and its author. */
    public QuotaOverride revisedTo(long newLimit, String newReason, Instant newExpiry,
                                   UUID newAuthor) {
        return new QuotaOverride(id, organizationId, resource, newLimit, newReason, newExpiry,
                newAuthor, createdAt, updatedAt, version);
    }
}
