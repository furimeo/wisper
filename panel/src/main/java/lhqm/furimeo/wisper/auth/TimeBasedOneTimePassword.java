package lhqm.furimeo.wisper.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 time-based one-time passwords, on top of RFC 4226's HOTP.
 *
 * <p>Written against {@code javax.crypto} rather than pulled in as a library. The whole
 * algorithm is an HMAC, a truncation and a modulo; a dependency for it would be forty
 * lines of code and one more thing to keep patched, which is what AGENTS.md §6 asks
 * callers to weigh.
 *
 * <h2>The drift window</h2>
 *
 * <p>A phone's clock and a server's clock are never exactly equal, and a person reading
 * six digits off a screen takes a second or two to type them. So a code is checked
 * against the current thirty-second step and {@code driftSteps} on each side of it. One
 * step each side - ninety seconds of acceptance - is the usual setting and the default in
 * {@link AuthSettings}. Widening it is a real cost: the window is how long a code
 * shoulder-surfed off somebody's screen stays usable.
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * <p>It does not remember which codes have been used. RFC 6238 §5.2 suggests refusing a
 * second use of one code, and doing that would need a column to hold the last accepted
 * step. The panel's answer instead is that a code is only ever accepted together with a
 * password that has already been checked, so replaying one inside its ninety seconds
 * requires the password too - at which point the second factor was not what was
 * protecting the account.
 */
public final class TimeBasedOneTimePassword {

    /** Six digits, as every authenticator app assumes unless told otherwise. */
    public static final int DIGITS = 6;

    /** Thirty seconds, the RFC 6238 default and the only period apps agree on. */
    public static final int STEP_SECONDS = 30;

    /** HMAC-SHA1: RFC 6238's default, and the only one Google Authenticator reads. */
    private static final String ALGORITHM = "HmacSHA1";

    private static final int[] POWERS_OF_TEN = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000};

    private TimeBasedOneTimePassword() {
    }

    /**
     * The code an authenticator holding {@code secret} shows at {@code when}.
     *
     * <p>Zero-padded to six characters, because the truncation produces a number and
     * {@code 001234} and {@code 1234} are the same number and different codes.
     */
    public static String codeAt(TotpSecret secret, Instant when) {
        return codeForStep(secret, stepAt(when));
    }

    /**
     * Whether {@code presented} is the code for any step within the drift window.
     *
     * <p>Every candidate is compared even after one matches. Returning early would make
     * the response measurably faster for a code that matched the first step tried, which
     * tells an attacker where in the window the server's clock is.
     */
    public static boolean matches(TotpSecret secret, String presented, Instant now, int driftSteps) {
        String normalised = normalise(presented);
        if (normalised.length() != DIGITS) {
            return false;
        }
        int window = Math.max(0, driftSteps);
        long current = stepAt(now);
        boolean matched = false;
        for (long step = current - window; step <= current + window; step++) {
            matched |= constantTimeEquals(codeForStep(secret, step), normalised);
        }
        return matched;
    }

    /**
     * Strips what a person's fingers add: spaces, and the hyphen some apps display.
     *
     * <p>Public because the challenge form reports "that is not six digits" before it
     * reaches the HMAC, and it has to strip the same characters this does or the two
     * disagree about what the person typed.
     */
    public static String normalise(String presented) {
        if (presented == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder(DIGITS);
        for (int i = 0; i < presented.length(); i++) {
            char c = presented.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        return digits.toString();
    }

    /** The thirty-second step number an instant falls in. */
    static long stepAt(Instant when) {
        return Math.floorDiv(when.getEpochSecond(), (long) STEP_SECONDS);
    }

    /** RFC 4226 HOTP: HMAC the counter, take four bytes from a dynamic offset, mod 10^d. */
    static String codeForStep(TotpSecret secret, long step) {
        byte[] counter = ByteBuffer.allocate(Long.BYTES).putLong(step).array();
        byte[] hash = hmac(secret.value(), counter);

        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);

        int code = binary % POWERS_OF_TEN[DIGITS];
        StringBuilder text = new StringBuilder(Integer.toString(code));
        while (text.length() < DIGITS) {
            text.insert(0, '0');
        }
        return text.toString();
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(message);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("HmacSHA1 is missing from this JDK", impossible);
        } catch (InvalidKeyException e) {
            // Only reachable with an empty key, which TotpSecret's constructor refuses.
            throw new IllegalStateException("The stored TOTP secret is not a usable key", e);
        }
    }

    private static boolean constantTimeEquals(String expected, String presented) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                presented.getBytes(StandardCharsets.US_ASCII));
    }
}
