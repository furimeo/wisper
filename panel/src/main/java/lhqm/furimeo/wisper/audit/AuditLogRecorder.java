package lhqm.furimeo.wisper.audit;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The one implementation of {@link AuditTrail}.
 *
 * <p>Two lines of real work and one deliberate swallow. The insert happens in
 * {@link AppendAuditLog}, in its own transaction; this class exists to make sure that a
 * failure to write it never reaches the caller.
 *
 * <p>That swallow is the only tolerated write failure in the codebase and it is a
 * considered trade. Every state-changing action in the panel calls this, so propagating a
 * failure would mean a panel that refuses to stop a customer's container because it could
 * not append a log line - an audit trail that has become an outage. The failure is logged
 * at {@code ERROR} with the action and the actor, so a trail that is silently not being
 * written shows up in the operator's logs rather than in a post-incident review.
 *
 * <p>{@link Clock} is injected so a test can assert the recorded instant instead of
 * asserting "recently". There is one {@code Clock} bean in the panel and it is the system
 * one; this is not a seam for anything else.
 */
@Component
public class AuditLogRecorder implements AuditTrail {

    private static final Logger log = LoggerFactory.getLogger(AuditLogRecorder.class);

    private final AppendAuditLog append;
    private final Clock clock;

    @Autowired
    public AuditLogRecorder(AppendAuditLog append) {
        this(append, Clock.systemUTC());
    }

    AuditLogRecorder(AppendAuditLog append, Clock clock) {
        this.append = append;
        this.clock = clock;
    }

    @Override
    public void record(AuditEntry entry) {
        if (entry == null) {
            // A caller that built no entry has a bug, and throwing here would turn that
            // bug into a failed customer action on the success path. Name it and move on.
            log.error("An audit entry was null. Something is calling AuditTrail.record(null).");
            return;
        }
        Instant occurredAt = Instant.now(clock);
        try {
            append.write(entry, occurredAt);
        } catch (RuntimeException unwritten) {
            log.error("Audit entry lost: {} {} on {} by {} ({})", entry.outcome(), entry.action(),
                    entry.target().kind(), entry.actor().label(), entry.actor().kind(), unwritten);
        }
    }
}
