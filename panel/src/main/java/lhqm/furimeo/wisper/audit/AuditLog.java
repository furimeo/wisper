package lhqm.furimeo.wisper.audit;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;

/**
 * One row of {@code audit_log}: what happened, who did it, and to what.
 *
 * <p>Append-only. There is no wither on this record and no update anywhere in the
 * package, because a trail that can be edited answers no question worth asking.
 *
 * <p>{@code occurredAt} is set in Java rather than left to the column's {@code DEFAULT
 * now()}: Spring Data JDBC writes every mapped column on insert, nulls included, so a
 * default would be overwritten with NULL (panel-configuration.md). It is deliberately not
 * a {@code @CreatedDate} either - the instant an action happened is the fact being
 * recorded, not framework bookkeeping, and {@link AppendAuditLog} is the one place that
 * decides it.
 *
 * <p>The actor columns are {@code ON DELETE SET NULL}, so a row outlives the account,
 * token or node that produced it. {@code actorLabel} and {@code targetLabel} are what
 * keep it readable afterwards, which is precisely the row somebody comes looking for.
 */
public record AuditLog(
        @Id UUID id,
        Instant occurredAt,

        UUID organizationId,

        AuditActorKind actorKind,
        UUID actorAccountId,
        UUID apiTokenId,
        UUID nodeId,
        String actorLabel,

        String action,
        String targetKind,
        UUID targetId,
        String targetLabel,
        AuditOutcome outcome,

        String remoteAddress,
        String userAgent,
        String requestId,
        String detail,

        @Version Long version) {

    /**
     * Longest {@code detail} kept.
     *
     * <p>An audit row is read by a person. A stack trace pasted into this column makes the
     * table expensive to scan and the screen impossible to read, and the useful part is
     * always the first sentence.
     */
    public static final int DETAIL_LIMIT = 2000;

    /** Longest label kept, for the same reason. */
    public static final int LABEL_LIMIT = 400;

    /**
     * Turns a validated {@link AuditEntry} into the row for it.
     *
     * <p>The id is generated here rather than by the database: an id that only exists
     * after a round trip cannot be logged alongside the action it describes
     * (schema.md §1).
     */
    public static AuditLog of(AuditEntry entry, Instant occurredAt) {
        AuditActor actor = entry.actor();
        return new AuditLog(
                UUID.randomUUID(),
                occurredAt,
                entry.organizationId(),
                actor.kind(),
                actor.accountId(),
                actor.apiTokenId(),
                actor.nodeId(),
                clip(actor.label(), LABEL_LIMIT),
                entry.action(),
                entry.target().kind(),
                entry.target().id(),
                clip(entry.target().label(), LABEL_LIMIT),
                entry.outcome(),
                clip(actor.remoteAddress(), LABEL_LIMIT),
                clip(actor.userAgent(), LABEL_LIMIT),
                clip(actor.requestId(), LABEL_LIMIT),
                clip(entry.detail(), DETAIL_LIMIT),
                null);
    }

    private static String clip(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit - 1) + "…";
    }
}
