package lhqm.furimeo.wisper.files;

import java.util.UUID;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.service.ServiceLocation;
import lhqm.furimeo.wisper.service.ServiceNotPlaced;

/**
 * Permission to touch one directory tree, resolved once and carried through the operation.
 *
 * <p>Four things travel together on every file request - who is asking, which tenant they
 * are asking on behalf of, which machine holds the files and which root they are allowed
 * into - and a method that takes this cannot be called by a caller who never looked them
 * up. That is the point: the fourteen operations in this package differ in what they do to
 * a path and in nothing else, so the check has to be impossible to skip rather than
 * remembered fourteen times.
 *
 * <p>Produced by {@link AuthorizeFileAccess} and by nothing else.
 *
 * @param service    where the service is, including the node holding it - null when
 *                   nothing is
 * @param membership the caller's role in the owning organization
 * @param root       the directory tree, already checked to belong to this service
 * @param actor      who to name in the audit trail, with the address they came from
 */
public record FileAccess(ServiceLocation service, Membership membership, FileRootRef root,
                         AuditActor actor) {

    /** The service being browsed. */
    public UUID serviceId() {
        return service.serviceId();
    }

    /** The tenant, for the audit entry and for quota. */
    public UUID organizationId() {
        return membership.organizationId();
    }

    /** The id the node knows this directory by. */
    public String rootId() {
        return root.id();
    }

    /** Whether the caller may change anything at all in this organization. */
    public boolean canWrite() {
        return membership.canWrite() && root.writable();
    }

    /**
     * The machine holding the files.
     *
     * @throws ServiceNotPlaced when nothing is running the service, which is an ordinary
     *         state for one that has never been started and not an error - the answer is
     *         "start it first", and a page that says so is better than an RPC to nowhere
     */
    public UUID requireNodeId() {
        if (!service.isPlaced()) {
            throw new ServiceNotPlaced(service.serviceId(), service.name());
        }
        return service.nodeId();
    }

    /** What an audit entry for this operation was done to. */
    public AuditTarget target() {
        return AuditTarget.of("service", service.serviceId(), service.name());
    }
}
