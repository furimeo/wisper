package lhqm.furimeo.wisper.audit;

import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts one audit row in a transaction of its own.
 *
 * <p>{@link Propagation#REQUIRES_NEW} is the whole reason this is a separate class from
 * {@link AuditLogRecorder}. Two properties fall out of it and both are wanted:
 *
 * <ol>
 * <li><strong>The entry survives the action being rolled back.</strong> "Somebody tried to
 *     delete this and the delete failed" is exactly the line an incident is reconstructed
 *     from, and joining the caller's transaction would take it away at the moment it
 *     became interesting.</li>
 * <li><strong>A failed insert can be caught outside the commit.</strong> A {@code catch}
 *     inside a {@code @Transactional} method does not catch a failure that happens when
 *     the transaction commits, because the commit occurs after the method returns. The
 *     recorder therefore wraps this call rather than this method wrapping itself, which
 *     is the only arrangement where "an audit write never breaks the action" is actually
 *     true (panel-ports.md §2.4).</li>
 * </ol>
 *
 * <p>Suspending the caller's transaction costs one extra connection for the length of one
 * insert. The pool is sized for it, and the alternative is an audit trail with holes in it
 * exactly where the interesting events are.
 */
@Component
public class AppendAuditLog {

    private final AuditLogRepository entries;

    public AppendAuditLog(AuditLogRepository entries) {
        this.entries = entries;
    }

    /**
     * Writes the entry and returns the row.
     *
     * <p>Throws whatever the database throws. {@link AuditLogRecorder} is what turns that
     * into a log line; nothing else may call this.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog write(AuditEntry entry, Instant occurredAt) {
        return entries.save(AuditLog.of(entry, occurredAt));
    }
}
