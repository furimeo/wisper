package lhqm.furimeo.wisper.crypto;

import java.util.Base64;
import java.util.regex.Pattern;

/**
 * The on-disk form of every reversibly encrypted column in the schema.
 *
 * <pre>
 *   v&lt;keyVersion&gt;.&lt;base64url nonce&gt;.&lt;base64url ciphertext&gt;
 * </pre>
 *
 * <p>One text column rather than three, because rotating a key then rewrites one value
 * and a reader always knows which key produced what it is holding. The
 * {@code *_is_envelope} CHECK constraints in the migrations enforce exactly this shape
 * in the database; this record enforces it in Java, so a plaintext value written into an
 * encrypted column fails at the boundary that has a stack trace rather than at the one
 * that has a constraint name.
 *
 * <p>Base64 <em>url</em>, unpadded: the CHECK is written against
 * {@code [A-Za-z0-9_-]+} and standard base64's {@code +}, {@code /} and {@code =} would
 * all fail it. The nonce is stored beside the ciphertext because AES-GCM needs it to
 * decrypt and it is not secret - reusing one is what would be fatal, and that is the
 * cipher's problem, not this format's.
 *
 * <p>Columns in this format: {@code account.totp_secret}, {@code service.webhook_secret},
 * {@code service.repository_credential}, {@code secret.value},
 * {@code database_engine.admin_password}, {@code managed_database.db_password},
 * {@code backup_destination.secret_access_key},
 * {@code backup_destination.archive_passphrase}.
 *
 * @param keyVersion which key encrypted this, starting at 1
 * @param nonce      the AES-GCM nonce, twelve bytes
 * @param ciphertext the ciphertext with its authentication tag appended
 */
public record SecretEnvelope(int keyVersion, byte[] nonce, byte[] ciphertext) {

    /** The same expression the {@code *_is_envelope} CHECK constraints use. */
    private static final Pattern SHAPE =
            Pattern.compile("^v[0-9]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /**
     * AES-GCM's standard nonce length. Twelve bytes is the only size the construction is
     * specified for; anything else is hashed into one and loses the uniqueness guarantee
     * that makes GCM safe.
     */
    public static final int NONCE_BYTES = 12;

    public SecretEnvelope {
        if (keyVersion < 1) {
            throw new IllegalArgumentException("Key versions start at 1, not " + keyVersion);
        }
        if (nonce == null || nonce.length != NONCE_BYTES) {
            throw new IllegalArgumentException(
                    "An AES-GCM nonce is " + NONCE_BYTES + " bytes");
        }
        if (ciphertext == null || ciphertext.length == 0) {
            throw new IllegalArgumentException("An envelope with no ciphertext holds nothing");
        }
        // Records expose the array by reference; copying on the way in and out is what
        // stops a caller mutating a value another caller is still holding.
        nonce = nonce.clone();
        ciphertext = ciphertext.clone();
    }

    /** Whether a stored value has the shape of an envelope at all. */
    public static boolean looksLikeEnvelope(String stored) {
        return stored != null && SHAPE.matcher(stored).matches();
    }

    /**
     * Reads a stored value.
     *
     * @throws IllegalArgumentException if it is not an envelope. That case is a
     *         plaintext value in an encrypted column, which is a bug worth stopping on
     *         rather than a value worth guessing at.
     */
    public static SecretEnvelope parse(String stored) {
        if (!looksLikeEnvelope(stored)) {
            throw new IllegalArgumentException(
                    "Not an encryption envelope. Expected v<n>.<nonce>.<ciphertext>; "
                            + "a plaintext value has been written into an encrypted column.");
        }
        int firstDot = stored.indexOf('.');
        int secondDot = stored.indexOf('.', firstDot + 1);
        int version = Integer.parseInt(stored.substring(1, firstDot));
        byte[] nonce = DECODER.decode(stored.substring(firstDot + 1, secondDot));
        byte[] ciphertext = DECODER.decode(stored.substring(secondDot + 1));
        return new SecretEnvelope(version, nonce, ciphertext);
    }

    /** The value to store in the column. */
    public String text() {
        return "v" + keyVersion + "." + ENCODER.encodeToString(nonce)
                + "." + ENCODER.encodeToString(ciphertext);
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    /**
     * The stored form, never the contents.
     *
     * <p>The default record {@code toString} would print two byte arrays as identity
     * hashes, which is useless, and any friendlier version risks a secret reaching a log
     * line. The envelope itself is safe to print: without the key it is noise.
     */
    @Override
    public String toString() {
        return text();
    }

    /**
     * Two envelopes are equal when they store the same text.
     *
     * <p>The generated version would compare the two byte arrays by identity, so an
     * envelope would not equal itself parsed back from its own {@link #text()} - which
     * is exactly the comparison a test writes first.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof SecretEnvelope envelope && text().equals(envelope.text());
    }

    @Override
    public int hashCode() {
        return text().hashCode();
    }
}
