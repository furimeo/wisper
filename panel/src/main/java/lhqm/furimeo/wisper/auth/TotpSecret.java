package lhqm.furimeo.wisper.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;

/**
 * The shared secret behind an authenticator app, plus the two encodings it has to survive
 * in: RFC 4648 base32 for the {@code otpauth://} URI a phone scans, and an AES-GCM
 * envelope for the {@code account.totp_secret} column.
 *
 * <p>Base32 is hand-written because the JDK has {@code Base64} and not {@code Base32},
 * and because pulling in Apache Commons Codec for thirty lines of table lookup is exactly
 * the dependency AGENTS.md §6 is about. The alphabet and the "no padding, upper case"
 * choice are fixed by what every authenticator app accepts, not by preference.
 *
 * @param value the raw secret; 160 bits, the length RFC 4226 §4 R6 recommends for
 *              HMAC-SHA1 and the length Google Authenticator, Aegis and 1Password all
 *              accept without complaint
 */
public record TotpSecret(byte[] value) {

    /** RFC 4648 §6, upper case. Padding is omitted; authenticator apps reject it. */
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /** 160 bits, matching the HMAC-SHA1 block the code is derived from. */
    public static final int LENGTH_BYTES = 20;

    private static final SecureRandom RANDOM = new SecureRandom();

    public TotpSecret {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException("A TOTP secret cannot be empty");
        }
        // Defensive copy: the array is the credential, and a caller that keeps a
        // reference could otherwise zero or alter it after enrolment succeeded.
        value = value.clone();
    }

    /** A fresh secret from {@link SecureRandom}. */
    public static TotpSecret generate() {
        byte[] bytes = new byte[LENGTH_BYTES];
        RANDOM.nextBytes(bytes);
        return new TotpSecret(bytes);
    }

    /**
     * Reads a secret back from its base32 form, which is what the encrypted column holds.
     *
     * <p>Spaces and lower case are accepted because a person typing a secret in by hand
     * from a printed page gets both wrong, and refusing them helps nobody.
     */
    public static TotpSecret ofBase32(String encoded) {
        String cleaned = encoded.replace(" ", "").replace("-", "")
                .replace("=", "").toUpperCase(Locale.ROOT);
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("A TOTP secret cannot be empty");
        }
        byte[] out = new byte[cleaned.length() * 5 / 8];
        int written = 0;
        int buffer = 0;
        int bitsHeld = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            int symbol = ALPHABET.indexOf(cleaned.charAt(i));
            if (symbol < 0) {
                throw new IllegalArgumentException(
                        "\"" + cleaned.charAt(i) + "\" is not a base32 character");
            }
            buffer = (buffer << 5) | symbol;
            bitsHeld += 5;
            if (bitsHeld >= 8) {
                bitsHeld -= 8;
                out[written++] = (byte) ((buffer >> bitsHeld) & 0xff);
            }
        }
        // A trailing partial group carries no whole byte; dropping it is what the
        // encoder's own padding would have said.
        return new TotpSecret(Arrays.copyOf(out, written));
    }

    /** The form shown on screen and embedded in the provisioning URI. */
    public String base32() {
        StringBuilder encoded = new StringBuilder((value.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsHeld = 0;
        for (byte b : value) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsHeld += 8;
            while (bitsHeld >= 5) {
                bitsHeld -= 5;
                encoded.append(ALPHABET.charAt((buffer >> bitsHeld) & 0x1f));
            }
        }
        if (bitsHeld > 0) {
            encoded.append(ALPHABET.charAt((buffer << (5 - bitsHeld)) & 0x1f));
        }
        return encoded.toString();
    }

    /**
     * The same value in groups of four, which is what goes under the QR code.
     *
     * <p>Somebody whose camera will not focus types this in, and thirty-two unbroken
     * characters is where they lose their place.
     */
    public String base32Grouped() {
        String plain = base32();
        StringBuilder grouped = new StringBuilder(plain.length() + plain.length() / 4);
        for (int i = 0; i < plain.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                grouped.append(' ');
            }
            grouped.append(plain.charAt(i));
        }
        return grouped.toString();
    }

    /**
     * The {@code otpauth://} URI an authenticator app reads from a QR code.
     *
     * <p>The algorithm, digit count and period are written out rather than left to
     * defaults. They are defaults in the spec, but not in every app, and an app that
     * assumed eight digits produces codes that never verify with no clue as to why.
     */
    public String provisioningUri(String issuer, String accountEmail) {
        String label = encode(issuer) + ":" + encode(accountEmail);
        return "otpauth://totp/" + label
                + "?secret=" + base32()
                + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1"
                + "&digits=" + TimeBasedOneTimePassword.DIGITS
                + "&period=" + TimeBasedOneTimePassword.STEP_SECONDS;
    }

    /** The raw bytes, copied, for the HMAC. */
    @Override
    public byte[] value() {
        return value.clone();
    }

    private static String encode(String part) {
        return URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
