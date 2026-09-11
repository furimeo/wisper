package lhqm.furimeo.wisper.auth;

import java.time.Instant;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;

/**
 * Creates an account.
 *
 * <p>There is no public sign-up. The panel is a hosting control plane, not a consumer
 * product: accounts are created by a platform operator on {@code /admin/accounts}, and by
 * {@link BootstrapAdminAccount} on a database with nobody in it yet. That is why
 * {@code /register} is absent from both the URL map in docs/contracts/panel-http.md and
 * from {@code PUBLIC_PATHS} - a door that leads somewhere nobody is supposed to go is
 * still a door.
 *
 * <p>The new account has no organization. Membership is the {@code org} package's to
 * grant, and creating one here would mean two packages could each create a tenant.
 */
@Component
public class RegisterUser {

    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final AuditTrail auditTrail;

    public RegisterUser(AccountRepository accounts, PasswordEncoder passwordEncoder,
                        AuditTrail auditTrail) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.auditTrail = auditTrail;
    }

    /**
     * @param actor who is creating the account - an operator, or the system on first run
     * @throws CredentialRejected if the address is malformed or taken, the display name is
     *                            blank, or the password does not meet
     *                            {@link PasswordPolicy}
     */
    @Transactional
    public Account run(String email, String displayName, String password, PlatformRole role,
                       AuditActor actor) {

        String address = Account.normaliseEmail(email);
        if (address.isEmpty()) {
            throw CredentialRejected.of("email", "Enter an email address.");
        }
        // The same shape account_email_shape enforces. Checked here so the answer is a
        // message under the field rather than a constraint violation in a stack trace.
        if (!address.matches(".+@.+")) {
            throw CredentialRejected.of("email", "That does not look like an email address.");
        }
        if (displayName == null || displayName.isBlank()) {
            throw CredentialRejected.of("displayName", "Enter a name to show in the panel.");
        }
        PasswordPolicy.rejectionReason(password, address).ifPresent(reason -> {
            throw CredentialRejected.of("password", reason);
        });
        if (accounts.existsByEmail(address)) {
            // Deliberately explicit. This form is only reachable by an operator who is
            // already administering accounts, so there is nothing to enumerate, and
            // "something went wrong" would send them looking for a bug.
            throw CredentialRejected.of("email", "There is already an account with that address.");
        }

        Account saved = accounts.save(Account.create(address, displayName,
                passwordEncoder.encode(password), role, Instant.now()));

        auditTrail.record(AuditEntry.succeeded(actor, "account.register",
                AuditTarget.of("account", saved.id(), saved.email()), null,
                "Created a " + role.name().toLowerCase() + " account."));
        return saved;
    }
}
