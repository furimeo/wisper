package lhqm.furimeo.wisper.deploy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lhqm.furimeo.wisper.jobs.JobQueue;
import lhqm.furimeo.wisper.node.NodeConnections;
import lhqm.furimeo.wisper.placement.ChooseNode;
import lhqm.furimeo.wisper.proto.v1.CommandResult;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;
import lhqm.furimeo.wisper.proto.v1.StartBuild;
import lhqm.furimeo.wisper.service.Service;
import lhqm.furimeo.wisper.service.ServiceRepository;

/**
 * The body of the {@code deploy-run} job: choose a node and hand it the work.
 *
 * <h2>Two phases, and the commit between them is the point</h2>
 *
 * <p>The row is moved to {@link DeploymentStatus#BUILDING} and <strong>committed</strong>
 * before {@code StartBuild} goes anywhere. A node on the same machine can answer in
 * single-digit milliseconds, and if the answer arrived while the transaction was still
 * open, {@link CompleteDeployment} would read a row that still said {@code QUEUED} and
 * refuse its own build result. So the database work runs in an explicit
 * {@link TransactionTemplate} and the network work runs after it returns - not in one
 * {@code @Transactional} method with a send at the end.
 *
 * <h2>What does not get built</h2>
 *
 * <p>An app has nothing to build: {@code service_app_needs_image} means it already has an
 * image, and no {@code build_preset} in the schema produces one. A rollback has nothing to
 * build either - that is the whole reason it is instant (design §5.5): the release
 * directory is still on the node and publishing points the symlink back at it. Both go
 * straight to {@code deploy-publish}.
 *
 * <p>The job returns as soon as the command is on the wire. The build then takes minutes
 * on the node, and no worker thread is sitting in it; the result comes back through
 * {@link CompleteDeployment}, from whichever of the two paths reaches it first - the
 * {@code CommandResult} this call is waiting on, or the {@code BuildCompleted} frame
 * {@code grpc} routes there directly.
 */
@Component
public class BuildArtifact {

    private static final Logger log = LoggerFactory.getLogger(BuildArtifact.class);

    /**
     * How much longer than the build's own timeout the panel waits.
     *
     * <p>The node is meant to stop the build itself and report a failure. This is the
     * backstop for a node that died instead of answering, and it has to be later than the
     * node's own deadline or the panel would give up on builds that were about to succeed.
     */
    private static final long PANEL_TIMEOUT_SLACK_SECONDS = 60;

    private final TransactionTemplate transactions;
    private final DeploymentRepository deployments;
    private final ServiceRepository services;
    private final ChooseNode chooseNode;
    private final NodeConnections nodes;
    private final JobQueue jobs;
    private final ComposeStartBuild startBuilds;
    private final PushDeploymentArchive archives;
    private final CompleteDeployment completions;
    private final AppendDeploymentLog logs;
    private final DeploySettings settings;

