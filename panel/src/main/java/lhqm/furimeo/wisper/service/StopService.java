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
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Asks for a service to be down.
 *
 * <p>The placement stays. That is the whole difference between stopping and deleting: the
 * container, its volumes and its logs remain on the node that has them, and starting again
 * puts the service back where its bytes already are rather than wherever the scheduler
 * would pick today. A stopped service is still in the node's spec, with
 * {@code DESIRED_STATE_STOPPED} - leaving it out of the spec is how a workload is removed,
 * and removal is a different button.
 *
 * <p>Stopping an already-stopped service republishes and says so. It is what a customer
 * presses when the panel says stopped and the node disagrees, and it is exactly right for
 * that: the node is handed the spec again.
 *
 * <p>An archived service is not refused here. Archiving already stopped it, and refusing
 * to stop something because it is put away would leave the one recovery path - "press stop
 * again" - closed on the customer who most needs it.
 */
@Component
public class StopService {

    private final ServiceRepository services;
    private final PublishNodeSpec specs;
    private final AuditTrail audit;

    public StopService(ServiceRepository services, PublishNodeSpec specs, AuditTrail audit) {
        this.services = services;
        this.specs = specs;
        this.audit = audit;
    }

    /**
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        when the service is not this
     *                                                  organization's
     */
    @Transactional
    public Service stop(AuditActor actor, Membership membership, UUID serviceId) {
        membership.requireWrite("service.stop");

        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));

        Service stopped = service.desiredState() == DesiredState.STOPPED
                ? service
                : services.save(service.desiring(DesiredState.STOPPED));

        specs.forService(stopped.id(), "service " + stopped.slug() + " stopped");

        audit.record(AuditEntry.succeeded(actor, "service.stop",
                AuditTarget.of("service", stopped.id(), stopped.name()),
                membership.organizationId(),
                "Stopped; the container, its volumes and its logs are kept"));
        return stopped;
    }
}
