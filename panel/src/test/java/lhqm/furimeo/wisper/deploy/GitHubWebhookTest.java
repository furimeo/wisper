package lhqm.furimeo.wisper.deploy;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The only unauthenticated write in the panel, so the cases below are the ones an attacker
 * would try rather than the ones a happy path needs.
 *
 * <p>The digest is checked against a published vector first. A signature verifier tested
 * only against its own output is verifying that it is self-consistent, which a wrong
 * algorithm also is.
 */
class GitHubWebhookTest {

    private static final String SECRET = "s3cret-per-service";

    private final GitHubWebhook webhook = new GitHubWebhook();

    private static final String PUSH_PAYLOAD = """
            {
              "ref": "refs/heads/main",
              "before": "1111111111111111111111111111111111111111",
              "after": "2222222222222222222222222222222222222222",
              "deleted": false,
              "head_commit": {
                "id": "2222222222222222222222222222222222222222",
                "message": "Fix the footer\\n\\nIt was two pixels out.",
                "author": { "name": "Sam Patel", "email": "sam@example.com" }
              },
              "repository": { "full_name": "acme/site" }
            }
            """;

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** An HMAC written independently of the production one, so the two can disagree. */
    private static String hmacHex(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String header(byte[] body, String secret) {
        return "sha256=" + hmacHex(body, secret);
    }

    @Test
    @DisplayName("the digest matches RFC 4231's published HMAC-SHA256 vector")
    void matchesAPublishedVector() {
        byte[] data = bytes("what do ya want for nothing?");

        assertThat(HexFormat.of().formatHex(webhook.sign(data, "Jefe")))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    @DisplayName("a genuine delivery is accepted")
    void acceptsAGenuineDelivery() {
        byte[] body = bytes(PUSH_PAYLOAD);

        assertThat(webhook.isSigned(body, header(body, SECRET), SECRET)).isTrue();
    }

    @Test
    @DisplayName("a signature computed with the wrong secret is refused")
    void refusesAForgedSignature() {
        byte[] body = bytes(PUSH_PAYLOAD);
        // What an attacker who has the payload but not the secret can produce.
        String forged = header(body, "s3cret-per-servicf");

        assertThat(webhook.isSigned(body, forged, SECRET)).isFalse();
    }

    @Test
    @DisplayName("a signature made up out of the right number of hex characters is refused")
    void refusesAnInventedSignature() {
        byte[] body = bytes(PUSH_PAYLOAD);

        assertThat(webhook.isSigned(body, "sha256=" + "ab".repeat(32), SECRET)).isFalse();
        assertThat(webhook.isSigned(body, "sha256=" + "00".repeat(32), SECRET)).isFalse();
    }

    @Test
    @DisplayName("a body altered after it was signed is refused")
    void refusesATamperedBody() {
        byte[] original = bytes(PUSH_PAYLOAD);
        String signature = header(original, SECRET);
        byte[] tampered = bytes(PUSH_PAYLOAD.replace("refs/heads/main", "refs/heads/evil"));

        assertThat(webhook.isSigned(tampered, signature, SECRET)).isFalse();
    }

    @Test
    @DisplayName("a missing, empty or wrongly-prefixed header is refused")
    void refusesAMalformedHeader() {
        byte[] body = bytes(PUSH_PAYLOAD);
        String correct = hmacHex(body, SECRET);

        assertThat(webhook.isSigned(body, null, SECRET)).isFalse();
        assertThat(webhook.isSigned(body, "", SECRET)).isFalse();
        // The bare digest with no scheme, and the SHA-1 header GitHub still sends.
        assertThat(webhook.isSigned(body, correct, SECRET)).isFalse();
        assertThat(webhook.isSigned(body, "sha1=" + correct, SECRET)).isFalse();
        assertThat(webhook.isSigned(body, "sha256=", SECRET)).isFalse();
        assertThat(webhook.isSigned(body, "sha256=not-hexadecimal", SECRET)).isFalse();
        // Truncated: the right prefix of the right answer must not pass.
        assertThat(webhook.isSigned(body, "sha256=" + correct.substring(0, 32), SECRET))
                .isFalse();
    }

    @Test
    @DisplayName("a blank configured secret accepts nothing rather than everything")
    void aBlankSecretAcceptsNothing() {
        byte[] body = bytes(PUSH_PAYLOAD);
        String genuine = header(body, SECRET);

        // An empty key cannot even be used to compute an HMAC, so the check has to refuse
        // before it tries - a service whose secret somehow ended up blank must accept
        // nothing rather than throw on every delivery.
        assertThat(webhook.isSigned(body, genuine, "")).isFalse();
        assertThat(webhook.isSigned(body, genuine, null)).isFalse();
        assertThat(webhook.isSigned(body, "sha256=" + "ff".repeat(32), "")).isFalse();
    }

    @Test
    @DisplayName("whitespace around the header does not change the answer")
    void toleratesWhitespace() {
        byte[] body = bytes(PUSH_PAYLOAD);

        assertThat(webhook.isSigned(body, "  " + header(body, SECRET) + "\n", SECRET)).isTrue();
    }

    @Test
    @DisplayName("a push is read into a ref, a commit, a subject and an author")
    void readsAPush() {
        Optional<PushEvent> parsed = webhook.read(bytes(PUSH_PAYLOAD), "push");

        assertThat(parsed).isPresent();
        PushEvent push = parsed.orElseThrow();
        assertThat(push.ref()).isEqualTo("refs/heads/main");
        assertThat(push.shortRef()).isEqualTo("main");
        assertThat(push.commitSha()).isEqualTo("2222222222222222222222222222222222222222");
        assertThat(push.commitMessage()).startsWith("Fix the footer");
        assertThat(push.author()).isEqualTo("Sam Patel");
        assertThat(push.isDeployable()).isTrue();
    }

    @Test
    @DisplayName("a ping is not a push and produces nothing")
    void ignoresAPing() {
        assertThat(webhook.read(bytes("{\"zen\":\"Keep it logically awesome.\"}"), "ping"))
                .isEmpty();
        assertThat(webhook.read(bytes(PUSH_PAYLOAD), "pull_request")).isEmpty();
        assertThat(webhook.read(bytes(PUSH_PAYLOAD), null)).isEmpty();
    }

    @Test
    @DisplayName("a deleted branch is read, and is not deployable")
    void readsABranchDelete() {
        String deleted = """
                {
                  "ref": "refs/heads/old-feature",
                  "after": "0000000000000000000000000000000000000000",
                  "deleted": true,
                  "head_commit": null
                }
                """;

        PushEvent push = webhook.read(bytes(deleted), "push").orElseThrow();

        assertThat(push.deleted()).isTrue();
        assertThat(push.isDeployable()).isFalse();
    }

    @Test
    @DisplayName("a form-encoded delivery carries the same payload")
    void readsAFormEncodedDelivery() {
        String form = "payload="
                + URLEncoder.encode(PUSH_PAYLOAD, StandardCharsets.UTF_8);

        PushEvent push = webhook.read(bytes(form), "push").orElseThrow();

        assertThat(push.shortRef()).isEqualTo("main");
        assertThat(push.commitSha()).isEqualTo("2222222222222222222222222222222222222222");
    }

    @Test
    @DisplayName("a body that is not JSON produces nothing rather than an exception")
    void unreadableBodiesAreIgnored() {
        assertThat(webhook.read(bytes("<html>not json</html>"), "push")).isEmpty();
        assertThat(webhook.read(new byte[0], "push")).isEmpty();
    }
}
