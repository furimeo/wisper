package lhqm.furimeo.wisper.files;

import java.util.UUID;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.service.ServiceLocation;
import lhqm.furimeo.wisper.service.ServiceNotPlaced;

/**
 * Permission to open a shell inside a service's container.
 *
 * <p>Deliberately not a {@link FileAccess}. A terminal names no file root - an app with no
 * volumes has no roots at all and still has a shell - and requiring one would make the
 * terminal unreachable for exactly the services most likely to need it.
 *
 * <p>What it shares with {@link FileAccess} is the rule: a shell inside a customer's
 * container can do anything the application can, so it is a write and a {@code VIEWER} does
 * not get one.
 */
public record TerminalAccess(ServiceLocation service, Membership membership, AuditActor actor) {

    public UUID serviceId() {
        return service.serviceId();
    }

    public UUID organizationId() {
        return membership.organizationId();
    }

    /** The id the node knows this workload by, which is the service's own id. */
    public String workloadId() {
        return service.serviceId().toString();
    }

    /**
     * The machine holding the container.
     *
     * @throws ServiceNotPlaced when nothing is running the service - "start it first" rather
     *         than a stream that never attaches
     */
    public UUID requireNodeId() {
        if (!service.isPlaced()) {
            throw new ServiceNotPlaced(service.serviceId(), service.name());
        }
        return service.nodeId();
    }

    /** What an audit entry for this session was done to. */
    public AuditTarget target() {
        return AuditTarget.of("service", service.serviceId(), service.name());
    }
}
