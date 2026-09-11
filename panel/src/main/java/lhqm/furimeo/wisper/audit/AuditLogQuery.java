package lhqm.furimeo.wisper.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * What to show from the trail.
 *
 * <p>Every field is optional and a null means "do not narrow by this". The combination is
 * what makes the four indexes on {@code audit_log} worth having: the platform-wide list,
 * one tenant's list, one object's history and one node's timeline are the same statement
 * with different predicates, and writing them as four queries would be four chances to
 * forget the ordering.
 *
 * @param organizationId one tenant's trail, or null for the whole platform
 * @param accountId      what one person did, when investigating an account
 * @param nodeId         one machine's timeline
 * @param action         an exact action from {@link AuditAction#ALL}; a value that is not
 *                       one of those is dropped by {@link #sanitised()} rather than
 *                       returning nothing and looking like an empty log
 * @param targetKind     narrow to one kind of object, such as {@code service}
 * @param targetId       narrow to one object; only meaningful with {@code targetKind}
 * @param outcome        succeeded, failed or denied
 * @param from           inclusive lower bound on {@code occurred_at}
 * @param to             exclusive upper bound, so consecutive pages of a day do not
 *                       overlap on the boundary
 * @param limit          rows per page, clamped by {@link #sanitised()}
 * @param offset         rows to skip
 */
public record AuditLogQuery(
        UUID organizationId,
        UUID accountId,
        UUID nodeId,
        String action,
        String targetKind,
        UUID targetId,
        AuditOutcome outcome,
        Instant from,
        Instant to,
        int limit,
        int offset) {

    /** Rows per page when nothing asked for a size. */
    public static final int DEFAULT_LIMIT = 50;

    /**
     * Hard ceiling. The screen is a list a person reads; anything larger is an export,
     * and an export that is really "limit=1000000" is a way to make the panel run out of
     * memory from a query string.
     */
    public static final int MAX_LIMIT = 200;

    /** Everything, newest first, one page. */
    public static AuditLogQuery everything() {
        return new AuditLogQuery(null, null, null, null, null, null, null, null, null,
                DEFAULT_LIMIT, 0);
    }

    /** One tenant's own trail. */
    public static AuditLogQuery forOrganization(UUID organizationId) {
        return everything().withOrganization(organizationId);
    }

    /** The history of one object, for the page that object is shown on. */
    public static AuditLogQuery forTarget(String targetKind, UUID targetId) {
        return new AuditLogQuery(null, null, null, null, targetKind, targetId, null, null, null,
                DEFAULT_LIMIT, 0);
    }

    /** The same query, pinned to one tenant. */
    public AuditLogQuery withOrganization(UUID tenant) {
        return new AuditLogQuery(tenant, accountId, nodeId, action, targetKind, targetId, outcome,
                from, to, limit, offset);
    }

    /** The same query, one page further on. */
    public AuditLogQuery nextPage() {
        return new AuditLogQuery(organizationId, accountId, nodeId, action, targetKind, targetId,
                outcome, from, to, limit, offset + limit);
    }

    /**
     * The query with everything a query string could get wrong corrected.
     *
     * <p>Blanks become nulls, an unknown action becomes null - a filter for something the
     * panel never writes should show the unfiltered log, not an empty one that reads as
     * "nothing has ever happened" - and the page size and offset are clamped.
     */
    public AuditLogQuery sanitised() {
        return new AuditLogQuery(
                organizationId,
                accountId,
                nodeId,
                AuditAction.isKnown(action) ? action : null,
                blankToNull(targetKind),
                targetId,
                outcome,
                from,
                to,
                limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT),
                Math.max(offset, 0));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
