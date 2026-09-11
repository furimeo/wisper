package lhqm.furimeo.wisper.auth;

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
 * Turns the second factor off, and throws away the recovery codes with it.
 *
 * <p>Guarded by the current password. Removing a second factor is the single most
 * valuable thing an attacker sitting at somebody's unlocked laptop could do, and it is
 * the one action where "you are already signed in" is not evidence enough.
 *
 * <p>Deletes the recovery codes rather than leaving them: they exist to get past a
 * challenge that no longer happens, and a printed list of secrets that no longer do
 * anything is a list somebody will still keep in a drawer. Turning the second factor back
 * on issues a fresh batch.
 */
@Component
public class DisableTwoFactor {

    private final AccountRepository accounts;
    private final AccountRecoveryCodeRepository recoveryCodes;
    private final PasswordEncoder passwordEncoder;
    private final AuditTrail auditTrail;

    public DisableTwoFactor(AccountRepository accounts,
                            AccountRecoveryCodeRepository recoveryCodes,
                            PasswordEncoder passwordEncoder, AuditTrail auditTrail) {
        this.accounts = accounts;
        this.recoveryCodes = recoveryCodes;
        this.passwordEncoder = passwordEncoder;
        this.auditTrail = auditTrail;
    }

    /**
     * @throws CredentialRejected if the password is wrong, or the account has no second
     *                            factor to remove
     */
    @Transactional
    public void run(UUID accountId, String currentPassword, AuditActor actor) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> NotFoundException.of("account", accountId));

        if (account.totpSecret() == null) {
            throw CredentialRejected.of("currentPassword",
                    "Two-factor authentication is not on for this account.");
        }
        if (!passwordEncoder.matches(currentPassword == null ? "" : currentPassword,
                account.passwordHash())) {
            auditTrail.record(AuditEntry.denied(actor, "account.two_factor_disable",
                    AuditTarget.of("account", account.id(), account.email()), null,
                    "The password given to turn the second factor off was wrong."));
            throw CredentialRejected.of("currentPassword", "That is not your current password.");
        }

        accounts.save(account.withTotp(null, null));
        int discarded = recoveryCodes.deleteAllFor(accountId);

        auditTrail.record(AuditEntry.succeeded(actor, "account.two_factor_disable",
                AuditTarget.of("account", account.id(), account.email()), null,
                "Two-factor authentication turned off; " + discarded
                        + " recovery code(s) discarded."));
    }
}
