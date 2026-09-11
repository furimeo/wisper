package lhqm.furimeo.wisper.service;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.placement.ChooseNode;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Asks for a service to be up.
 *
 * <p>Three steps in this order, and the order is the point:
 *
 * <ol>
 * <li><strong>Find a node.</strong> If nothing can hold this service - no capacity, no
 *     node carrying the required tags - the transaction rolls back and the service is
 *     still stopped. Recording the intent first would leave a service the panel believes
 *     is running and nothing is.</li>
 * <li><strong>Write the intent.</strong> {@code desired_state = RUNNING} is the
 *     customer's decision and the only thing this package writes about running.</li>
 * <li><strong>Publish.</strong> The node is handed a spec that contains the workload. It
 *     is never told "start container X" - it is told what the world should look like and
 *     works out the difference (design §5.1).</li>
 * </ol>
 *
 * <p>Starting an already-running service is not an error. It republishes, which is the
 * cheapest useful thing a "start" button can do for a customer looking at a service that
 * says running and is not: the node gets the spec again and reconciles to it.
 *
 * <p>A site starts too. It has no container, but being in the spec is what makes the
 * node's Caddy serve its current release; leaving it out is how a site is taken offline
 * without deleting anything.
 */
@Component
public class StartService {

    private final ServiceRepository services;
    private final ChooseNode chooseNode;
    private final PublishNodeSpec specs;
    private final AuditTrail audit;

    public StartService(ServiceRepository services, ChooseNode chooseNode, PublishNodeSpec specs,
                        AuditTrail audit) {
        this.services = services;
        this.chooseNode = chooseNode;
        this.specs = specs;
        this.audit = audit;
    }

    /**
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        when the service is not this
     *                                                  organization's
     * @throws RequestRejected                          for an archived service
     */
    @Transactional
    public Service start(AuditActor actor, Membership membership, UUID serviceId) {
        membership.requireWrite("service.start");

        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));
        if (service.isArchived()) {
            throw new RequestRejected(null,
                    "This service is archived. Restore its project before starting it.");
        }

        UUID nodeId = chooseNode.forService(service.id());

        Service running = service.desiredState() == DesiredState.RUNNING
                ? service
                : services.save(service.desiring(DesiredState.RUNNING));

        specs.forService(running.id(), "service " + running.slug() + " started");

        audit.record(AuditEntry.succeeded(actor, "service.start",
                AuditTarget.of("service", running.id(), running.name()),
                membership.organizationId(), "Placed on node " + nodeId));
        return running;
    }
}
