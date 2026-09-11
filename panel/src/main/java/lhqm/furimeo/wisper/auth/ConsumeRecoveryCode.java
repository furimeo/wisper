package lhqm.furimeo.wisper.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;

/**
 * Spends one recovery code to get past the TOTP challenge.
 *
 * <p>The lookup is by hash alone, because the hash column is globally unique - but the
 * row is then checked to belong to the account answering the challenge. Both halves
 * matter: without the uniqueness the hash could collide across accounts, and without the
 * ownership check a code belonging to somebody else would satisfy this challenge.
 *
 * <p>Spending sets {@code used_at} rather than deleting the row, so a replay finds a code
 * that is known and spent instead of one that is unknown, and so the security page can
 * say how many of the batch are left.
 *
 * <p>Recorded in the audit trail either way. Somebody using a recovery code is somebody
 * who has lost their phone or is in the middle of taking an account over, and the entry
 * is what tells the two apart afterwards.
 */
@Component
public class ConsumeRecoveryCode {

    private final AccountRecoveryCodeRepository recoveryCodes;
    private final AuditTrail auditTrail;

    public ConsumeRecoveryCode(AccountRecoveryCodeRepository recoveryCodes,
                               AuditTrail auditTrail) {
        this.recoveryCodes = recoveryCodes;
        this.auditTrail = auditTrail;
    }

    /**
     * @return true when the code was valid, unused and this account's - and is now spent
     */
    @Transactional
    public boolean run(UUID accountId, String accountEmail, String presented, AuditActor actor) {
        Optional<RecoveryCode> parsed = RecoveryCode.parse(presented);
        if (parsed.isEmpty()) {
            return false;
        }

        Optional<AccountRecoveryCode> row = recoveryCodes.findByCodeHash(parsed.get().hash());
        AuditTarget target = AuditTarget.of("account", accountId, accountEmail);

        if (row.isEmpty() || !row.get().accountId().equals(accountId)) {
            auditTrail.record(AuditEntry.denied(actor, "account.recovery_code_used", target, null,
                    "A recovery code that does not belong to this account was presented."));
            return false;
        }
        if (row.get().usedAt() != null) {
            auditTrail.record(AuditEntry.denied(actor, "account.recovery_code_used", target, null,
                    "A recovery code that had already been used was presented again."));
            return false;
        }

        recoveryCodes.save(row.get().consumed(Instant.now()));
        long left = recoveryCodes.countByAccountIdAndUsedAtIsNull(accountId);
        auditTrail.record(AuditEntry.succeeded(actor, "account.recovery_code_used", target, null,
                "Signed in with a recovery code; " + left + " left."));
        return true;
    }
}
