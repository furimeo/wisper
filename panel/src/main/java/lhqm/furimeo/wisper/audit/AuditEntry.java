package lhqm.furimeo.wisper.audit;

import java.util.UUID;

/**
 * One line of the audit trail, ready to be written.
 *
 * <p>Assembled by the caller because only the caller knows all six parts, and validated
 * here because the alternative is finding out from a CHECK constraint. Every
 * state-changing action in the panel produces one of these (AGENTS.md §5).
 *
 * @param actor          who, and from where
 * @param action         a dotted, stable identifier - {@code service.start},
 *                       {@code node.enrol}, {@code backup.restore} - never prose. It is
 *                       filtered on, and prose cannot be filtered on.
 * @param target         what it was done to
 * @param outcome        succeeded, failed, or refused
 * @param organizationId the tenant this belongs to, so a customer can be shown their own
 *                       trail; null for a platform-level action such as enrolling a node
 * @param detail         one short sentence a person can read. <strong>Never a secret
 *                       value</strong>: not a password, not a token, not the contents of
 *                       an encrypted column. Naming the field that changed is the point;
 *                       showing what it changed to is a second copy of the secret.
 */
public record AuditEntry(
        AuditActor actor,
        String action,
        AuditTarget target,
        AuditOutcome outcome,
        UUID organizationId,
        String detail) {

    /** The same shape the {@code audit_log_action_shape} CHECK enforces. */
    private static final String ACTION_SHAPE = "[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+";

    public AuditEntry {
        if (actor == null) {
            throw new IllegalArgumentException("An audit entry needs an actor");
        }
        if (target == null) {
            throw new IllegalArgumentException("An audit entry needs a target");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("An audit entry needs an outcome");
        }
        if (action == null || !action.matches(ACTION_SHAPE)) {
            throw new IllegalArgumentException("An audit action is dotted and stable, like "
                    + "\"service.start\", not \"" + action + "\"");
        }
        detail = detail == null ? "" : detail;
    }

    /** It happened. */
    public static AuditEntry succeeded(AuditActor actor, String action, AuditTarget target,
                                       UUID organizationId, String detail) {
        return new AuditEntry(actor, action, target, AuditOutcome.SUCCEEDED, organizationId, detail);
    }

    /** It was allowed and went wrong. */
    public static AuditEntry failed(AuditActor actor, String action, AuditTarget target,
                                    UUID organizationId, String detail) {
        return new AuditEntry(actor, action, target, AuditOutcome.FAILED, organizationId, detail);
    }

    /** It was refused: authorization, quota, suspension, a missing second factor. */
    public static AuditEntry denied(AuditActor actor, String action, AuditTarget target,
                                    UUID organizationId, String detail) {
        return new AuditEntry(actor, action, target, AuditOutcome.DENIED, organizationId, detail);
    }
}
