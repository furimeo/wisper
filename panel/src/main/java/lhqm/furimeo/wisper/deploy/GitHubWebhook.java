package lhqm.furimeo.wisper.deploy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * GitHub's half of {@code /webhooks/**}: proving a delivery is real, and reading it.
 *
 * <p>One class per provider because the signature scheme and the payload shape change
 * together. When GitHub renames a header it renames it alongside whatever else changed in
 * the same release, and a shared "verify a webhook" helper would be the file both
 * providers fight over.
 *
 * <h2>The signature</h2>
 *
 * <p>{@code X-Hub-Signature-256: sha256=<hex>}, an HMAC-SHA256 over the <strong>raw
 * request body</strong> keyed with the service's own webhook secret. Three things make
 * this a real check rather than a decorative one, and all three are easy to get wrong:
 *
 * <ul>
 * <li>The bytes hashed are the bytes that arrived. Re-serialising a parsed payload
 *     produces a different string and therefore a different digest, so the body is taken
 *     as {@code byte[]} and parsed only after it has been verified.</li>
 * <li>The comparison is {@link MessageDigest#isEqual}, which does not return early. A
 *     comparison that stops at the first wrong character leaks the correct prefix, and a
 *     signature can be recovered a byte at a time from the timing.</li>
 * <li>A missing or malformed header is a refusal, never a pass. "No signature configured"
 *     must not mean "accept anything".</li>
 * </ul>
 */
@Component
public class GitHubWebhook {

    /** The header carrying the HMAC. The v1 {@code X-Hub-Signature} is SHA-1 and ignored. */
    public static final String SIGNATURE_HEADER = "X-Hub-Signature-256";

    /** Which event this delivery is: {@code push}, {@code ping}, and a hundred others. */
    public static final String EVENT_HEADER = "X-GitHub-Event";

    private static final String PREFIX = "sha256=";
    private static final String HMAC = "HmacSHA256";
    private static final String PUSH = "push";

    /** A form-encoded delivery wraps the JSON in this parameter. */
    private static final String FORM_PARAMETER = "payload=";

    private final ObjectMapper json = new ObjectMapper();

    /**
     * Whether this body was signed with this secret.
     *
     * <p>False for every way a delivery can fail to prove itself: no header, a header with
     * the wrong prefix, hex that will not decode, a digest of the right length that does
     * not match. None of them is distinguished in the answer, because a caller that can
     * tell them apart is an oracle.
     */
    public boolean isSigned(byte[] body, String signatureHeader, String secret) {
        if (body == null || signatureHeader == null || secret == null || secret.isEmpty()) {
            return false;
        }
        String header = signatureHeader.strip();
        if (!header.startsWith(PREFIX)) {
            return false;
        }
        byte[] presented;
        try {
            presented = HexFormat.of().parseHex(header.substring(PREFIX.length()));
        } catch (IllegalArgumentException notHex) {
            return false;
        }
        return MessageDigest.isEqual(presented, sign(body, secret));
    }

    /** The HMAC a correctly configured GitHub would have sent, for tests and for the check. */
    public byte[] sign(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            return mac.doFinal(body);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is required of every JVM", impossible);
        }
    }

    /**
     * The push this delivery describes, or nothing.
     *
     * <p>Empty for a {@code ping} - the delivery GitHub sends when a hook is created, and
     * the one an operator uses to check the URL - and for every other event type, of which
     * there are dozens and none is a deployment. Empty rather than an exception: a hook
     * subscribed to more events than it needs is a configuration to shrug at, not a 500 in
     * somebody's delivery log.
     */
    public Optional<PushEvent> read(byte[] body, String eventHeader) {
        if (!PUSH.equalsIgnoreCase(eventHeader == null ? "" : eventHeader.strip())) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = json.readTree(unwrap(body));
        } catch (JacksonException unreadable) {
            return Optional.empty();
        }
        if (!root.isObject()) {
            // An empty body parses to a missing node rather than throwing. Reading fields
            // off it would produce a push with no ref, which is a shape nothing downstream
            // should have to recognise.
            return Optional.empty();
        }
        JsonNode head = root.path("head_commit");
        return Optional.of(new PushEvent(
                root.path("ref").asString(""),
                root.path("after").asString(""),
                head.path("message").asString(""),
                head.path("author").path("name").asString(""),
                root.path("deleted").asBoolean(false)));
    }

    /**
     * The JSON body, whichever content type the hook was configured with.
     *
     * <p>GitHub offers {@code application/json} and
     * {@code application/x-www-form-urlencoded}, and the second wraps the same document in
     * a {@code payload} parameter. The signature covers the raw body in both cases, so
     * this unwrapping happens after verification and cannot be used to change what was
     * signed.
     */
    private static byte[] unwrap(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        if (!text.startsWith(FORM_PARAMETER)) {
            return body;
        }
        return URLDecoder.decode(text.substring(FORM_PARAMETER.length()), StandardCharsets.UTF_8)
                .getBytes(StandardCharsets.UTF_8);
    }
}
