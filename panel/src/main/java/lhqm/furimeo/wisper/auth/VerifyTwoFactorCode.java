package lhqm.furimeo.wisper.auth;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.crypto.SecretCipher;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Checks a six-digit code against an account's TOTP secret.
 *
 * <p>Used twice and in two different states: by {@link EnableTwoFactor} to confirm an
 * enrolment, where the secret exists but is not yet confirmed, and by the sign-in
 * challenge, where it is. Both are the same question, so both ask it here rather than one
 * of them reimplementing the drift window.
 *
 * <p>The secret is decrypted for the length of one comparison and then dropped. It is
 * stored as an AES-GCM envelope ({@link SecretCipher}) rather than hashed because a TOTP
 * code cannot be verified without the secret itself - unlike a password, which is why
 * this is the one credential in the {@code auth} package the panel can read back.
 */
@Component
public class VerifyTwoFactorCode {

    private final AccountRepository accounts;
    private final SecretCipher secretCipher;
    private final AuthSettings settings;

    public VerifyTwoFactorCode(AccountRepository accounts, SecretCipher secretCipher,
                               AuthSettings settings) {
        this.accounts = accounts;
        this.secretCipher = secretCipher;
        this.settings = settings;
    }

    /**
     * Whether {@code presentedCode} is valid for the account right now.
     *
     * <p>False rather than an exception for a wrong code: a wrong code is an expected
     * outcome of a form, not an exceptional one, and the caller answers differently
     * depending on whether it was the enrolment screen or the sign-in challenge.
     *
     * @throws NotFoundException if there is no such account
     * @throws IllegalStateException if the account has no secret at all. That is not a
     *                               wrong code, it is a caller asking the wrong question,
     *                               and returning false would hide the bug
     */
    public boolean run(UUID accountId, String presentedCode) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> NotFoundException.of("account", accountId));
        return run(account, presentedCode);
    }

    /** The same check when the row is already loaded, which the sign-in path has. */
    public boolean run(Account account, String presentedCode) {
        if (account.totpSecret() == null) {
            throw new IllegalStateException(
                    "Account " + account.id() + " has no TOTP secret to check a code against");
        }
        TotpSecret secret = TotpSecret.ofBase32(secretCipher.decrypt(account.totpSecret()));
        return TimeBasedOneTimePassword.matches(secret, presentedCode, Instant.now(),
                settings.totpDriftSteps());
    }
}
