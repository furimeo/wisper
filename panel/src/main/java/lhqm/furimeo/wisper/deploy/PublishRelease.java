package lhqm.furimeo.wisper.deploy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.node.PublishNodeSpec;

/**
 * The body of the {@code deploy-publish} job: make this deployment the live one.
 *
 * <h2>There is no publish command</h2>
 *
 * <p>The panel does not tell a node to swap a symlink or to restart a container. It moves
 * {@code is_current} to this row and republishes the node's spec; the spec then names this
 * release, and the node converges to it (design §5.1). That is why publishing and rolling
 * back are the same code path with a different row, and why "what is live" has exactly one
 * answer - the partial unique index on {@code is_current} - rather than one answer here
 * and another on the node.
 *
 * <p>The consequence worth stating: this job succeeding means the panel has recorded the
 * intent and handed it over, not that a customer's browser is already seeing the new
 * bytes. A node that is offline gets the whole spec when it reconnects, which is why
 * {@code PublishNodeSpec} swallows that case rather than failing a deployment over a
 * tunnel that dropped.
 */
@Component
public class PublishRelease {

    private static final Logger log = LoggerFactory.getLogger(PublishRelease.class);

    private final DeploymentRepository deployments;
    private final PublishNodeSpec specs;
    private final PruneOldReleases pruning;
    private final AppendDeploymentLog logs;

    public PublishRelease(DeploymentRepository deployments, PublishNodeSpec specs,
                          PruneOldReleases pruning, AppendDeploymentLog logs) {
        this.deployments = deployments;
        this.specs = specs;
        this.pruning = pruning;
        this.logs = logs;
    }

    /** Runs one publish. Called by db-scheduler; see {@link DeploymentTasks}. */
    @Transactional
    public void run(PublishJob job) {
        UUID deploymentId = job.deploymentId();
        Deployment deployment = deployments.findById(deploymentId).orElse(null);
        if (deployment == null) {
            log.debug("Publish job for deployment {}, which no longer exists", deploymentId);
            return;
        }
        if (!deployment.status().canTransitionTo(DeploymentStatus.PUBLISHING)) {
            // Cancelled while the build was running, or already published by a job that
            // was delivered twice. Both are ordinary and neither is this job's to undo.
            log.debug("Deployment {} is {} and cannot be published", deploymentId,
                    deployment.status());
            return;
        }

        Instant now = Instant.now();
        // The live flag comes off whatever holds it before it goes on this row. The
        // partial unique index is checked per statement, so the two rows cannot both
        // claim to be current even for the instant between the writes.
        deployments.clearCurrent(deployment.serviceId(), now);
        Deployment live = deployments.save(
                deployment.publishing(now).succeeded(now).promotedToCurrent());

        specs.forService(live.serviceId(),
                "deployment #" + live.sequence() + " published");

        logs.note(deploymentId, "Deployment #" + live.sequence() + " is live"
                + (live.releasePath() == null ? "." : " from " + live.releasePath() + "."));
        logs.close(deploymentId);

        // Now, and not before: pruning reads is_current, and until the line above it was
        // still pointing at the release this one replaces.
        pruning.forService(live.serviceId(), List.of());
    }
}
