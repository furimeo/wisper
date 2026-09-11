package lhqm.furimeo.wisper.auth;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Writes the {@code session} row for a browser that has just authenticated.
 *
 * <p>Called from {@link SignInSuccessHandler}, after Spring Security's session-fixation
 * protection has already replaced the container's session id - so the hash written here
 * is the one every subsequent request will present, and not the pre-authentication id
 * that is now dead.
 *
 * <p>{@code second_factor_at} is set now for an account with no TOTP, and left null for
 * one with it. That null is the entire second-factor gate: {@link SessionGateFilter}
 * refuses everything except {@code /login/**} and {@code /logout} while it is there.
 */
@Component
public class RecordSignIn {

    private final SessionRepository sessions;
    private final AuthSettings settings;

    public RecordSignIn(SessionRepository sessions, AuthSettings settings) {
        this.sessions = sessions;
        this.settings = settings;
    }

    /**
     * @param containerSessionId the servlet container's session id, hashed before storage
     * @param secondFactorNeeded whether a TOTP challenge is still outstanding
     * @return the row, whose id the caller keeps in the {@code HttpSession} so later
     *         requests can revoke or complete it without another lookup
     */
    @Transactional
    public Session run(UUID accountId, String containerSessionId, boolean secondFactorNeeded,
                       HttpServletRequest request) {

        String hash = TokenDigest.of(containerSessionId);
        // The unique index on session_id_hash makes a leftover row from an earlier sign-in
        // in this same container session an insert failure rather than an overwrite.
        // Containers do reuse ids after an invalidate, so this is reachable.
        sessions.findBySessionIdHash(hash).ifPresent(sessions::delete);

        Instant now = Instant.now();
        return sessions.save(Session.opened(accountId, hash,
                request.getRemoteAddr(), request.getHeader("User-Agent"),
                secondFactorNeeded ? null : now, now, now.plus(settings.sessionLifetime())));
    }
}
