package lhqm.furimeo.wisper.deploy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GitLab presents a shared secret rather than signing the body, so the interesting cases
 * are the near misses: a token that is a prefix of the real one, one with something
 * appended, one that differs in a single character. All three are what an attacker
 * produces while probing, and all three must be refused identically.
 */
class GitLabWebhookTest {

    private static final String SECRET = "glpat-per-service-token";

    private final GitLabWebhook webhook = new GitLabWebhook();

    private static final String PUSH_PAYLOAD = """
            {
              "object_kind": "push",
              "ref": "refs/heads/production",
              "before": "1111111111111111111111111111111111111111",
              "after": "3333333333333333333333333333333333333333",
              "checkout_sha": "3333333333333333333333333333333333333333",
              "user_name": "Ana Ruiz",
              "total_commits_count": 2,
              "commits": [
                { "id": "2222222222222222222222222222222222222222",
                  "message": "An older commit",
                  "author": { "name": "Someone Else" } },
                { "id": "3333333333333333333333333333333333333333",
                  "message": "Publish the March notes",
                  "author": { "name": "Ana Ruiz" } }
              ]
            }
            """;

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the exact token is accepted")
    void acceptsTheToken() {
        assertThat(webhook.isAuthentic(SECRET, SECRET)).isTrue();
    }

    @Test
    @DisplayName("a forged token is refused, however close it is")
    void refusesAForgedToken() {
        assertThat(webhook.isAuthentic("glpat-per-service-tokeN", SECRET)).isFalse();
        assertThat(webhook.isAuthentic("glpat-per-service-toke", SECRET)).isFalse();
        assertThat(webhook.isAuthentic(SECRET + "x", SECRET)).isFalse();
        assertThat(webhook.isAuthentic(" " + SECRET, SECRET)).isFalse();
        assertThat(webhook.isAuthentic("", SECRET)).isFalse();
        assertThat(webhook.isAuthentic(null, SECRET)).isFalse();
    }

    @Test
    @DisplayName("a blank configured secret accepts nothing rather than everything")
    void aBlankSecretAcceptsNothing() {
        assertThat(webhook.isAuthentic("", "")).isFalse();
        assertThat(webhook.isAuthentic("anything", "")).isFalse();
        assertThat(webhook.isAuthentic("anything", null)).isFalse();
    }

    @Test
    @DisplayName("a push is read, and the head commit is the last one in the list")
    void readsAPush() {
        PushEvent push = webhook.read(bytes(PUSH_PAYLOAD), "Push Hook").orElseThrow();

        assertThat(push.shortRef()).isEqualTo("production");
        assertThat(push.commitSha()).isEqualTo("3333333333333333333333333333333333333333");
        // GitLab orders commits oldest first. Taking the first one shows a deployment list
        // a message from three commits ago.
        assertThat(push.commitMessage()).isEqualTo("Publish the March notes");
        assertThat(push.author()).isEqualTo("Ana Ruiz");
        assertThat(push.isDeployable()).isTrue();
    }

    @Test
    @DisplayName("a tag push deploys too, and carries the tag as its ref")
    void readsATagPush() {
        String tagPush = """
                {
                  "object_kind": "tag_push",
                  "ref": "refs/tags/v2.1.0",
                  "after": "4444444444444444444444444444444444444444",
                  "checkout_sha": "4444444444444444444444444444444444444444",
                  "user_name": "Ana Ruiz",
                  "commits": [
                    { "id": "4444444444444444444444444444444444444444",
                      "message": "Release 2.1.0", "author": { "name": "Ana Ruiz" } }
                  ]
                }
                """;

        PushEvent push = webhook.read(bytes(tagPush), "Tag Push Hook").orElseThrow();

        assertThat(push.shortRef()).isEqualTo("v2.1.0");
        assertThat(push.isDeployable()).isTrue();
    }

    @Test
    @DisplayName("a deleted branch is not deployable")
    void readsABranchDelete() {
        String deleted = """
                {
                  "object_kind": "push",
                  "ref": "refs/heads/spike",
                  "after": "0000000000000000000000000000000000000000",
                  "checkout_sha": null,
                  "user_name": "Ana Ruiz",
                  "total_commits_count": 0,
                  "commits": []
                }
                """;

        PushEvent push = webhook.read(bytes(deleted), "Push Hook").orElseThrow();

        assertThat(push.commitSha()).isEmpty();
        assertThat(push.deleted()).isTrue();
        assertThat(push.isDeployable()).isFalse();
    }

    @Test
    @DisplayName("anything that is not a push produces nothing")
    void ignoresOtherEvents() {
        assertThat(webhook.read(bytes(PUSH_PAYLOAD), "Merge Request Hook")).isEmpty();
        assertThat(webhook.read(bytes(PUSH_PAYLOAD), "Pipeline Hook")).isEmpty();
        assertThat(webhook.read(bytes(PUSH_PAYLOAD), null)).isEmpty();
    }

    @Test
    @DisplayName("a body that is not JSON produces nothing rather than an exception")
    void unreadableBodiesAreIgnored() {
        assertThat(webhook.read(bytes("<html>not json</html>"), "Push Hook")).isEmpty();
        assertThat(webhook.read(new byte[0], "Push Hook")).isEmpty();
    }

    @Test
    @DisplayName("the pusher is credited when the commit carries no author of its own")
    void fallsBackToThePusher() {
        String anonymous = """
                {
                  "object_kind": "push",
                  "ref": "refs/heads/main",
                  "checkout_sha": "5555555555555555555555555555555555555555",
                  "user_name": "Ana Ruiz",
                  "commits": [ { "id": "5555555555555555555555555555555555555555",
                                 "message": "Tidy up" } ]
                }
                """;

        PushEvent push = webhook.read(bytes(anonymous), "Push Hook").orElseThrow();

        assertThat(push.author()).isEqualTo("Ana Ruiz");
    }
}
