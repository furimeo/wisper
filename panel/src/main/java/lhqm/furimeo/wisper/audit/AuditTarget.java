package lhqm.furimeo.wisper.audit;

import java.util.UUID;

/**
 * What an action was done to.
 *
 * <p>{@code kind} is a stable lower-case word matching the table the target lives in -
 * {@code service}, {@code node}, {@code domain}, {@code managed_database},
 * {@code restore_point}, {@code account}, {@code api_token}. It is filtered on, so it is
 * a fixed vocabulary and not a sentence.
 *
 * <p>{@code label} carries what the thing was called at the time. The id is a
 * {@code SET NULL} away from being gone and, more to the point, "somebody deleted
 * service 9f3a..." is not an answer anybody can use. The label is how the entry stays
 * readable after the row it points at has been deleted - which is precisely the entry
 * people come looking for.
 *
 * @param kind  the target's table name, lower case
 * @param id    the target's primary key, or null for something with no row (a sign-in
 *              attempt against an address that does not exist, a rejected enrolment)
 * @param label the target's name as it was when this happened; may be empty only when
 *              there is genuinely nothing to name
 */
public record AuditTarget(String kind, UUID id, String label) {

    public AuditTarget {
        if (kind == null || !kind.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "An audit target kind is a lower-case table name, not \"" + kind + "\"");
        }
        label = label == null ? "" : label;
    }

    /** The common case: a row, and what it was called. */
    public static AuditTarget of(String kind, UUID id, String label) {
        return new AuditTarget(kind, id, label);
    }

    /** Something with no row: a sign-in against an unknown address, a refused token. */
    public static AuditTarget unidentified(String kind, String label) {
        return new AuditTarget(kind, null, label);
    }
}
