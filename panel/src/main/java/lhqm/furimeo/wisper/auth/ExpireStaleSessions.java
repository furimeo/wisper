package lhqm.furimeo.wisper.auth;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retires session rows that can no longer authorise anything.
 *
 * <p>Two passes, in this order and for two different reasons:
 *
 * <ol>
 * <li><strong>Revoke what has expired.</strong> {@link SessionGateFilter} already refuses
 *     a session past {@code expires_at}, so this is not what makes expiry safe - it is
 *     what makes the list in {@code /settings/security} agree with reality. A customer
 *     shown four live sessions when two of them stopped working last week has been given
 *     a security screen that lies.</li>
 * <li><strong>Delete what finished long enough ago.</strong> Not immediately: the row is
 *     the sign-in history, and "you signed in from this address on Tuesday" is the answer
 *     somebody needs after an incident. After
 *     {@code wisper.auth.finished-session-retention} it is only making the per-request
 *     lookup pay for dead rows.</li>
 * </ol>
 */
@Component
public class ExpireStaleSessions {

    private static final Logger log = LoggerFactory.getLogger(ExpireStaleSessions.class);

    private final SessionRepository sessions;
    private final AuthSettings settings;

    public ExpireStaleSessions(SessionRepository sessions, AuthSettings settings) {
        this.sessions = sessions;
        this.settings = settings;
    }

    /** @return how many rows were deleted, which is what the scheduler's log reports */
    @Transactional
    public int run() {
        Instant now = Instant.now();
        int revoked = sessions.revokeExpired(now);
        int deleted = sessions.deleteFinishedBefore(now.minus(settings.finishedSessionRetention()));
        if (revoked > 0 || deleted > 0) {
            log.info("Session sweep: {} expired, {} removed from the history", revoked, deleted);
        }
        return deleted;
    }
}
