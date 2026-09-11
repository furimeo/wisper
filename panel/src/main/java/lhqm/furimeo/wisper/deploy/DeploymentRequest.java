package lhqm.furimeo.wisper.deploy;

import java.util.UUID;

/**
 * Everything a caller decides when it asks for a deployment.
 *
 * <p>Four callers produce one of these - the deployments screen, the zip upload, a Git
 * webhook and the rollback button - and they differ in exactly these fields. Passing six
 * loose arguments through {@link StartDeployment} instead would mean every new caller
 * gets to guess which nulls are allowed together; the factories below are the allowed
 * combinations, written once.
 *
 * <p>What is <em>not</em> here is anything the service already decides: the repository,
 * the build preset, the image, the limits. A request that carried a copy of those would
 * be a second version of the truth, and the version that is wrong is always the one that
 * was copied.
 *
 * @param trigger              what set it going, for the deployment list and the audit
 * @param triggeredByAccountId the person, or null for a webhook, which has no person
 *                             behind it - only a signature
 * @param source               where the bytes come from
 * @param revision             the Git ref and commit, empty for an archive
 * @param archivePath          where the panel parked the uploaded zip, for
 *                             {@link DeploymentSource#ARCHIVE} and nothing else
 * @param rolledBackFrom       the deployment being undone, set only by a rollback
 */
public record DeploymentRequest(
        DeploymentTrigger trigger,
        UUID triggeredByAccountId,
        DeploymentSource source,
        GitRevision revision,
        String archivePath,
        UUID rolledBackFrom) {

    public DeploymentRequest {
        if (trigger == null) {
            throw new IllegalArgumentException("A deployment request needs a trigger");
        }
        if (source == null) {
            throw new IllegalArgumentException("A deployment request needs a source");
        }
        revision = revision == null ? GitRevision.unknown() : revision;
        if (source == DeploymentSource.ARCHIVE && (archivePath == null || archivePath.isBlank())) {
            // The same rule as deployment_archive_source_needs_path, checked here so the
            // failure names the mistake instead of arriving as a constraint violation.
            throw new IllegalArgumentException("An archive deployment needs the path the "
                    + "uploaded zip was stored at");
        }
        if (source == DeploymentSource.GIT && !revision.hasRef()) {
            // deployment_git_source_needs_ref. A clone with no ref has nothing to check out.
            throw new IllegalArgumentException("A Git deployment needs a branch or tag");
        }
    }

    /** Somebody pressed deploy on the deployments screen. */
    public static DeploymentRequest manual(UUID accountId, DeploymentSource source,
                                           GitRevision revision) {
        return new DeploymentRequest(DeploymentTrigger.MANUAL, accountId, source, revision,
                null, null);
    }

    /** A Git provider posted a push whose signature checked out. */
    public static DeploymentRequest fromPush(DeploymentSource source, GitRevision revision) {
        return new DeploymentRequest(DeploymentTrigger.GIT_PUSH, null, source, revision,
                null, null);
    }

    /** A customer uploaded a zip, for the site that is not in Git. */
    public static DeploymentRequest fromArchive(UUID accountId, String archivePath) {
        return new DeploymentRequest(DeploymentTrigger.MANUAL, accountId,
                DeploymentSource.ARCHIVE, GitRevision.unknown(), archivePath, null);
    }

    /**
     * Put a previous release back.
     *
     * <p>Everything about what to deploy is copied from the deployment being undone - the
     * same commit, the same archive, the same source - because a rollback that rebuilt
     * from the branch would produce different bytes from the ones the customer is asking
     * to return to. {@link BuildArtifact} sees {@link DeploymentTrigger#ROLLBACK} and
     * publishes without building at all, which is what makes a rollback instant.
     *
     * <p>{@code rolledBackFrom} is the deployment that <em>owns the release directory</em>,
     * not necessarily the row being undone. Rolling back to a row that was itself a
     * rollback would otherwise start a chain that whoever reads the spec has to walk, and
     * a chain with a {@code SET NULL} in it is a chain that breaks.
     */
    public static DeploymentRequest rollbackTo(UUID accountId, Deployment target) {
        UUID releaseOwner = target.rolledBackFromDeploymentId() != null
                ? target.rolledBackFromDeploymentId()
                : target.id();
        return new DeploymentRequest(DeploymentTrigger.ROLLBACK, accountId, target.source(),
                new GitRevision(target.gitRef(), target.commitSha(), target.commitMessage(),
                        target.commitAuthor()),
                target.archivePath(), releaseOwner);
    }

    /** A program calling {@code /api/v1/**} with a scoped token. */
    public static DeploymentRequest fromApi(UUID accountId, DeploymentSource source,
                                            GitRevision revision) {
        return new DeploymentRequest(DeploymentTrigger.API, accountId, source, revision,
                null, null);
    }
}
