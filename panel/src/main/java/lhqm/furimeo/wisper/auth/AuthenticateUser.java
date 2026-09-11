package lhqm.furimeo.wisper.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditActorKind;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;

/**
 * Checks an email and a password, and applies the sign-in throttle.
 *
 * <h2>Why this is not transactional</h2>
 *
 * <p>Every failure path here writes something - the failure counter, the lock, an audit
 * entry - and then throws. A {@code @Transactional} method that throws rolls its writes
 * back, so wrapping this would silently reset the counter on every wrong password and
 * turn the lockout into decoration. The method does one read and at most one write, so
 * there is nothing to make atomic beyond what a single statement already is.
 *
 * <h2>The throttle</h2>
 *
 * <p>{@code maxFailedSignIns} wrong passwords in a row set {@code locked_until} and zero
 * the counter. While the lock holds, even the correct password is refused - otherwise the
 * lock would only slow down an attacker who is wrong, which is the one who was never the
 * problem. A successful sign-in clears both.
 */
@Component
public class AuthenticateUser {

    private static final Logger log = LoggerFactory.getLogger(AuthenticateUser.class);

    /**
     * A real BCrypt hash of a value nobody knows, verified against when the address is
     * unknown.
     *
     * <p>Without it, a sign-in against an address that does not exist returns in
     * microseconds while a wrong password takes the tens of milliseconds BCrypt costs.
     * The difference is measurable over a network and turns the sign-in form into a
     * "does this person have an account here" oracle.
     */
    private static final String ABSENT_ACCOUNT_HASH =
            new BCryptPasswordEncoder().encode(UUID.randomUUID().toString());

    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final AuthSettings settings;
    private final AuditTrail auditTrail;

    public AuthenticateUser(AccountRepository accounts, PasswordEncoder passwordEncoder,
                            AuthSettings settings, AuditTrail auditTrail) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.settings = settings;
        this.auditTrail = auditTrail;
    }

    /**
     * @param remoteAddress the caller's address as Spring Security saw it, recorded on the
     *                      account and in the audit entry
     * @return the principal to put in the {@code SecurityContext}
     * @throws BadCredentialsException if the address is unknown or the password is wrong
     * @throws LockedException         if the throttle is holding the account shut
     * @throws DisabledException       if the account is suspended
     */
    public SignedInAccount run(String email, String password, String remoteAddress) {
        String address = Account.normaliseEmail(email);
        Instant now = Instant.now();

        Optional<Account> found = accounts.findByEmail(address);
        if (found.isEmpty()) {
            passwordEncoder.matches(password == null ? "" : password, ABSENT_ACCOUNT_HASH);
            refuse(actorFor(null, address, remoteAddress), null, address,
                    "No account with that address.");
            throw new BadCredentialsException("Bad credentials");
        }

        Account account = found.get();
        AuditActor actor = actorFor(account.id(), account.email(), remoteAddress);

        if (account.status() == AccountStatus.SUSPENDED) {
            refuse(actor, account.id(), account.email(), "The account is suspended.");
            throw new DisabledException("Account suspended");
        }
        if (account.isLockedAt(now)) {
            refuse(actor, account.id(), account.email(),
                    "The account is locked until " + account.lockedUntil() + ".");
            throw new LockedException("Account locked");
        }
        if (!passwordEncoder.matches(password == null ? "" : password, account.passwordHash())) {
            Account throttled = registerFailure(account, now);
            String detail = throttled.lockedUntil() != null
                    ? "Wrong password; locked until " + throttled.lockedUntil() + "."
                    : "Wrong password (" + throttled.failedLoginCount() + " in a row).";
            refuse(actor, account.id(), account.email(), detail);
            throw new BadCredentialsException("Bad credentials");
        }

        accounts.save(account.signedInAt(now, remoteAddress));
        auditTrail.record(AuditEntry.succeeded(actor, "account.sign_in",
                AuditTarget.of("account", account.id(), account.email()), null,
                account.hasSecondFactor()
                        ? "Password accepted; second factor outstanding."
                        : "Signed in."));
        return SignedInAccount.of(account);
    }

    /**
     * Counts the failure and locks the account if this was the last one allowed.
     *
     * <p>An optimistic-locking failure here means two wrong guesses landed at the same
     * moment and one of them counted. That is not worth failing the request over, and
     * retrying would give an attacker a way to make the panel do more work per guess.
     */
    private Account registerFailure(Account account, Instant now) {
        int count = account.failedLoginCount() + 1;
        Account updated = count >= settings.maxFailedSignIns()
                ? account.lockedUntil(now.plus(settings.lockout()))
                : account.withFailedSignIn(count);
        try {
            return accounts.save(updated);
        } catch (OptimisticLockingFailureException concurrent) {
            log.debug("Two failed sign-ins for {} raced; one was counted", account.email());
            return updated;
        }
    }

    private void refuse(AuditActor actor, UUID accountId, String label, String detail) {
        auditTrail.record(AuditEntry.denied(actor, "account.sign_in",
                accountId == null
                        ? AuditTarget.unidentified("account", label)
                        : AuditTarget.of("account", accountId, label),
                null, detail));
    }

    /**
     * The actor for a sign-in attempt.
     *
     * <p>Built by hand rather than through {@code AuditActor.account(...)} because there
     * is no {@code HttpServletRequest} in a Spring Security {@code AuthenticationProvider} -
     * the address comes from the authentication's {@code WebAuthenticationDetails} - and
     * because the account id is null when the address is unknown, which is exactly the
     * attempt worth recording.
     */
    private static AuditActor actorFor(UUID accountId, String label, String remoteAddress) {
        return new AuditActor(AuditActorKind.ACCOUNT, accountId, null, null,
                label.isBlank() ? "(no address given)" : label, remoteAddress, null, null);
    }
}
