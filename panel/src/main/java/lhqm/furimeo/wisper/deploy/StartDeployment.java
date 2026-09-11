package lhqm.furimeo.wisper.deploy;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.jobs.JobQueue;
import lhqm.furimeo.wisper.org.QuotaGuard;
import lhqm.furimeo.wisper.org.QuotaResource;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.service.Service;
import lhqm.furimeo.wisper.service.ServiceKind;
import lhqm.furimeo.wisper.service.ServiceRepository;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Accepts a request to deploy, writes the row and puts the job on the queue.
 *
 * <p>This is the only door into the deployment pipeline. The screen, the zip upload, both
 * Git webhooks and the rollback button all come through here, which is why the quota
 * check and the audit entry are here and not in four controllers - four copies of a
 * limit is three chances to omit it.
 *
 * <p>Everything happens in one transaction, including the enqueue. That is the whole
 * reason the queue is PostgreSQL: a job for a deployment row that was rolled back, or a
 * row with no job, cannot occur (panel-configuration.md).
 *
 * <h2>Superseding</h2>
 *
 * <p>A push while an earlier push is still waiting makes the earlier one pointless:
 * building a commit that a later commit has already replaced spends a node's minutes on
 * something nobody will ever see. So every deployment still in {@code QUEUED} for this
 * service is marked {@link DeploymentStatus#SUPERSEDED} and its job cancelled. Anything
 * already {@code ASSIGNED} or further is left alone - it is on a node, and cancelling it
 * is {@link CancelDeployment}'s job, not a side effect of somebody else pushing.
 */
@Component
public class StartDeployment {

    private final ServiceRepository services;
    private final DeploymentRepository deployments;
    private final NextDeploymentSequence sequences;
    private final QuotaGuard quotas;
    private final JobQueue jobs;
    private final AppendDeploymentLog logs;
    private final AuditTrail audit;

    public StartDeployment(ServiceRepository services, DeploymentRepository deployments,
                           NextDeploymentSequence sequences, QuotaGuard quotas, JobQueue jobs,
                           AppendDeploymentLog logs, AuditTrail audit) {
        this.services = services;
        this.deployments = deployments;
        this.sequences = sequences;
        this.quotas = quotas;
        this.jobs = jobs;
        this.logs = logs;
        this.audit = audit;
    }

    /**
     * Queues a deployment of {@code serviceId}.
     *
     * <p>Authorization is the caller's: a controller has already resolved a
     * {@link lhqm.furimeo.wisper.org.Membership} and called {@code requireWrite}, and a
     * webhook has already verified a signature against the service's own secret. What
     * this method insists on is that the service really is in {@code organizationId},
     * which is what stops a caller quoting one tenant's id while naming another's
     * service.
     *
     * @throws NotFoundException if there is no such service in that organization
     * @throws RequestRejected   if the service cannot be deployed the way it was asked
     * @throws lhqm.furimeo.wisper.org.QuotaExceeded if the organization is out of
     *         deployments for the day, or is suspended
     */
    @Transactional
    public Deployment start(AuditActor actor, UUID organizationId, UUID serviceId,
                            DeploymentRequest request) {
        Service service = services.findOwnedBy(serviceId, organizationId)
                .orElseThrow(() -> NotFoundException.of("service", serviceId));
        checkDeployable(service, request);

        quotas.require(organizationId, QuotaResource.DEPLOYMENTS_PER_DAY, 1);

        Instant now = Instant.now();
        long sequence = sequences.forService(service.id());
        Deployment deployment = deployments.save(Deployment.queued(UUID.randomUUID(),
                service.id(), sequence, request.trigger(), request.triggeredByAccountId(),
                request.source(), request.revision(), request.archivePath(),
                request.rolledBackFrom(), now));

        supersedeQueuedBefore(service.id(), sequence, now);
        jobs.enqueue(DeploymentTasks.RUN, deployment.id().toString(),
                new DeploymentJob(deployment.id()));

        logs.note(deployment.id(), "Deployment #" + sequence + " queued ("
                + request.trigger().label().toLowerCase(Locale.ROOT) + ", "
                + request.source().label().toLowerCase(Locale.ROOT) + ").");
        audit.record(AuditEntry.succeeded(actor, "deployment.start",
                AuditTarget.of("service", service.id(), service.name()), organizationId,
                "Deployment #" + sequence + " queued from " + request.source()));
        return deployment;
    }

    /**
     * The rules that make a deployment possible at all, checked before anything is
     * written so a customer gets a sentence naming the problem rather than a queued job
     * that fails on a node ten seconds later.
     */
    private void checkDeployable(Service service, DeploymentRequest request) {
        if (service.isArchived()) {
            throw new RequestRejected(null, "This service is archived. Restore its project "
                    + "before deploying it.");
        }
        if (service.kind() == ServiceKind.APP && request.source() != DeploymentSource.IMAGE) {
            throw new RequestRejected(null, "An app deploys the image it is configured with. "
                    + "Building an image from a repository is not something this platform "
                    + "does yet; point the service at a new image tag instead.");
        }
        if (service.kind() == ServiceKind.SITE && request.source() == DeploymentSource.IMAGE) {
            throw new RequestRejected(null, "A static site has no container, so there is no "
                    + "image to deploy.");
        }
        // A rollback clones nothing - the release is already on the node - so a repository
        // that has since been removed from the settings does not stand in its way. That
        // is precisely the moment somebody wants to go back to what was working.
        if (request.source() == DeploymentSource.GIT && service.repositoryUrl() == null
                && request.trigger() != DeploymentTrigger.ROLLBACK) {
            throw new RequestRejected("repositoryUrl", "This service has no repository. Add one "
                    + "in its settings, or upload a zip instead.");
        }
    }

    private void supersedeQueuedBefore(UUID serviceId, long sequence, Instant at) {
        List<Deployment> waiting = deployments.findQueuedBelow(serviceId, sequence);
        for (Deployment overtaken : waiting) {
            deployments.save(overtaken.superseded(sequence, at));
            // False when a worker already holds it. That is fine and needs no handling:
            // BuildArtifact re-reads the row and stops when it finds it superseded.
            jobs.cancel(DeploymentTasks.RUN, overtaken.id().toString());
            logs.note(overtaken.id(),
                    "Superseded by deployment #" + sequence + " before this one started.");
            // Anybody watching that one is told it is over rather than left on a stream
            // that has quietly stopped producing lines.
            logs.close(overtaken.id());
        }
    }
}
