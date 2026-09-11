package lhqm.furimeo.wisper.auth;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;

/**
 * One recovery code, in the two forms it exists in: the normalised value that gets
 * hashed, and the grouped value a person reads off a printout.
 *
 * <p>A recovery code is what a customer uses when the phone with the authenticator on it
 * is at the bottom of a canal. It therefore has to survive being written down, read back
 * a year later and typed on a phone keyboard, which is why the alphabet excludes the four
 * characters that get confused - {@code 0} and {@code O}, {@code 1} and {@code I} - and
 * why {@link #parse} throws away spaces, hyphens and case before comparing anything.
 *
 * <p>Fifty bits of entropy per code. That is deliberately less than an API token and
 * deliberately far more than a PIN: the code is only reachable behind a correct password,
 * and each one works exactly once, so the number that matters is "can this be guessed
 * before the sign-in throttle bites", not "can this be brute-forced offline".
 *
 * @param value the normalised form: ten characters of {@link #ALPHABET}, upper case
 */
public record RecoveryCode(String value) {

    /** Crockford-shaped: no O, I, 0 or 1, so a handwritten code reads back unambiguously. */
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    /** Ten characters of a 32-symbol alphabet: fifty bits. */
    private static final int LENGTH = 10;

    /** Where the hyphen goes in the printed form. */
    private static final int GROUP = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    public RecoveryCode {
        if (value == null || value.length() != LENGTH) {
            throw new IllegalArgumentException("A recovery code is " + LENGTH + " characters");
        }
    }

    /** A fresh code from {@link SecureRandom}. */
    public static RecoveryCode generate() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return new RecoveryCode(code.toString());
    }

    /**
     * Reads what somebody typed.
     *
     * <p>Case is folded up and the separators a person adds - spaces, hyphens - are
     * dropped. Nothing else is corrected: the alphabet has no {@code O}, {@code I},
     * {@code 0} or {@code 1} in it, so a character outside it is a transcription error
     * with no single obvious repair, and guessing which one was meant would silently turn
     * one code into another.
     *
     * <p>Empty when the result is not a code at all, so the caller answers "that is not
     * one of your codes" without a database lookup.
     */
    public static Optional<RecoveryCode> parse(String presented) {
        if (presented == null) {
            return Optional.empty();
        }
        StringBuilder cleaned = new StringBuilder(LENGTH);
        for (char raw : presented.toUpperCase(Locale.ROOT).toCharArray()) {
            if (ALPHABET.indexOf(raw) >= 0) {
                cleaned.append(raw);
            } else if (raw != ' ' && raw != '-') {
                return Optional.empty();
            }
        }
        if (cleaned.length() != LENGTH) {
            return Optional.empty();
        }
        return Optional.of(new RecoveryCode(cleaned.toString()));
    }

    /** The form shown once on screen and meant to be printed: {@code AB3CD-EF7GH}. */
    public String display() {
        return value.substring(0, GROUP) + "-" + value.substring(GROUP);
    }

    /** What {@code account_recovery_code.code_hash} holds. */
    public String hash() {
        return TokenDigest.of(value);
    }
}
