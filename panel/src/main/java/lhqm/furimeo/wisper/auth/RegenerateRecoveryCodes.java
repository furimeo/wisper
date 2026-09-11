package lhqm.furimeo.wisper.auth;

import java.util.ArrayList;
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
 * Throws away an account's recovery codes and issues a fresh batch.
 *
 * <p>Called once automatically, by {@link EnableTwoFactor}, and after that from the
 * security page by anyone who has used most of theirs or lost the printout.
 *
 * <p>Replace, never top up. A batch is one printed list; leaving the old codes working
 * would mean a person who regenerated because the paper went missing still has a valid
 * paper out there. The old rows are deleted rather than marked spent, because they were
 * never used and keeping them would make "you have used two of ten" count the wrong ten.
 *
 * <p>The plaintext is returned once and only once. Nothing stores it, and the page that
 * shows it cannot be reloaded to see it again - which is stated on that page, because a
 * person who closes the tab has to regenerate rather than being locked out later.
 */
@Component
public class RegenerateRecoveryCodes {

    private final AccountRepository accounts;
    private final AccountRecoveryCodeRepository recoveryCodes;
    private final AuthSettings settings;
    private final AuditTrail auditTrail;

    public RegenerateRecoveryCodes(AccountRepository accounts,
                                   AccountRecoveryCodeRepository recoveryCodes,
                                   AuthSettings settings, AuditTrail auditTrail) {
        this.accounts = accounts;
        this.recoveryCodes = recoveryCodes;
        this.settings = settings;
        this.auditTrail = auditTrail;
    }

    /**
     * @return the codes in their printed form, in the order they should be shown
     * @throws CredentialRejected if the account has no confirmed second factor, because
     *                            codes that recover from nothing would be a list of
     *                            secrets with no purpose
     */
    @Transactional
    public List<String> run(UUID accountId, AuditActor actor) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> NotFoundException.of("account", accountId));
        if (!account.hasSecondFactor()) {
            throw CredentialRejected.of("code",
                    "Turn on two-factor authentication first. Recovery codes are how you get "
                    + "past it when your phone is not to hand.");
        }

        recoveryCodes.deleteAllFor(accountId);

        List<String> printable = new ArrayList<>(settings.recoveryCodeCount());
        List<AccountRecoveryCode> rows = new ArrayList<>(settings.recoveryCodeCount());
        for (int i = 0; i < settings.recoveryCodeCount(); i++) {
            RecoveryCode code = RecoveryCode.generate();
            printable.add(code.display());
            rows.add(AccountRecoveryCode.issued(accountId, code.hash()));
        }
        recoveryCodes.saveAll(rows);

        auditTrail.record(AuditEntry.succeeded(actor, "account.recovery_codes_issued",
                AuditTarget.of("account", account.id(), account.email()), null,
                printable.size() + " recovery codes issued; any previous ones stopped working."));
        return List.copyOf(printable);
    }
}
