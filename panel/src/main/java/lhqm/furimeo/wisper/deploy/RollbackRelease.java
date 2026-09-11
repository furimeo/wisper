package lhqm.furimeo.wisper.deploy;

import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Points a service back at a release it was serving before.
 *
 * <h2>A rollback is a new deployment, not an edit of an old one</h2>
 *
 * <p>Nothing is rewound. A new row is written whose
 * {@code rolled_back_from_deployment_id} names the release being restored, and that row
 * becomes the current one. The history therefore reads forwards - "#14 rolled back to
 * #11" is a thing that happened at a time, by a person - and "what is live" still has one
 * answer in one column, which is what the partial unique index is for.
 *
 * <p>It is instant because it builds nothing. The release directory is still on the node,
 * so publishing is the next spec naming an older release id and the node moving a symlink
 * (design §5.5). {@link BuildArtifact} sees {@link DeploymentTrigger#ROLLBACK} and skips
 * straight to {@code deploy-publish}.
 *
 * <p>The two node-reported columns are copied onto the new row rather than being chased
 * through the pointer at publish time. {@code image_digest} exists on this table for
 * exactly that reason - "the digest actually deployed, so a rollback restarts the same
 * bytes" (V16) - and {@code release_path} for the same reason on the other kind of
 * service. Neither value is invented: both are the node's own report being pointed at
 * again.
 */
@Component
public class RollbackRelease {

    private final DeploymentRepository deployments;
    private final StartDeployment startDeployment;
    private final AppendDeploymentLog logs;
    private final AuditTrail audit;

    public RollbackRelease(DeploymentRepository deployments, StartDeployment startDeployment,
                           AppendDeploymentLog logs, AuditTrail audit) {
        this.deployments = deployments;
        this.startDeployment = startDeployment;
        this.logs = logs;
        this.audit = audit;
    }

    /**
     * Queues a rollback of {@code serviceId} to {@code targetId}.
     *
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException if the target is not a deployment of that service, which
     *         is also the answer for a target belonging to another tenant
     * @throws RequestRejected   if the target never succeeded, or is already live
     */
    @Transactional
    public Deployment to(AuditActor actor, Membership membership, UUID serviceId, UUID targetId) {
        membership.requireWrite("deployment.rollback");

        Deployment target = deployments.findById(targetId)
                .filter(candidate -> candidate.serviceId().equals(serviceId))
                .orElseThrow(() -> NotFoundException.of("deployment", targetId));
        if (target.isCurrent()) {
            throw new RequestRejected(null, "Deployment #" + target.sequence()
                    + " is already the live one.");
        }
        if (!target.isRollbackTarget()) {
            throw new RequestRejected(null, "Deployment #" + target.sequence() + " "
                    + target.status().label().toLowerCase(Locale.ROOT)
                    + ", so there is no release to go back to.");
        }

        Deployment queued = startDeployment.start(actor, membership.organizationId(), serviceId,
                DeploymentRequest.rollbackTo(membership.accountId(), target));
        Deployment pointed = deployments.save(queued.withBuildOutput(target.releasePath(),
                target.imageDigest(), target.commitSha(), target.commitMessage()));

        logs.note(pointed.id(), "Rolling back to deployment #" + target.sequence()
                + (target.shortCommit() == null ? "" : " (" + target.shortCommit() + ")") + ".");
        audit.record(AuditEntry.succeeded(actor, "deployment.rollback",
                AuditTarget.of("service", serviceId, "#" + target.sequence()),
                membership.organizationId(),
                "Deployment #" + pointed.sequence() + " restores release #"
                        + target.sequence()));
        return pointed;
    }
}
