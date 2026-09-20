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
 * Sets an account's password directly by an operator or commercial API.
 *
 * <p>Unlike {@link ChangePassword}, this does not require the current password,
 * making it suitable for billing dashboard self-service password synchronization
 * and administrative password resets.
 */
@Component
public class AdminSetPassword {

    private final AccountRepository accounts;
    private final SessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final AuditTrail auditTrail;

    public AdminSetPassword(AccountRepository accounts, SessionRepository sessions,
                            PasswordEncoder passwordEncoder, AuditTrail auditTrail) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.auditTrail = auditTrail;
    }

    /**
     * @param accountId target account ID
     * @param newPassword new plaintext password to set
     * @param actor who performed the reset (administrator or API token)
     * @return updated Account
     * @throws CredentialRejected if the password does not satisfy {@link PasswordPolicy}
     * @throws NotFoundException if account does not exist
     */
    @Transactional
    public Account run(UUID accountId, String newPassword, AuditActor actor) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> NotFoundException.of("account", accountId));

        PasswordPolicy.rejectionReason(newPassword, account.email()).ifPresent(reason -> {
            throw CredentialRejected.of("newPassword", reason);
        });

        Instant now = Instant.now();
        Account updated = accounts.save(account.withPassword(passwordEncoder.encode(newPassword), now));

        int ended = sessions.revokeAllFor(accountId, now, SessionRevocationReason.PASSWORD_CHANGED.name());

        auditTrail.record(AuditEntry.succeeded(actor, "account.password_reset",
                AuditTarget.of("account", account.id(), account.email()), null,
                "Password reset by operator/API; " + ended + " session(s) revoked."));

        return updated;
    }

    /**
     * Reset password by account email.
     */
    @Transactional
    public Account runByEmail(String email, String newPassword, AuditActor actor) {
        String address = Account.normaliseEmail(email);
        Account account = accounts.findByEmail(address)
                .orElseThrow(() -> NotFoundException.of("account", address));
        return run(account.id(), newPassword, actor);
    }
}