    public BuildArtifact(PlatformTransactionManager transactionManager,
                         DeploymentRepository deployments, ServiceRepository services,
                         ChooseNode chooseNode, NodeConnections nodes, JobQueue jobs,
                         ComposeStartBuild startBuilds, PushDeploymentArchive archives,
                         CompleteDeployment completions, AppendDeploymentLog logs,
                         DeploySettings settings) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.deployments = deployments;
        this.services = services;
        this.chooseNode = chooseNode;
        this.nodes = nodes;
        this.jobs = jobs;
        this.startBuilds = startBuilds;
        this.archives = archives;
        this.completions = completions;
        this.logs = logs;
        this.settings = settings;
    }

    /** Runs one queued deployment. Called by db-scheduler; see {@link DeploymentTasks}. */
    public void run(DeploymentJob job) {
        Assignment assignment = transactions.execute(status -> assign(job.deploymentId()));
        if (assignment == null || !assignment.needsBuild()) {
            return;
        }
        try {
            dispatch(assignment);
        } catch (RuntimeException failed) {
            // Anything from here - the node's file stream gone, an archive that no longer
            // reads, a plan with no command - is this deployment's failure and nobody
            // else's. Recording it beats letting db-scheduler retry a job whose row is
            // already past QUEUED and would be refused on the next attempt anyway.
            log.warn("Deployment {} could not be handed to node {}: {}",
                    assignment.deployment().id(), assignment.deployment().nodeId(),
                    failed.toString());
            completions.failed(assignment.deployment().id(), reasonOf(failed));
        }
    }

    /**
     * Picks a node, moves the row and decides whether there is anything to build.
     *
     * <p>Returns null when there is nothing to do: the deployment was cancelled or
     * superseded while it sat in the queue, or no node would take it - all three of which
     * are ordinary outcomes rather than job failures.
     */
    private Assignment assign(UUID deploymentId) {
        Deployment deployment = deployments.findById(deploymentId).orElse(null);
        if (deployment == null) {
            log.debug("Deployment {} is gone; nothing to run", deploymentId);
            return null;
        }
        if (deployment.status() != DeploymentStatus.QUEUED) {
            // Cancelled, superseded, or already picked up. Not an error: this is exactly
            // what the status check is here to catch.
            log.debug("Deployment {} is {} and no longer queued", deploymentId,
                    deployment.status());
            return null;
        }
        Service service = services.findById(deployment.serviceId()).orElse(null);
        if (service == null) {
            String reason = "The service was deleted before this deployment started.";
            deployments.save(deployment.failed(reason, Instant.now()));
            logs.note(deploymentId, reason);
            logs.close(deploymentId);
            return null;
        }

        UUID nodeId;
        try {
            nodeId = chooseNode.forService(service.id());
        } catch (RuntimeException noRoom) {
            String reason = reasonOf(noRoom);
            logs.note(deploymentId, "No node could take this deployment: " + reason);
            deployments.save(deployment.failed(reason, Instant.now()));
            logs.close(deploymentId);
            return null;
        }

        Instant now = Instant.now();
        boolean needsBuild = needsBuild(deployment);
        Deployment moved = needsBuild
                // Assigned and building are one write: the intermediate state is invisible
                // inside a transaction anyway, and both transitions are still checked by
                // the state machine on the way through.
                ? deployments.save(deployment.assignedTo(nodeId, now).building(now))
                : deployments.save(deployment.assignedTo(nodeId, now));

        logs.note(deploymentId, needsBuild
                ? "Building on node " + nodeId + "."
                : "Publishing on node " + nodeId + "; nothing to build.");

        if (!needsBuild) {
            jobs.enqueue(DeploymentTasks.PUBLISH, deploymentId.toString(),
                    new PublishJob(deploymentId));
        }
        return new Assignment(moved, service, needsBuild);
    }

    /** Pushes the archive if there is one, then puts {@code StartBuild} on the stream. */
    private void dispatch(Assignment assignment) {
        Deployment deployment = assignment.deployment();
        UUID nodeId = deployment.nodeId();
        String uploadedSha = null;
        if (deployment.source() == DeploymentSource.ARCHIVE) {
            logs.note(deployment.id(), "Uploading the archive to the node.");
            // Synchronous, on this worker thread. A 200 MB zip over a tunnel takes a
            // while, and holding one of db-scheduler's threads for it is the honest cost
            // of the panel being the only route the bytes can take.
            uploadedSha = archives.toNode(nodeId, deployment.id(),
                    deployment.archivePath()).sha256();
        }

        StartBuild command = startBuilds.forDeployment(deployment, assignment.service(),
                workloadIdFor(assignment.service()), uploadedSha, useCache(deployment));

        nodes.call(nodeId, PanelMessage.newBuilder().setStartBuild(command))
                .orTimeout(settings.buildTimeoutSeconds() + PANEL_TIMEOUT_SLACK_SECONDS,
                        TimeUnit.SECONDS)
                .whenComplete((result, error) -> settle(deployment.id(), result, error));
    }

    /** Turns whatever came back from the node into one call on {@link CompleteDeployment}. */
    private void settle(UUID deploymentId, CommandResult result, Throwable error) {
        if (error != null) {
            completions.failed(deploymentId, "The node did not report a result: "
                    + reasonOf(error));
            return;
        }
        if (result.hasBuild()) {
            // The same entry point grpc uses for a BuildCompleted frame. Whichever
            // arrives first wins; the second finds a terminal row and does nothing.
            completions.accept(deploymentId, result.getBuild());
            return;
        }
        completions.failed(deploymentId, result.getOk()
                ? "The node acknowledged the build but reported no result."
                : blankToDefault(result.getDetail(), "The node refused the build."));
    }

    /**
     * Whether a node has to build anything at all.
     *
     * <p>A rollback never does: the release is already on the node and publishing points
     * at it again, which is what makes it instant. An image deployment never does either.
     */
    private static boolean needsBuild(Deployment deployment) {
        return deployment.trigger() != DeploymentTrigger.ROLLBACK
                && deployment.source().needsBuild();
    }

    /**
     * Reuse the workspace unless the last attempt failed.
     *
     * <p>"Clear cache and deploy" is a button on other platforms and a column this schema
     * does not have. Deriving it costs nothing and is right nearly always: a build that
     * succeeded leaves a cache worth keeping, and the one thing people press that button
     * for is the build after a failure that made no sense.
     */
    private boolean useCache(Deployment deployment) {
        List<Deployment> recent = deployments.findRecent(deployment.serviceId(), 2);
        for (Deployment candidate : recent) {
            if (!candidate.id().equals(deployment.id())) {
                return candidate.status() != DeploymentStatus.FAILED;
            }
        }
        return true;
    }

    /**
     * The id the node knows this service's workload by.
     *
     * <p>The service id. A workload id is stable for the life of the service - container
     * names, volume directories and release trees all hang off it - and the service's own
     * id is the only value that has that property and is known to both sides without a
     * lookup. It must agree with what {@code placement} puts in the spec.
     */
    private static String workloadIdFor(Service service) {
        return service.id().toString();
    }

    private static String reasonOf(Throwable failure) {
        String message = failure.getMessage();
        return blankToDefault(message, failure.getClass().getSimpleName());
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** What {@link #assign} decided, carried out of the transaction it decided it in. */
    private record Assignment(Deployment deployment, Service service, boolean needsBuild) {
    }
}
