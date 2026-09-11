package lhqm.furimeo.wisper.deploy;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.jobs.JobQueue;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Stops a deployment that has not finished.
 *
 * <h2>What "cancel" honestly means at each stage</h2>
 *
 * <ul>
 * <li><strong>Queued.</strong> Nothing has happened. The row goes to
 *     {@link DeploymentStatus#CANCELLED} and the job is removed from
 *     {@code scheduled_tasks}. Complete in every sense.</li>
 * <li><strong>Assigned or building.</strong> The build is running on a node, and there is
 *     no cancel-build command in the contract to send it - {@code PanelMessage} carries
 *     none, deliberately, because the panel publishes state and does not reach past it.
 *     So the row is marked cancelled now, the customer stops waiting now, and the node's
 *     own {@code timeout_seconds} reclaims the slot. When the result eventually arrives,
 *     {@link CompleteDeployment} finds a terminal row and discards it, so a cancelled
 *     build can never publish itself ten minutes later.</li>
 * <li><strong>Publishing.</strong> Refused. The spec is already out and the node is
 *     converging to it; telling a customer it was cancelled would be a claim about the
 *     world the panel cannot make good on. The honest answer is to wait and then roll
 *     back.</li>
 * </ul>
 */
@Component
public class CancelDeployment {

    private final DeploymentRepository deployments;
    private final StoreDeploymentArchive archives;
    private final AppendDeploymentLog logs;
    private final JobQueue jobs;
    private final AuditTrail audit;

    public CancelDeployment(DeploymentRepository deployments, StoreDeploymentArchive archives,
                            AppendDeploymentLog logs, JobQueue jobs, AuditTrail audit) {
        this.deployments = deployments;
        this.archives = archives;
        this.logs = logs;
        this.jobs = jobs;
        this.audit = audit;
    }

    /**
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException if that deployment is not this service's
     * @throws RequestRejected   if it has finished, or is already being published
     */
    @Transactional
    public Deployment cancel(AuditActor actor, Membership membership, UUID serviceId,
                             UUID deploymentId) {
        membership.requireWrite("deployment.cancel");

        Deployment deployment = deployments.findById(deploymentId)
                .filter(candidate -> candidate.serviceId().equals(serviceId))
                .orElseThrow(() -> NotFoundException.of("deployment", deploymentId));
        if (!deployment.isCancellable()) {
            throw new RequestRejected(null, refusalFor(deployment));
        }

        boolean hadStarted = deployment.status() != DeploymentStatus.QUEUED;
        Deployment cancelled = deployments.save(deployment.cancelled(
                "Cancelled by " + actor.label() + ".", Instant.now()));

        // False when a worker already holds the job, which is exactly the case below:
        // BuildArtifact re-reads the row and stops when it finds it no longer queued.
        jobs.cancel(DeploymentTasks.RUN, deploymentId.toString());
        archives.discard(cancelled.archivePath());

        logs.note(deploymentId, hadStarted
                ? "Cancelled. The node will stop this build when it reaches its own timeout, "
                        + "and its result will be discarded."
                : "Cancelled before it started.");
        logs.close(deploymentId);

        audit.record(AuditEntry.succeeded(actor, "deployment.cancel",
                AuditTarget.of("service", serviceId, "#" + cancelled.sequence()),
                membership.organizationId(),
                "Deployment #" + cancelled.sequence() + " cancelled while "
                        + deployment.status().label().toLowerCase(Locale.ROOT)));
        return cancelled;
    }

    private static String refusalFor(Deployment deployment) {
        if (deployment.status() == DeploymentStatus.PUBLISHING) {
            return "Deployment #" + deployment.sequence() + " is already going live. Wait for it "
                    + "to finish, then roll back.";
        }
        return "Deployment #" + deployment.sequence() + " has already "
                + deployment.status().label().toLowerCase(Locale.ROOT) + ".";
    }
}
