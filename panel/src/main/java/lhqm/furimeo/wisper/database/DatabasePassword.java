package lhqm.furimeo.wisper.database;

import java.security.SecureRandom;

/**
 * Generates the passwords the platform gives out for database logins and for an engine's
 * administrative account.
 *
 * <h2>The alphabet, and why it is not the whole keyboard</h2>
 *
 * <p>These passwords travel in three places that each mangle a different character set: a
 * URI ({@code postgresql://user:pass@host/db}), a shell-free {@code exec} argument list on
 * the node, and an engine's own {@code ALTER ROLE} statement. Rather than escape correctly
 * in three grammars, the alphabet excludes every character any of them treats specially -
 * no {@code @}, {@code :}, {@code /}, {@code #}, {@code ?}, {@code %}, no quotes, no
 * backslash, no space. What is left is letters, digits and a handful of punctuation marks
 * that are literal everywhere.
 *
 * <p>{@code 0}, {@code O}, {@code l}, {@code 1} and {@code I} are also out. A customer
 * reads one of these off a screen and types it into a config file, and a password that
 * fails because of a glyph nobody can tell apart is a support ticket the platform created
 * for itself.
 *
 * <p>32 characters over a 64-symbol alphabet is 192 bits. The cost of being generous here
 * is nothing; the cost of being stingy is a customer database somebody guessed.
 */
public final class DatabasePassword {

    /**
     * 64 symbols exactly, so a byte drawn uniformly can be reduced with no modulo bias
     * once the top two bits are discarded.
     */
    private static final char[] ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789-_+=.,~".toCharArray();

    static {
        // Not decoration. The generator masks a random byte with 0x3F, which is only a
        // uniform choice when there are exactly 64 symbols; 63 would index out of bounds
        // and 65 would make the last symbol unreachable. Anybody editing the string above
        // finds out here rather than in production.
        if (ALPHABET.length != 64) {
            throw new IllegalStateException("The password alphabet has " + ALPHABET.length
                    + " symbols; a six-bit draw needs exactly 64");
        }
    }

    /** Long enough that nobody has to think about whether it is long enough. */
    private static final int LENGTH = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private DatabasePassword() {
    }

    /** A new password for a customer's database login. */
    public static String generate() {
        // 64 symbols is 6 bits, so each byte contributes one character with its low six
        // bits and no rejection sampling is needed.
        byte[] entropy = new byte[LENGTH];
        RANDOM.nextBytes(entropy);
        StringBuilder password = new StringBuilder(LENGTH);
        for (byte b : entropy) {
            password.append(ALPHABET[b & 0x3F]);
        }
        return password.toString();
    }

    /**
     * Whether a password can be carried through a URI, an argument list and a SQL literal
     * without escaping.
     *
     * <p>Used as an assertion on the way out rather than as validation of user input:
     * nobody chooses one of these passwords, so a failure here means this class changed
     * and something downstream is about to be quoted wrongly.
     */
    public static boolean isSafe(String password) {
        if (password == null || password.length() < 16) {
            return false;
        }
        for (char c : password.toCharArray()) {
            if (!contains(c)) {
                return false;
            }
        }
        return true;
    }

    /** How many characters {@link #generate} produces, for a screen that says so. */
    public static int length() {
        return LENGTH;
    }

    private static boolean contains(char candidate) {
        for (char c : ALPHABET) {
            if (c == candidate) {
                return true;
            }
        }
        return false;
    }
}
