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
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Takes an app down and brings it straight back up.
 *
 * <h2>Why this is two generations and not a command</h2>
 *
 * <p>There is no "restart" in this platform's vocabulary, on the wire or anywhere else.
 * The panel publishes desired state and the node converges to it (design §5.1), and
 * {@code PanelMessage} carries no imperative that would let the panel reach past that.
 * A restart is therefore said in the only language the two share: one spec in which the
 * workload is stopped, then a second in which it is running. The node stops the container
 * for the first and starts it for the second, and both are ordinary reconciles.
 *
 * <p>The two publishes are separate on purpose, and each carries its own generation.
 * <strong>A node that has both specs in hand before it reconciles will see only the second
 * one</strong> and leave the container alone. The gap is small - specs are applied as they
 * arrive - but it is real, and closing it properly needs a monotonic revision on the
 * workload the node can compare, which the contract does not have yet.
 *
 * <h2>Sites</h2>
 *
 * <p>Refused. A static site is files on disk served by Caddy; there is no process, so
 * "restart" has no meaning and pretending otherwise would return a success message for
 * something that did nothing. A customer whose site is stale wants a deployment, and the
 * message says so.
 */
@Component
public class RestartService {

    private final ServiceRepository services;
    private final PublishNodeSpec specs;
    private final AuditTrail audit;

    public RestartService(ServiceRepository services, PublishNodeSpec specs, AuditTrail audit) {
        this.services = services;
        this.specs = specs;
        this.audit = audit;
    }

    /**
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        when the service is not this
     *                                                  organization's
     * @throws RequestRejected                          for a site, an archived service, or
     *                                                  one that is not running
     */
    @Transactional
    public Service restart(AuditActor actor, Membership membership, UUID serviceId) {
        membership.requireWrite("service.restart");

        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));

        if (service.isSite()) {
            throw new RequestRejected(null,
                    "A static site has no process to restart. Deploy it again to publish new "
                            + "files, or roll back to a previous release.");
        }
        if (service.isArchived()) {
            throw new RequestRejected(null,
                    "This service is archived. Restore its project before restarting it.");
        }
        if (service.desiredState() != DesiredState.RUNNING) {
            throw new RequestRejected(null,
                    "This service is stopped, so there is nothing to restart. Start it instead.");
        }

        Service down = services.save(service.desiring(DesiredState.STOPPED));
        specs.forService(down.id(), "service " + down.slug() + " restarting: stop");

        Service up = services.save(down.desiring(DesiredState.RUNNING));
        specs.forService(up.id(), "service " + up.slug() + " restarting: start");

        audit.record(AuditEntry.succeeded(actor, "service.restart",
                AuditTarget.of("service", up.id(), up.name()), membership.organizationId(),
                "Published a stopped generation and a running one"));
        return up;
    }
}
