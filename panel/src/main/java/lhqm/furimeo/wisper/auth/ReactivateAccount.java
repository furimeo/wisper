package lhqm.furimeo.wisper.auth;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Lets a suspended person sign in again.
 *
 * <p>Also clears any throttle lock, because the two are usually the same incident seen
 * from both ends: somebody was locked out by wrong passwords, an operator suspended the
 * account while working out why, and reactivating it with the lock still in place would
 * look like the reactivation did not work.
 *
 * <p>It does not restore the sessions that suspension ended. Those are gone; the person
 * signs in again, which is also what puts a fresh row in the sign-in history.
 */
@Component
public class ReactivateAccount {

    private final AccountRepository accounts;
    private final AuditTrail auditTrail;

    public ReactivateAccount(AccountRepository accounts, AuditTrail auditTrail) {
        this.accounts = accounts;
        this.auditTrail = auditTrail;
    }

    @Transactional
    public Account run(UUID accountId, AuditActor actor) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> NotFoundException.of("account", accountId));
        if (account.status() == AccountStatus.ACTIVE && account.lockedUntil() == null) {
            return account;
        }

        Account active = accounts.save(account.withStatus(AccountStatus.ACTIVE));
        auditTrail.record(AuditEntry.succeeded(actor, "account.reactivate",
                AuditTarget.of("account", account.id(), account.email()), null,
                "Account reactivated and any sign-in lock lifted."));
        return active;
    }
}
