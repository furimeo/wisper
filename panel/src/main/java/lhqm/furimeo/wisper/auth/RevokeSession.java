package lhqm.furimeo.wisper.auth;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Ends one browser session.
 *
 * <p>Used by the sign-out button (through {@link SessionSignOutHandler}) and by the
 * "sessions" list in {@code /settings/security}, where the customer ends a session on a
 * device they no longer have.
 *
 * <p>Revocation is a row update, not a container call: the browser being signed out is
 * usually not the one making the request, so its {@code HttpSession} is unreachable from
 * here. {@link SessionGateFilter} is what turns the revoked row into an actual sign-out,
 * on that browser's next request.
 *
 * <p>A session that belongs to somebody else answers {@link NotFoundException}, the same
 * as one that does not exist - telling an attacker that a session id is real is exactly
 * the enumeration a 404 avoids (docs/contracts/panel-http.md).
 */
@Component
public class RevokeSession {

    private final SessionRepository sessions;
    private final AuditTrail auditTrail;

    public RevokeSession(SessionRepository sessions, AuditTrail auditTrail) {
        this.sessions = sessions;
        this.auditTrail = auditTrail;
    }

    /**
     * @param ownerAccountId the account the caller is allowed to act for
     * @throws NotFoundException if the session does not exist or is not that account's
     */
    @Transactional
    public void run(UUID sessionId, UUID ownerAccountId, SessionRevocationReason reason,
                    AuditActor actor) {

        Session session = sessions.findById(sessionId)
                .filter(row -> row.accountId().equals(ownerAccountId))
                .orElseThrow(() -> NotFoundException.of("session", sessionId));

        if (session.revokedAt() == null) {
            sessions.save(session.revoked(Instant.now(), reason));
        }
        // "account.sign_out" and not "session.revoke": the vocabulary in
        // docs/contracts/panel-ports.md §2.4 is closed, and AuditAction.ALL is what fills
        // the filter on /admin/audit - an action outside the list cannot be filtered for.
        // Which session it was is the target; why it ended is the detail.
        auditTrail.record(AuditEntry.succeeded(actor, "account.sign_out",
                AuditTarget.of("session", session.id(),
                        session.remoteAddress() == null ? "unknown address"
                                : session.remoteAddress()),
                null, "Session ended: " + reason.name().toLowerCase().replace('_', ' ') + "."));
    }
}
