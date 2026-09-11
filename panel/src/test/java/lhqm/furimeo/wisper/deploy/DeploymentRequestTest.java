package lhqm.furimeo.wisper.deploy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The combinations a caller is allowed to ask for, and the one that would otherwise
 * produce a chain nobody can follow.
 */
class DeploymentRequestTest {

    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-03-05T10:00:00Z");

    @Test
    @DisplayName("the two CHECK constraints are enforced before anything is written")
    void refusesWhatTheDatabaseWould() {
        // deployment_archive_source_needs_path
        assertThatThrownBy(() -> new DeploymentRequest(DeploymentTrigger.MANUAL, ACCOUNT,
                DeploymentSource.ARCHIVE, GitRevision.unknown(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        // deployment_git_source_needs_ref
        assertThatThrownBy(() -> new DeploymentRequest(DeploymentTrigger.MANUAL, ACCOUNT,
                DeploymentSource.GIT, GitRevision.unknown(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a webhook request carries no account, because there is no person behind it")
    void aPushHasNoAccount() {
        DeploymentRequest request = DeploymentRequest.fromPush(DeploymentSource.GIT,
                new GitRevision("main", "abc", "Tidy up", "Sam"));

        assertThat(request.trigger()).isEqualTo(DeploymentTrigger.GIT_PUSH);
        assertThat(request.triggeredByAccountId()).isNull();
    }

    @Test
    @DisplayName("rolling back copies what to deploy from the release being restored")
    void rollbackCopiesTheRelease() {
        Deployment target = succeeded(11, DeploymentSource.GIT, null);

        DeploymentRequest request = DeploymentRequest.rollbackTo(ACCOUNT, target);

        assertThat(request.trigger()).isEqualTo(DeploymentTrigger.ROLLBACK);
        assertThat(request.source()).isEqualTo(DeploymentSource.GIT);
        assertThat(request.revision().commitSha()).isEqualTo(target.commitSha());
        assertThat(request.rolledBackFrom()).isEqualTo(target.id());
    }

    @Test
    @DisplayName("rolling back to a rollback points at the release, not at the pointer")
    void rollbackDoesNotChain() {
        UUID originalRelease = UUID.randomUUID();
        Deployment previousRollback = succeeded(12, DeploymentSource.GIT, originalRelease);

        DeploymentRequest request = DeploymentRequest.rollbackTo(ACCOUNT, previousRollback);

        // Following a chain would work until one link was pruned and its SET NULL turned
        // the whole thing into a spec naming nothing.
        assertThat(request.rolledBackFrom()).isEqualTo(originalRelease);
    }

    @Test
    @DisplayName("an archive rollback keeps the archive path the CHECK insists on")
    void archiveRollbackKeepsThePath() {
        Deployment target = Deployment.queued(UUID.randomUUID(), SERVICE, 4,
                DeploymentTrigger.MANUAL, ACCOUNT, DeploymentSource.ARCHIVE,
                GitRevision.unknown(), "svc/upload.zip", null, T0);

        DeploymentRequest request = DeploymentRequest.rollbackTo(ACCOUNT, target);

        assertThat(request.source()).isEqualTo(DeploymentSource.ARCHIVE);
        assertThat(request.archivePath()).isEqualTo("svc/upload.zip");
    }

    @Test
    @DisplayName("the source follows the service, not the caller")
    void sourceFollowsTheKind() {
        assertThat(DeploymentSource.forKind(lhqm.furimeo.wisper.service.ServiceKind.APP, true))
                .isEqualTo(DeploymentSource.IMAGE);
        assertThat(DeploymentSource.forKind(lhqm.furimeo.wisper.service.ServiceKind.SITE, true))
                .isEqualTo(DeploymentSource.GIT);
        assertThat(DeploymentSource.forKind(lhqm.furimeo.wisper.service.ServiceKind.SITE, false))
                .isEqualTo(DeploymentSource.ARCHIVE);
        assertThat(DeploymentSource.IMAGE.needsBuild()).isFalse();
        assertThat(DeploymentSource.GIT.needsBuild()).isTrue();
        assertThat(DeploymentSource.ARCHIVE.needsBuild()).isTrue();
    }

    private static Deployment succeeded(long sequence, DeploymentSource source,
                                        UUID rolledBackFrom) {
        UUID node = UUID.randomUUID();
        return Deployment.queued(UUID.randomUUID(), SERVICE, sequence, DeploymentTrigger.MANUAL,
                        ACCOUNT, source,
                        new GitRevision("main", "abcdef0123456789", "A change", "Sam"), null,
                        rolledBackFrom, T0)
                .assignedTo(node, T0)
                .building(T0)
                .withBuildOutput("/releases/" + sequence, "sha256:" + sequence, null, null)
                .publishing(T0)
                .succeeded(T0.plusSeconds(10));
    }
}
