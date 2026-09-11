package lhqm.furimeo.wisper.node;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The two opaque strings a node holds: the bootstrap token it enrols with, and the
 * credential it presents on every call afterwards.
 *
 * <p>Both are 256 random bits the panel generated, and both are stored as a plain SHA-256
 * because the panel only ever compares them - {@code node_enrollment_token.token_hash} and
 * {@code node.credential_hash} are on schema.md's hashed list, not its encrypted one. A
 * password hash would be the wrong tool twice over: BCrypt's work factor buys resistance
 * to guessing a human-chosen secret and there is nothing here a person chose, and a salted
 * digest cannot be indexed, which would turn the per-call credential lookup into a table
 * scan.
 *
 * <p>{@code auth.TokenDigest} does the same job for sessions and API tokens. It is not
 * reused here because {@code node} does not depend on {@code auth} and must not
 * (panel-ports.md §6); the arrow runs the other way for the whole panel.
 *
 * <p>The prefixes are deliberate. A token pasted into the wrong field, or found in a shell
 * history, says what it is without anybody having to try it.
 *
 * @param text the value handed out exactly once, and never stored
 * @param hash what goes in the column
 */
public record NodeSecret(String text, String hash) {

    /** Marks a bootstrap token: single use, fifteen minutes, one node. */
    public static final String BOOTSTRAP_PREFIX = "wsp_";

    /** Marks a long-lived node credential, written 0600 into {@code /etc/wisper/node.json}. */
    public static final String CREDENTIAL_PREFIX = "wsn_";

    private static final int ENTROPY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** A fresh bootstrap token for one node record. */
    public static NodeSecret bootstrapToken() {
        return mint(BOOTSTRAP_PREFIX);
    }

    /** A fresh long-lived credential, issued at the end of a successful enrolment. */
    public static NodeSecret credential() {
        return mint(CREDENTIAL_PREFIX);
    }

    /** Lower-case hex SHA-256, which is what both columns hold. */
    public static String hashOf(String value) {
        byte[] digest = sha256().digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    /**
     * Whether a string is shaped like something this class produced.
     *
     * <p>Used to answer an obviously malformed credential without a database round trip,
     * which keeps a scanner hammering the gRPC port off the hottest index in the schema.
     */
    public static boolean looksLikeCredential(String presented) {
        return presented != null
                && presented.startsWith(CREDENTIAL_PREFIX)
                && presented.length() > CREDENTIAL_PREFIX.length() + 32;
    }

    private static NodeSecret mint(String prefix) {
        byte[] entropy = new byte[ENTROPY_BYTES];
        RANDOM.nextBytes(entropy);
        String text = prefix + ENCODER.encodeToString(entropy);
        return new NodeSecret(text, hashOf(text));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is missing from this JDK", impossible);
        }
    }

    @Override
    public String toString() {
        // The whole point of this type is that the text is shown once, at creation, by the
        // one screen that is allowed to. Nothing else, including a log line, gets it.
        return "NodeSecret[hash=" + hash.substring(0, 8) + "...]";
    }
}
