package lhqm.furimeo.wisper.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Confirms a TOTP enrolment with one working code, and hands over the recovery codes.
 *
 * <p>The second half of the handshake {@link BeginTwoFactorEnrolment} starts. Nothing is
 * switched on until a code the phone produced verifies here, which is the whole reason
 * {@code totp_confirmed_at} is a separate column from {@code totp_secret}: a person who
 * mistypes the secret would otherwise leave with two-factor authentication on and no way
 * to satisfy it.
 *
 * <p>Recovery codes are issued in the same transaction. Turning on a second factor
 * without a way past it is how an account becomes unrecoverable, and making the customer
 * press a second button for them means some of them will not.
 */
@Component
public class EnableTwoFactor {

    private final AccountRepository accounts;
    private final VerifyTwoFactorCode verifyTwoFactorCode;
    private final RegenerateRecoveryCodes regenerateRecoveryCodes;
    private final AuditTrail auditTrail;

    public EnableTwoFactor(AccountRepository accounts, VerifyTwoFactorCode verifyTwoFactorCode,
                           RegenerateRecoveryCodes regenerateRecoveryCodes,
                           AuditTrail auditTrail) {
        this.accounts = accounts;
        this.verifyTwoFactorCode = verifyTwoFactorCode;
        this.regenerateRecoveryCodes = regenerateRecoveryCodes;
        this.auditTrail = auditTrail;
    }

    /**
     * @return the recovery codes, shown once
     * @throws CredentialRejected if the enrolment was never started, is already finished,
     *                            or the code does not verify
     */
    @Transactional
    public List<String> run(UUID accountId, String code, AuditActor actor) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> NotFoundException.of("account", accountId));

        if (account.totpSecret() == null) {
            throw CredentialRejected.of("code",
                    "That setup has expired. Start again to get a new QR code.");
        }
        if (account.totpConfirmedAt() != null) {
            throw CredentialRejected.of("code",
                    "Two-factor authentication is already on for this account.");
        }
        if (!verifyTwoFactorCode.run(account, code)) {
            auditTrail.record(AuditEntry.denied(actor, "account.two_factor_enable",
                    AuditTarget.of("account", account.id(), account.email()), null,
                    "The confirmation code did not verify."));
            throw CredentialRejected.of("code",
                    "That code is not right. Check your phone's clock is set automatically, "
                    + "then try the next code it shows.");
        }

        accounts.save(account.withTotp(account.totpSecret(), Instant.now()));
        auditTrail.record(AuditEntry.succeeded(actor, "account.two_factor_enable",
                AuditTarget.of("account", account.id(), account.email()), null,
                "Two-factor authentication turned on."));

        return regenerateRecoveryCodes.run(accountId, actor);
    }
}
