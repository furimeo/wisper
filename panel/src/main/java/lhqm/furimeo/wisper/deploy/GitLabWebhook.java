package lhqm.furimeo.wisper.deploy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * GitLab's half of {@code /webhooks/**}: proving a delivery is real, and reading it.
 *
 * <h2>GitLab does not sign, it presents</h2>
 *
 * <p>{@code X-Gitlab-Token} carries the shared secret itself rather than an HMAC of the
 * body. That is weaker than GitHub's scheme in two specific ways, and both are worth
 * naming rather than papering over: the secret travels on every delivery, so anything
 * that sees one delivery has it, and nothing binds the token to the body, so a delivery
 * that is captured can be replayed with a different payload. TLS is what stands between
 * those and reality, which is why the panel is only ever reached over HTTPS.
 *
 * <p>What is still done properly here is the comparison. It is
 * {@link MessageDigest#isEqual} over the bytes, which does not return early - an
 * {@code equals} that stops at the first wrong character leaks the correct prefix, and a
 * secret can be recovered from that one character at a time.
 *
 * <p>The token is per service, so a leaked one can be rotated for one customer without
 * touching anybody else's hooks.
 */
@Component
public class GitLabWebhook {

    /** The header carrying the shared secret. */
    public static final String TOKEN_HEADER = "X-Gitlab-Token";

    /** Which event this delivery is: {@code Push Hook}, {@code Tag Push Hook}, others. */
    public static final String EVENT_HEADER = "X-Gitlab-Event";

    private static final String PUSH = "Push Hook";
    private static final String TAG_PUSH = "Tag Push Hook";

    private final ObjectMapper json = new ObjectMapper();

    /**
     * Whether this delivery presented the service's token.
     *
     * <p>False for a missing header and false for an empty configured secret, so a service
     * whose secret somehow ended up blank accepts nothing rather than everything.
     */
    public boolean isAuthentic(String tokenHeader, String secret) {
        if (tokenHeader == null || secret == null || secret.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(tokenHeader.getBytes(StandardCharsets.UTF_8),
                secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The push this delivery describes, or nothing.
     *
     * <p>Tag pushes count: publishing a site from a tag is a perfectly ordinary release
     * process, and the ref carries which one it was. Everything else - merge requests,
     * pipelines, issues - is empty, because a hook subscribed to more than it needs is a
     * configuration to shrug at rather than an error in somebody's delivery log.
     */
    public Optional<PushEvent> read(byte[] body, String eventHeader) {
        String event = eventHeader == null ? "" : eventHeader.strip();
        if (!PUSH.equalsIgnoreCase(event) && !TAG_PUSH.equalsIgnoreCase(event)) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException unreadable) {
            return Optional.empty();
        }
        if (!root.isObject()) {
            // An empty body parses to a missing node rather than throwing, and reading
            // fields off it would produce a push with no ref at all.
            return Optional.empty();
        }
        JsonNode head = lastCommit(root);
        String checkout = root.path("checkout_sha").asString("");
        return Optional.of(new PushEvent(
                root.path("ref").asString(""),
                // checkout_sha is null on a delete and equal to `after` otherwise. Taking
                // it first means a payload with a stale `after` cannot pin the build to a
                // commit GitLab did not check out. A deleted branch leaves both empty or
                // all-zero, which PushEvent reads as "nothing to deploy".
                checkout.isEmpty() ? root.path("after").asString("") : checkout,
                head.path("message").asString(""),
                authorOf(root, head),
                false));
    }

    /**
     * The commit at the tip. GitLab orders {@code commits} oldest first, so the head is
     * the last entry and not the first - the mistake that shows a deployment list a
     * message from three commits ago.
     */
    private static JsonNode lastCommit(JsonNode root) {
        JsonNode commits = root.path("commits");
        if (!commits.isArray() || commits.isEmpty()) {
            return root.path("commits").path(0);
        }
        return commits.get(commits.size() - 1);
    }

    /**
     * The commit's own author if the payload carries one, otherwise the person who pushed.
     * A push of somebody else's commit is credited to whoever wrote it, which is what a
     * deployment list is being asked.
     */
    private static String authorOf(JsonNode root, JsonNode head) {
        String commitAuthor = head.path("author").path("name").asString("");
        return commitAuthor.isEmpty() ? root.path("user_name").asString("") : commitAuthor;
    }
}
