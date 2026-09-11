package lhqm.furimeo.wisper.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provider-independent half of a webhook: which pushes reach which services.
 *
 * <p>The branch filter is the rule that decides whether a feature branch can publish
 * itself over production, so it is tested here rather than twice inside the two provider
 * tests.
 */
class PushEventTest {

    private static PushEvent on(String ref) {
        return new PushEvent(ref, "abc123", "A change", "Sam", false);
    }

    @Test
    @DisplayName("a branch ref loses its refs/heads/ prefix")
    void shortensABranch() {
        assertThat(on("refs/heads/main").shortRef()).isEqualTo("main");
        assertThat(on("refs/heads/feature/new-nav").shortRef()).isEqualTo("feature/new-nav");
    }

    @Test
    @DisplayName("a tag ref loses its refs/tags/ prefix")
    void shortensATag() {
        assertThat(on("refs/tags/v1.2.3").shortRef()).isEqualTo("v1.2.3");
    }

    @Test
    @DisplayName("anything else is left alone rather than guessed at")
    void leavesUnknownRefsAlone() {
        assertThat(on("refs/merge-requests/9/head").shortRef())
                .isEqualTo("refs/merge-requests/9/head");
    }

    @Test
    @DisplayName("a service with no branch configured takes any push")
    void noBranchTakesAnything() {
        assertThat(on("refs/heads/main").matches(null)).isTrue();
        assertThat(on("refs/heads/anything").matches("")).isTrue();
        assertThat(on("refs/heads/anything").matches("   ")).isTrue();
    }

    @Test
    @DisplayName("a service with a branch configured takes only that one")
    void aConfiguredBranchIsExact() {
        PushEvent push = on("refs/heads/main");

        assertThat(push.matches("main")).isTrue();
        assertThat(push.matches(" main ")).as("a stray space in the settings field")
                .isTrue();
        assertThat(push.matches("Main")).isFalse();
        assertThat(push.matches("develop")).isFalse();
        assertThat(push.matches("refs/heads/main")).isFalse();
    }

    @Test
    @DisplayName("an all-zero commit is a deletion, whatever the payload's own flag said")
    void zeroShaMeansDeleted() {
        PushEvent push = new PushEvent("refs/heads/gone",
                "0000000000000000000000000000000000000000", "", "", false);

        assertThat(push.commitSha()).isEmpty();
        assertThat(push.deleted()).isTrue();
        assertThat(push.isDeployable()).isFalse();
    }

    @Test
    @DisplayName("a push with no ref at all is not deployable")
    void aRefIsRequired() {
        assertThat(new PushEvent("", "abc", "", "", false).isDeployable()).isFalse();
        assertThat(new PushEvent(null, "abc", "", "", false).isDeployable()).isFalse();
    }

    @Test
    @DisplayName("the revision handed to a deployment carries the short ref, not the full one")
    void revisionUsesTheShortRef() {
        GitRevision revision = on("refs/heads/main").revision();

        assertThat(revision.ref()).isEqualTo("main");
        assertThat(revision.commitSha()).isEqualTo("abc123");
        assertThat(revision.isPinned()).isTrue();
        assertThat(revision.hasRef()).isTrue();
    }
}
