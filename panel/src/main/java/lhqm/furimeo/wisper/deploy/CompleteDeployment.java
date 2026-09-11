package lhqm.furimeo.wisper.deploy;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.jobs.JobAlreadyQueued;
import lhqm.furimeo.wisper.jobs.JobQueue;
import lhqm.furimeo.wisper.proto.v1.BuildCompleted;

/**
 * Takes a build's outcome and decides what happens to the deployment.
 *
 * <p>{@code grpc} calls {@link #accept} for a {@code BuildCompleted} frame
 * (panel-ports.md §3), and {@link BuildArtifact} calls the same method when the
 * {@code CommandResult} it was waiting on carries one. Both paths are real and either can
 * arrive first, so this is written to be <strong>idempotent</strong>: the second caller
 * finds a row that is no longer building and returns without touching it.
 *
 * <p>That same check is what makes a cancelled build safe. There is no cancel-build
 * command in the contract - the node's own {@code timeout_seconds} reclaims the slot -
 * so a cancelled deployment's result arrives minutes later and must not resurrect it.
 * A row in {@code CANCELLED} is terminal and this leaves it alone.
 *
 * <p>Nothing here publishes. A successful build records what it produced and enqueues
 * {@code deploy-publish}; the promotion is a separate job precisely so it survives the
 * panel restarting between "the build succeeded" and "the symlink moved".
 */
@Component
public class CompleteDeployment {

    private static final Logger log = LoggerFactory.getLogger(CompleteDeployment.class);

    /**
     * Where a site's releases live on a node.
     *
     * <p>Fixed by design §11.1 - changing it means moving customer data - and documented
     * on the {@code deployment.release_path} column, which is why the panel can compose it
     * from a release id rather than needing the node to spell it out. It is a value the
     * deployment page displays; nothing is ever sent to a node as an absolute path.
     */
    private static final String RELEASES = "/var/lib/wisper/sites/%s/releases/%s";

    private final DeploymentRepository deployments;
    private final PruneOldReleases pruning;
    private final StoreDeploymentArchive archives;
    private final AppendDeploymentLog logs;
    private final JobQueue jobs;

    public CompleteDeployment(DeploymentRepository deployments, PruneOldReleases pruning,
                              StoreDeploymentArchive archives, AppendDeploymentLog logs,
                              JobQueue jobs) {
        this.deployments = deployments;
        this.pruning = pruning;
        this.archives = archives;
        this.logs = logs;
        this.jobs = jobs;
    }

    /**
     * Records what the node built.
     *
     * <p>The commit is taken from the result rather than from the row: a build of a branch
     * resolves to a commit that was not knowable when the deployment was queued, and the
     * resolved one is what the customer needs to see next to it.
     */
    @Transactional
    public void accept(UUID deploymentId, BuildCompleted result) {
        Deployment deployment = deployments.findById(deploymentId).orElse(null);
        if (deployment == null) {
            log.debug("Build result for deployment {}, which no longer exists", deploymentId);
            return;
        }
        if (deployment.status() != DeploymentStatus.BUILDING) {
            log.debug("Ignoring a build result for deployment {}, which is {}", deploymentId,
                    deployment.status());
            return;
        }
        if (!result.getSuccess()) {
            finishAsFailure(deployment, describeFailure(result));
            return;
        }

        Deployment built = deployments.save(deployment.withBuildOutput(
                releasePathOf(deployment, result.getReleaseId()),
                result.getImageDigest(),
                result.getCommit(),
                result.getCommitMessage()));
        logs.note(deploymentId, "Build finished: " + result.getArtifactBytes()
                + " bytes in release " + shortReleaseId(result.getReleaseId()) + ".");

        dropReleasesTheNodeDeleted(built, result.getPrunedReleasesList());
        archives.discard(built.archivePath());
        enqueuePublish(built);
    }

    /**
     * Ends a deployment that will not produce a release: the node refused it, the stream
     * died, the archive would not upload, the build ran past its deadline.
     *
     * <p>Quiet about a row that has already finished. Both the timeout in
     * {@link BuildArtifact} and a late answer from the node can land here for the same
     * deployment, and the first one to arrive is the one that matters.
     */
    @Transactional
    public void failed(UUID deploymentId, String reason) {
        Deployment deployment = deployments.findById(deploymentId).orElse(null);
        if (deployment == null || deployment.status().isTerminal()) {
            return;
        }
        finishAsFailure(deployment, reason);
    }

    private void finishAsFailure(Deployment deployment, String reason) {
        deployments.save(deployment.failed(reason, Instant.now()));
        logs.note(deployment.id(), "Deployment failed: " + reason);
        archives.discard(deployment.archivePath());
        // The browser watching this build is told there will be no more lines, rather
        // than being left with a spinner and a stream that has quietly stopped.
        logs.close(deployment.id());
    }

    private void enqueuePublish(Deployment deployment) {
        try {
            jobs.enqueue(DeploymentTasks.PUBLISH, deployment.id().toString(),
                    new PublishJob(deployment.id()));
        } catch (JobAlreadyQueued alreadyGoing) {
            // Both completion paths reached here inside the same instant. One publish job
            // is exactly the right number; the other caller's row write is identical.
            log.debug("Publish of deployment {} was already queued", deployment.id());
        }
    }

    /**
     * Drops the rows for release directories the node deleted to honour its retention
     * policy. Those releases can no longer be rolled back to, and a rollback button that
     * points at nothing is worse than no button.
     */
    private void dropReleasesTheNodeDeleted(Deployment deployment, List<String> releaseIds) {
        if (releaseIds.isEmpty()) {
            return;
        }
        pruning.forService(deployment.serviceId(), releaseIds);
    }

    private static String releasePathOf(Deployment deployment, String releaseId) {
        String release = releaseId == null || releaseId.isBlank()
                ? deployment.id().toString()
                : releaseId;
        return RELEASES.formatted(deployment.serviceId(), release);
    }

    private static String shortReleaseId(String releaseId) {
        if (releaseId == null || releaseId.length() <= 8) {
            return releaseId == null ? "" : releaseId;
        }
        return releaseId.substring(0, 8);
    }

    /**
     * One sentence a customer can act on, assembled from the three fields the node fills
     * in on a failure. The whole output is in the log they are already looking at; this is
     * what fits next to a red pill.
     */
    private static String describeFailure(BuildCompleted result) {
        StringBuilder message = new StringBuilder("The build failed");
        if (result.getFailedStageValue() != 0) {
            message.append(" during ").append(stageName(result.getFailedStage().name()));
        }
        if (result.getExitCode() != 0) {
            message.append(" with exit code ").append(result.getExitCode());
        }
        message.append('.');
        if (!result.getDetail().isBlank()) {
            message.append(' ').append(result.getDetail());
        }
        return message.toString();
    }

    /** {@code BUILD_STAGE_INSTALL} is not a word anybody says out loud. */
    private static String stageName(String enumName) {
        String tail = enumName.startsWith("BUILD_STAGE_")
                ? enumName.substring("BUILD_STAGE_".length())
                : enumName;
        return tail.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
