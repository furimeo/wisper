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
 * Bars a person from signing in, and ends every session they have open.
 *
 * <p>Suspending a person is not suspending their organization. Their colleagues keep
 * working and their containers keep serving; the two are separate columns on separate
 * tables for exactly that reason (docs/contracts/schema.md).
 *
 * <p>Refuses to suspend the last operator who can still sign in. A platform with no
 * reachable admin cannot be administered, and the way back is editing the database by
 * hand - which the person who just did it is in no position to do, because they cannot
 * sign in either.
 */
@Component
public class SuspendAccount {

    private final AccountRepository accounts;
    private final SignOutEverywhere signOutEverywhere;
    private final AuditTrail auditTrail;

    public SuspendAccount(AccountRepository accounts, SignOutEverywhere signOutEverywhere,
                          AuditTrail auditTrail) {
        this.accounts = accounts;
        this.signOutEverywhere = signOutEverywhere;
        this.auditTrail = auditTrail;
    }

    /**
     * @throws CredentialRejected if this would leave the platform with no admin who can
     *                            sign in
     */
    @Transactional
    public Account run(UUID accountId, AuditActor actor) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> NotFoundException.of("account", accountId));

        if (account.status() == AccountStatus.SUSPENDED) {
            return account;
        }
        if (account.platformRole() == PlatformRole.ADMIN && accounts.countActiveAdmins() <= 1) {
            auditTrail.record(AuditEntry.denied(actor, "account.suspend",
                    AuditTarget.of("account", account.id(), account.email()), null,
                    "Refused: this is the last operator who can sign in."));
            throw CredentialRejected.of("accountId",
                    "This is the last administrator who can still sign in. Promote somebody "
                    + "else first, or nobody will be able to administer the platform.");
        }

        Account suspended = accounts.save(account.withStatus(AccountStatus.SUSPENDED));
        signOutEverywhere.run(accountId, account.email(), null,
                SessionRevocationReason.ACCOUNT_SUSPENDED, actor);

        auditTrail.record(AuditEntry.succeeded(actor, "account.suspend",
                AuditTarget.of("account", account.id(), account.email()), null,
                "Account suspended and every session ended."));
        return suspended;
    }
}
