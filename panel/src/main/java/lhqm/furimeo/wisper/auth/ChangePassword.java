package lhqm.furimeo.wisper.auth;

import java.time.Instant;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Replaces an account's password, and ends every other browser that was signed in with
 * the old one.
 *
 * <p>The revocation is the point, not a nicety. The usual reason somebody changes a
 * password is that they think it is known, and leaving the sessions it opened alive means
 * the change accomplishes nothing until each of those sessions happens to expire. The
 * browser making the request is kept, because signing somebody out of the page they are
 * currently on reads as a failure.
 *
 * <p>The current password is verified even though the caller is already signed in. That
 * is what makes an unattended, unlocked laptop not enough to take an account over.
 */
@Component
public class ChangePassword {

    private final AccountRepository accounts;
    private final SessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final AuditTrail auditTrail;

    public ChangePassword(AccountRepository accounts, SessionRepository sessions,
                          PasswordEncoder passwordEncoder, AuditTrail auditTrail) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.auditTrail = auditTrail;
    }

    /**
     * @param keepSessionId the session row of the browser making the change, which stays
     *                      live; null ends every session including this one
     * @return how many other sessions were ended, so the page can say so
     * @throws CredentialRejected if the current password is wrong or the new one fails
     *                            {@link PasswordPolicy}
     */
    @Transactional
    public int run(UUID accountId, String currentPassword, String newPassword,
                   UUID keepSessionId, AuditActor actor) {

        Account account = accounts.findById(accountId)
                .orElseThrow(() -> NotFoundException.of("account", accountId));

        if (!passwordEncoder.matches(currentPassword == null ? "" : currentPassword,
                account.passwordHash())) {
            auditTrail.record(AuditEntry.denied(actor, "account.password_change",
                    AuditTarget.of("account", account.id(), account.email()), null,
                    "The current password was wrong."));
            throw CredentialRejected.of("currentPassword", "That is not your current password.");
        }
        PasswordPolicy.rejectionReason(newPassword, account.email()).ifPresent(reason -> {
            throw CredentialRejected.of("newPassword", reason);
        });
        if (passwordEncoder.matches(newPassword, account.passwordHash())) {
            throw CredentialRejected.of("newPassword",
                    "That is the password you are already using.");
        }

        Instant now = Instant.now();
        accounts.save(account.withPassword(passwordEncoder.encode(newPassword), now));

        int ended = keepSessionId == null
                ? sessions.revokeAllFor(accountId, now,
                        SessionRevocationReason.PASSWORD_CHANGED.name())
                : sessions.revokeAllForExcept(accountId, keepSessionId, now,
                        SessionRevocationReason.PASSWORD_CHANGED.name());

        auditTrail.record(AuditEntry.succeeded(actor, "account.password_change",
                AuditTarget.of("account", account.id(), account.email()), null,
                "Password changed; " + ended + " other session(s) ended."));
        return ended;
    }
}
