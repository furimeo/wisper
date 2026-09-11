package lhqm.furimeo.wisper.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the trail as a screen shows it.
 *
 * <p>Separate from {@link AuditLog} because the two answer different questions.
 * {@code AuditLog} is the shape of the table, including the three actor id columns whose
 * only job is to be a foreign key. This is what a person reads: who, what, to what, how
 * it ended - with the labels that still make sense after the account was deleted.
 *
 * @param actorLabel  the email, token name or node name as it was at the time. Kept even
 *                    when {@code actorAccountId} has been nulled by a deletion, which is
 *                    the case this record exists to render correctly
 * @param detail      one short sentence. Never a secret value: the writer names the field
 *                    that changed and not what it changed to (panel-ports.md §2.4)
 */
public record AuditLogEntry(
        UUID id,
        Instant occurredAt,
        UUID organizationId,
        AuditActorKind actorKind,
        UUID actorAccountId,
        UUID nodeId,
        String actorLabel,
        String action,
        String targetKind,
        UUID targetId,
        String targetLabel,
        AuditOutcome outcome,
        String remoteAddress,
        String requestId,
        String detail) {

    /** Whether this row is one of the refusals an incident is usually reconstructed from. */
    public boolean isRefusal() {
        return outcome == AuditOutcome.DENIED;
    }

    /** What the action acted on, named if it had a name and identified if it did not. */
    public String target() {
        if (targetLabel != null && !targetLabel.isBlank()) {
            return targetKind + " " + targetLabel;
        }
        return targetId == null ? targetKind : targetKind + " " + targetId;
    }
}
