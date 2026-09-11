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
 * Changes the name a person is shown as.
 *
 * <p>The email address is deliberately not changeable here. It is the sign-in identity,
 * it is what every audit entry that has already been written names, and changing it is
 * how an account takeover is made to look like an ordinary profile edit. An operator
 * moves an address from {@code /admin/accounts}, where it is a visible, audited act.
 */
@Component
public class UpdateDisplayName {

    /** Long enough for a real name, short enough not to break the header on a phone. */
    private static final int MAX_LENGTH = 80;

    private final AccountRepository accounts;
    private final AuditTrail auditTrail;

    public UpdateDisplayName(AccountRepository accounts, AuditTrail auditTrail) {
        this.accounts = accounts;
        this.auditTrail = auditTrail;
    }

    /** @throws CredentialRejected if the name is blank or too long */
    @Transactional
    public Account run(UUID accountId, String displayName, AuditActor actor) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> NotFoundException.of("account", accountId));

        String name = displayName == null ? "" : displayName.strip();
        if (name.isEmpty()) {
            throw CredentialRejected.of("displayName", "Enter a name.");
        }
        if (name.length() > MAX_LENGTH) {
            throw CredentialRejected.of("displayName",
                    "Keep the name under " + MAX_LENGTH + " characters.");
        }
        if (name.equals(account.displayName())) {
            return account;
        }

        Account renamed = accounts.save(account.withDisplayName(name));
        auditTrail.record(AuditEntry.succeeded(actor, "account.rename",
                AuditTarget.of("account", account.id(), account.email()), null,
                "Display name changed from \"" + account.displayName() + "\"."));
        return renamed;
    }
}
