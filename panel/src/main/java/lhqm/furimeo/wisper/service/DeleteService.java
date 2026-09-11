package lhqm.furimeo.wisper.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.node.NodeSpecSource;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.placement.ReleasePlacement;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Removes a service and everything hanging off it.
 *
 * <p>The order below is the whole of this class, and every step of it is load-bearing:
 *
 * <ol>
 * <li><strong>Ask which nodes hold it, first.</strong> Once the placement is released the
 *     answer is empty, and a node nobody remembered to tell keeps running the container
 *     forever. This is the step that is easy to write last and wrong.</li>
 * <li><strong>Release the placement.</strong> {@code placement} owns that table and the
 *     bookkeeping that goes with letting a node go.</li>
 * <li><strong>Delete the row.</strong> {@code ON DELETE CASCADE} takes the environment
 *     variables, the secrets, the volumes, the cron entries, the domains, the deployments
 *     and the placement with it.</li>
 * <li><strong>Publish to each node that was holding it.</strong> The spec no longer
 *     contains the workload, and a workload absent from the spec is a workload the node
 *     removes. That, not an RPC, is how a container stops existing here.</li>
 * </ol>
 *
 * <p>What this does <em>not</em> do is delete the customer's bytes. The node removes
 * containers that are no longer in its spec; volume data goes only on an explicit purge,
 * because "cannot see it" is not "does not exist" and a platform that deletes disks on a
 * cascade has one bad afternoon and no customers (AGENTS.md §4.5, schema.md §5). The
 * confirmation the controller insists on is the honest half of that trade.
 */
@Component
public class DeleteService {

    private final ServiceRepository services;
    private final NodeSpecSource specSource;
    private final ReleasePlacement releasePlacement;
    private final PublishNodeSpec specs;
    private final AuditTrail audit;

    public DeleteService(ServiceRepository services, NodeSpecSource specSource,
                         ReleasePlacement releasePlacement, PublishNodeSpec specs,
                         AuditTrail audit) {
        this.services = services;
        this.specSource = specSource;
        this.releasePlacement = releasePlacement;
        this.specs = specs;
        this.audit = audit;
    }

    /**
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        when the service is not this
     *                                                  organization's
     */
    @Transactional
    public void delete(AuditActor actor, Membership membership, UUID serviceId) {
        membership.requireWrite("service.delete");

        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));

        List<UUID> nodes = specSource.nodesHosting(service.id());
        String reason = "service " + service.slug() + " deleted";

        releasePlacement.forService(service.id(), reason);
        services.delete(service);

        for (UUID nodeId : nodes) {
            specs.toNode(nodeId, reason);
        }

        audit.record(AuditEntry.succeeded(actor, "service.delete",
                AuditTarget.of("service", service.id(), service.name()),
                membership.organizationId(),
                nodes.isEmpty()
                        ? "Deleted; it was not placed on a node"
                        : "Deleted; " + nodes.size() + " node(s) republished. Volume data on the "
                                + "node is kept until it is purged."));
    }
}
