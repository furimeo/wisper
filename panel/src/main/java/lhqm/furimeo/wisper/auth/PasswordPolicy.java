package lhqm.furimeo.wisper.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * What the panel refuses to accept as a password.
 *
 * <p>Two of the three rules here exist because of BCrypt rather than because of the
 * customer, and that is worth being explicit about:
 *
 * <ul>
 * <li><strong>At least twelve characters.</strong> Composition rules - one capital, one
 *     digit, one symbol - are not applied. They push people towards
 *     {@code Password1!} and NIST SP 800-63B stopped recommending them; length is what
 *     actually costs an attacker something.</li>
 * <li><strong>At most seventy-two bytes.</strong> BCrypt hashes the first 72 bytes and
 *     silently ignores everything after. Accepting a longer one would mean a person who
 *     set a 100-character passphrase could sign in with the first 72 characters of it,
 *     and would never find out. Refusing is the only honest option.</li>
 * <li><strong>Not the email address.</strong> The one guess every attacker makes first.</li>
 * </ul>
 *
 * <p>The byte length is measured in UTF-8, not in characters: an emoji is four bytes to
 * BCrypt, so a passphrase that looks short can still overflow the limit.
 */
public final class PasswordPolicy {

    /** Below this, length is not buying anything. */
    public static final int MINIMUM_CHARACTERS = 12;

    /** BCrypt's own ceiling. Anything past this byte is not hashed at all. */
    public static final int MAXIMUM_BYTES = 72;

    private PasswordPolicy() {
    }

    /**
     * The reason this password is unacceptable, or empty when it is fine.
     *
     * <p>One reason rather than a list: the form shows a single message under the field,
     * and three at once reads as an interrogation.
     *
     * @param email the account's address, so the password cannot simply be it
     */
    public static Optional<String> rejectionReason(String password, String email) {
        if (password == null || password.isEmpty()) {
            return Optional.of("Enter a password.");
        }
        if (password.length() < MINIMUM_CHARACTERS) {
            return Optional.of("Use at least " + MINIMUM_CHARACTERS
                    + " characters. A phrase of a few words is easier to remember and harder "
                    + "to guess than a short one with symbols in it.");
        }
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAXIMUM_BYTES) {
            return Optional.of("That is longer than " + MAXIMUM_BYTES
                    + " bytes, which is as much as the hashing algorithm reads. Shorten it, "
                    + "so that all of it counts.");
        }
        if (email != null && password.equalsIgnoreCase(email.strip())) {
            return Optional.of("Your password cannot be your email address.");
        }
        if (password.toLowerCase(Locale.ROOT).strip().isEmpty()) {
            return Optional.of("A password of only spaces is not a password.");
        }
        return Optional.empty();
    }
}
