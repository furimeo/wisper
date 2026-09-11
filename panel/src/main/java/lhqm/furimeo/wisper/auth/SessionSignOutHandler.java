package lhqm.furimeo.wisper.auth;

import java.time.Instant;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import lhqm.furimeo.wisper.audit.AuditActor;

/**
 * Revokes the {@code session} row when somebody presses sign out.
 *
 * <p>Invalidating the container's {@code HttpSession} is what Spring Security already
 * does; it is not enough on its own, because the row is what the customer's "where am I
 * signed in" list reads and what {@link SessionGateFilter} checks. Leaving it live would
 * show a session that no longer exists, which is worse than showing none.
 *
 * <p>Registered through {@code logout().addLogoutHandler(...)}, and handlers added that
 * way run before {@code SecurityContextLogoutHandler} - so the {@code HttpSession} is
 * still readable here and its id can still be hashed.
 */
@Component
public class SessionSignOutHandler implements LogoutHandler {

    private final SessionRepository sessions;
    private final RevokeSession revokeSession;

    public SessionSignOutHandler(SessionRepository sessions, RevokeSession revokeSession) {
        this.sessions = sessions;
        this.revokeSession = revokeSession;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response,
                       Authentication authentication) {
        HttpSession httpSession = request.getSession(false);
        if (httpSession == null) {
            return;
        }
        sessions.findBySessionIdHash(TokenDigest.of(httpSession.getId()))
                .filter(row -> row.revokedAt() == null)
                .ifPresent(row -> revoke(row, authentication, request));
    }

    /**
     * With a principal the revocation goes through the use-case, so it is audited with
     * the person who did it. Without one - a sign-out from a context that has already
     * been cleared - the row is still closed, because a session left live because nobody
     * could be named is a session still working.
     */
    private void revoke(Session row, Authentication authentication, HttpServletRequest request) {
        if (authentication != null && authentication.getPrincipal() instanceof SignedInAccount who) {
            revokeSession.run(row.id(), who.id(), SessionRevocationReason.SIGNED_OUT,
                    AuditActor.account(who.id(), who.email(), request));
            return;
        }
        sessions.save(row.revoked(Instant.now(), SessionRevocationReason.SIGNED_OUT));
    }
}
