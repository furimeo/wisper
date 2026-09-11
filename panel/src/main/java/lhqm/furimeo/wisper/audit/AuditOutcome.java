package lhqm.furimeo.wisper.audit;

/**
 * How the action ended. Mirrors the {@code audit_log.outcome} CHECK.
 *
 * <p>{@link #DENIED} is the reason this is an enum and not a boolean. "Somebody tried to
 * delete a node they do not own and was refused" is the entry an incident is
 * reconstructed from, and a log that records only what succeeded cannot produce it.
 */
public enum AuditOutcome {

    /** It happened. */
    SUCCEEDED,

    /** It was allowed and went wrong: the node refused, a constraint fired, a timeout. */
    FAILED,

    /** It was not allowed: authorization, quota, a suspended organization, 2FA. */
    DENIED
}
