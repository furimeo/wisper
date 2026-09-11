package lhqm.furimeo.wisper.org;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * One limit a plan grants: {@code (plan, resource) -> number}.
 *
 * <p>Rows rather than columns, because the set of metered resources grows and adding one
 * must not be an {@code ALTER TABLE} on a table six packages read.
 *
 * <p><strong>A resource with no row is limited to zero</strong>, not to unlimited. That
 * asymmetry is deliberate and it is enforced in {@link EnforceQuota}: reading a missing
 * row as "no limit" means the day somebody adds a plan and forgets one line, that plan is
 * unmetered and nobody finds out until a node fills up.
 *
 * @param id         generated in Java before the insert
 * @param planId     the plan this limit belongs to
 * @param resource   which limit
 * @param limitValue the ceiling, a long in the resource's smallest unit - a count, bytes
 *                   or millicores, never a double and never a percentage
 * @param version    null means new
 */
public record Quota(
        @Id UUID id,
        UUID planId,
        QuotaResource resource,
        long limitValue,
        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    public Quota {
        if (limitValue < 0) {
            throw new IllegalArgumentException("A quota limit is never negative");
        }
    }

    /** A limit ready to be inserted for a plan. */
    public static Quota of(UUID planId, QuotaResource resource, long limitValue) {
        return new Quota(UUID.randomUUID(), planId, resource, limitValue, null, null, null);
    }

    /** The same row with a different ceiling. */
    public Quota withLimit(long newLimit) {
        return new Quota(id, planId, resource, newLimit, createdAt, updatedAt, version);
    }
}
