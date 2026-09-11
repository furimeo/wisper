package lhqm.furimeo.wisper.auth;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;

/**
 * Ends every session an account has, optionally sparing the browser asking.
 *
 * <p>One UPDATE rather than a loop over the rows. A loop would leave a person signed out
 * of three browsers and still signed in to the fourth if it failed part way, which is the
 * worst possible outcome for the button whose entire purpose is "I do not know where I am
 * signed in".
 *
 * <p>Also called by {@link SuspendAccount}, where nothing is spared: a suspended person
 * keeps no session, including the one they are reading the panel in.
 */
@Component
public class SignOutEverywhere {

    private final SessionRepository sessions;
    private final AuditTrail auditTrail;

    public SignOutEverywhere(SessionRepository sessions, AuditTrail auditTrail) {
        this.sessions = sessions;
        this.auditTrail = auditTrail;
    }

    /**
     * @param keepSessionId the row to leave alone, or null to end every one
     * @return how many sessions were ended
     */
    @Transactional
    public int run(UUID accountId, String accountEmail, UUID keepSessionId,
                   SessionRevocationReason reason, AuditActor actor) {

        Instant now = Instant.now();
        int ended = keepSessionId == null
                ? sessions.revokeAllFor(accountId, now, reason.name())
                : sessions.revokeAllForExcept(accountId, keepSessionId, now, reason.name());

        // The same closed vocabulary RevokeSession writes: one entry against the account,
        // with the count and the reason in the detail, rather than a "session.revoke_all"
        // that docs/contracts/panel-ports.md §2.4 does not name and the audit filter
        // therefore cannot select.
        auditTrail.record(AuditEntry.succeeded(actor, "account.sign_out",
                AuditTarget.of("account", accountId, accountEmail), null,
                ended + " session(s) ended: "
                        + reason.name().toLowerCase().replace('_', ' ') + "."));
        return ended;
    }
}
