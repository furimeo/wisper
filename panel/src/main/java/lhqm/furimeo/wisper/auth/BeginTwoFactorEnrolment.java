package lhqm.furimeo.wisper.auth;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.crypto.SecretCipher;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Starts TOTP enrolment: generates a secret, stores it unconfirmed, and hands back what
 * the authenticator app needs.
 *
 * <p>Two steps rather than one because the secret has to be in the phone before the panel
 * can know the phone has it. Storing it unconfirmed - a secret with
 * {@code totp_confirmed_at} still null - is what makes the half-finished state safe: the
 * {@code account_totp_confirmed_needs_secret} CHECK allows it, {@link Account#hasSecondFactor}
 * reports false for it, and so a person who closes the tab mid-setup is not challenged for
 * a code they cannot produce. {@link EnableTwoFactor} is the second step.
 *
 * <p>Calling this again before confirming replaces the secret. That is the "the QR code
 * did not scan, let me try again" path, and it must not be an error.
 */
@Component
public class BeginTwoFactorEnrolment {

    private final AccountRepository accounts;
    private final SecretCipher secretCipher;
    private final AuthSettings settings;

    public BeginTwoFactorEnrolment(AccountRepository accounts, SecretCipher secretCipher,
                                   AuthSettings settings) {
        this.accounts = accounts;
        this.secretCipher = secretCipher;
        this.settings = settings;
    }

    /**
     * @throws CredentialRejected if the second factor is already on - turning it off is a
     *                            separate, password-protected act, and quietly replacing
     *                            the secret would invalidate the phone without saying so
     */
    @Transactional
    public Enrolment run(UUID accountId) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> NotFoundException.of("account", accountId));
        if (account.hasSecondFactor()) {
            throw CredentialRejected.of("code",
                    "Two-factor authentication is already on for this account. Turn it off "
                    + "first if you want to move it to a different phone.");
        }

        TotpSecret secret = TotpSecret.generate();
        accounts.save(account.withTotp(secretCipher.encrypt(secret.base32()), null));

        return new Enrolment(secret.base32Grouped(),
                secret.provisioningUri(settings.totpIssuer(), account.email()));
    }

    /**
     * What the enrolment screen shows.
     *
     * <p>Both forms, because a phone camera reads the URI as a QR code and a person on
     * the same phone as the panel - which is most of them, this is a mobile-first UI -
     * has no second screen to point a camera at and types the secret in instead.
     *
     * @param secret          the base32 secret in groups of four
     * @param provisioningUri the {@code otpauth://} URI to render as a QR code
     */
    public record Enrolment(String secret, String provisioningUri) {
    }
}
