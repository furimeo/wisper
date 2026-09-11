package lhqm.furimeo.wisper.deploy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lifecycle as the row actually experiences it.
 *
 * <p>{@link DeploymentStatusTest} proves the transition table; this proves that the record
 * uses it, that the timestamps a customer is shown come out of the right transitions, and
 * that the two node-owned columns survive every move that is not the one allowed to set
 * them.
 */
class DeploymentTest {

    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-03-05T10:00:00Z");

    private static Deployment queued() {
        return Deployment.queued(UUID.randomUUID(), SERVICE, 7, DeploymentTrigger.MANUAL, null,
                DeploymentSource.GIT, GitRevision.ofRef("main"), null, null, T0);
    }

    @Test
    @DisplayName("a new deployment is queued, unassigned and not live")
    void startsQueued() {
        Deployment deployment = queued();

        assertThat(deployment.status()).isEqualTo(DeploymentStatus.QUEUED);
        assertThat(deployment.nodeId()).isNull();
        assertThat(deployment.assignedAt()).isNull();
        assertThat(deployment.startedAt()).isNull();
        assertThat(deployment.finishedAt()).isNull();
        assertThat(deployment.isCurrent()).isFalse();
        assertThat(deployment.queuedAt()).isEqualTo(T0);
        assertThat(deployment.version()).as("null version is what makes the insert an insert")
                .isNull();
    }

    @Test
    @DisplayName("the site path: queued, assigned, building, publishing, succeeded, live")
    void theSitePath() {
        Deployment deployment = queued()
                .assignedTo(NODE, T0.plusSeconds(2))
                .building(T0.plusSeconds(3));

        assertThat(deployment.status()).isEqualTo(DeploymentStatus.BUILDING);
        assertThat(deployment.nodeId()).isEqualTo(NODE);
        assertThat(deployment.assignedAt()).isEqualTo(T0.plusSeconds(2));
        assertThat(deployment.startedAt()).isEqualTo(T0.plusSeconds(3));

        Deployment live = deployment
                .publishing(T0.plusSeconds(60))
                .succeeded(T0.plusSeconds(63))
                .promotedToCurrent();

        assertThat(live.status()).isEqualTo(DeploymentStatus.SUCCEEDED);
        assertThat(live.isCurrent()).isTrue();
        assertThat(live.finishedAt()).isEqualTo(T0.plusSeconds(63));
        // From the build starting, not from the queue: a customer asking how long a
        // deploy took means the build, not how long it waited for a worker.
        assertThat(live.duration()).hasSeconds(60);
    }

    @Test
    @DisplayName("the app path has no build, so publishing is what starts the clock")
    void theAppPathStartsAtPublish() {
        Deployment app = Deployment.queued(UUID.randomUUID(), SERVICE, 1,
                        DeploymentTrigger.MANUAL, null, DeploymentSource.IMAGE,
                        GitRevision.unknown(), null, null, T0)
                .assignedTo(NODE, T0.plusSeconds(1))
                .publishing(T0.plusSeconds(2));

        assertThat(app.status()).isEqualTo(DeploymentStatus.PUBLISHING);
        assertThat(app.startedAt()).isEqualTo(T0.plusSeconds(2));
    }

    @Test
    @DisplayName("skipping a step throws instead of silently rewriting the status")
    void skippingAStepThrows() {
        Deployment deployment = queued();

        assertThatThrownBy(() -> deployment.building(T0))
                .isInstanceOf(IllegalStatusTransition.class);
        assertThatThrownBy(() -> deployment.publishing(T0))
                .isInstanceOf(IllegalStatusTransition.class);
        assertThatThrownBy(() -> deployment.succeeded(T0))
                .isInstanceOf(IllegalStatusTransition.class);
    }

    @Test
    @DisplayName("a finished deployment cannot be moved again")
    void terminalIsTerminal() {
        Deployment failed = queued().failed("the node refused it", T0.plusSeconds(5));

        assertThat(failed.status()).isEqualTo(DeploymentStatus.FAILED);
        assertThat(failed.errorMessage()).isEqualTo("the node refused it");
        assertThatThrownBy(() -> failed.assignedTo(NODE, T0))
                .isInstanceOf(IllegalStatusTransition.class);
        assertThatThrownBy(() -> failed.cancelled("too late", T0))
                .isInstanceOf(IllegalStatusTransition.class);
    }

