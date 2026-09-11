package lhqm.furimeo.wisper.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of a bearer credential, as stored in the columns the panel may only compare.
 *
 * <p>Three columns in this package hold one of these: {@code session.session_id_hash},
 * {@code api_token.token_hash} and {@code account_recovery_code.code_hash}. All three are
 * high-entropy values the panel generated itself, which is exactly the case where a plain
 * digest is right and a password hash is not - BCrypt's work factor buys resistance to
 * guessing a human-chosen secret, and there is nothing to guess in 256 random bits. It
 * would also make the per-request session lookup a table scan, because a salted hash
 * cannot be indexed.
 *
 * <p>Passwords do <em>not</em> come near this class. They are BCrypt, through the
 * {@code PasswordEncoder} bean.
 */
public final class TokenDigest {

    private TokenDigest() {
    }

    /** Lower-case hex of the SHA-256 of {@code value}, which is what the column holds. */
    public static String of(String value) {
        MessageDigest digest = sha256();
        byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    /**
     * Whether a presented credential hashes to a stored digest.
     *
     * <p>Compared with {@link MessageDigest#isEqual}, which does not stop at the first
     * differing byte. A plain {@code equals} on the hex string leaks, through timing, how
     * many leading characters a guess got right - which over enough attempts recovers the
     * digest one character at a time.
     */
    public static boolean matches(String value, String storedDigest) {
        if (value == null || storedDigest == null) {
            return false;
        }
        return MessageDigest.isEqual(
                of(value).getBytes(StandardCharsets.UTF_8),
                storedDigest.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            // Every JDK ships SHA-256; the checked exception is a relic of the 1.4 API.
            throw new IllegalStateException("SHA-256 is missing from this JDK", impossible);
        }
    }
}
