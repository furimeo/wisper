package lhqm.furimeo.wisper.auth;

import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Marks a session as having answered its TOTP challenge.
 *
 * <p>One column on one row, and it is what turns a session that may only reach
 * {@code /login/**} into one that may reach the panel. It is stored on the row rather
 * than in the {@code HttpSession} so that "sign out everywhere" can take a half-finished
 * challenge with it, and so an operator looking at the session list can tell a stalled
 * sign-in from a completed one.
 */
@Component
public class CompleteSecondFactor {

    private final SessionRepository sessions;

    public CompleteSecondFactor(SessionRepository sessions) {
        this.sessions = sessions;
    }

    /**
     * @param containerSessionId the servlet container's session id for the browser that
     *                           just answered the challenge
     * @throws NotFoundException if there is no live row for that browser, which means the
     *                           session was revoked or expired while the challenge was on
     *                           screen. Answering it must not resurrect it
     */
    @Transactional
    public Session run(String containerSessionId) {
        String hash = TokenDigest.of(containerSessionId);
        Session session = sessions.findBySessionIdHash(hash)
                .filter(row -> row.isLiveAt(Instant.now()))
                .orElseThrow(() -> new NotFoundException(
                        "No live session for this browser; sign in again"));
        if (session.isSecondFactorSatisfied()) {
            return session;
        }
        return sessions.save(session.withSecondFactorAt(Instant.now()));
    }
}