    @Test
    @DisplayName("a failure with no message still says something")
    void failuresAlwaysCarryASentence() {
        assertThat(queued().failed(null, T0).errorMessage()).isNotBlank();
        assertThat(queued().failed("   ", T0).errorMessage()).isNotBlank();
    }

    @Test
    @DisplayName("only a succeeded deployment can be made live")
    void onlySuccessGoesLive() {
        Deployment building = queued().assignedTo(NODE, T0).building(T0);

        assertThatThrownBy(building::promotedToCurrent)
                .isInstanceOf(IllegalStatusTransition.class);
    }

    @Test
    @DisplayName("retiring the live flag leaves the status alone")
    void retiringKeepsTheHistory() {
        Deployment live = queued().assignedTo(NODE, T0).building(T0)
                .publishing(T0).succeeded(T0.plusSeconds(1)).promotedToCurrent();

        Deployment retired = live.retiredFromCurrent();

        assertThat(retired.isCurrent()).isFalse();
        assertThat(retired.status()).isEqualTo(DeploymentStatus.SUCCEEDED);
        assertThat(retired.isRollbackTarget()).isTrue();
    }

    @Test
    @DisplayName("superseding names the deployment that overtook it")
    void supersededSaysWhy() {
        Deployment overtaken = queued().superseded(9, T0.plusSeconds(1));

        assertThat(overtaken.status()).isEqualTo(DeploymentStatus.SUPERSEDED);
        assertThat(overtaken.errorMessage()).contains("#9");
    }

    @Test
    @DisplayName("build output survives every later transition")
    void buildOutputIsCarriedThrough() {
        Deployment built = queued().assignedTo(NODE, T0).building(T0)
                .withBuildOutput("/var/lib/wisper/sites/s/releases/r", "sha256:abc",
                        "0123456789abcdef0123456789abcdef01234567", "Fix the header\n\nlong body");

        assertThat(built.status()).as("recording output is not a transition")
                .isEqualTo(DeploymentStatus.BUILDING);
        assertThat(built.commitSubject()).isEqualTo("Fix the header");
        assertThat(built.shortCommit()).isEqualTo("0123456");

        Deployment live = built.publishing(T0).succeeded(T0.plusSeconds(1)).promotedToCurrent();

        assertThat(live.releasePath()).isEqualTo("/var/lib/wisper/sites/s/releases/r");
        assertThat(live.imageDigest()).isEqualTo("sha256:abc");
        assertThat(live.commitSha()).isEqualTo("0123456789abcdef0123456789abcdef01234567");
    }

    @Test
    @DisplayName("an empty value in a build result does not erase what was already known")
    void emptyBuildOutputDoesNotErase() {
        Deployment known = queued()
                .withArchiveAt("svc/upload.zip")
                .assignedTo(NODE, T0).building(T0)
                .withBuildOutput("/releases/one", "sha256:one", "aaa", "first");

        Deployment redelivered = known.withBuildOutput("", "", "", "");

        assertThat(redelivered.releasePath()).isEqualTo("/releases/one");
        assertThat(redelivered.imageDigest()).isEqualTo("sha256:one");
        assertThat(redelivered.commitSha()).isEqualTo("aaa");
        assertThat(redelivered.archivePath()).isEqualTo("svc/upload.zip");
    }

    @Test
    @DisplayName("a commit message longer than the column is cut, not rejected")
    void longCommitMessagesAreCut() {
        Deployment deployment = Deployment.queued(UUID.randomUUID(), SERVICE, 1,
                DeploymentTrigger.GIT_PUSH, null, DeploymentSource.GIT,
                new GitRevision("main", "abc", "x".repeat(2_000), "Someone"), null, null, T0);

        assertThat(deployment.commitMessage()).hasSize(500);
    }

    @Test
    @DisplayName("cancelling a queued deployment records who and when")
    void cancellingQueued() {
        Deployment cancelled = queued().cancelled("Cancelled by sam@example.com.",
                T0.plusSeconds(4));

        assertThat(cancelled.status()).isEqualTo(DeploymentStatus.CANCELLED);
        assertThat(cancelled.finishedAt()).isEqualTo(T0.plusSeconds(4));
        assertThat(cancelled.duration()).hasSeconds(4);
        assertThat(cancelled.isCancellable()).isFalse();
    }
}
