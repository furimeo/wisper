package lhqm.furimeo.wisper.audit;

/**
 * Who did it. Mirrors the {@code audit_log.actor_kind} CHECK exactly.
 *
 * <p>Four kinds rather than a nullable account id, because the columns that may be set
 * differ per kind and the database enforces that: {@code audit_log_actor_consistent}
 * refuses a row whose actor columns do not match its kind. Keeping the kind explicit in
 * Java means the violation is caught while building the entry rather than as a
 * constraint error with no context.
 */
public enum AuditActorKind {

    /** A signed-in person, acting through the panel. */
    ACCOUNT,

    /** A program holding a scoped bearer token against {@code /api/v1/**}. */
    API_TOKEN,

    /** A node, reporting something over gRPC that changed state on the panel. */
    NODE,

    /** The panel itself: a scheduled job, a sweep, a retention pass. */
    SYSTEM
}
