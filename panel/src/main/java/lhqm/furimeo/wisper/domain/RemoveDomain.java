package lhqm.furimeo.wisper.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.jobs.JobQueue;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Takes a hostname off a service and releases it back to the platform.
 *
 * <p>Removing is the only way a name becomes available again - {@code domain_hostname_key}
 * is global - so this is also the transfer mechanism between two customers who have agreed
 * on one. That makes it worth an audit entry naming the hostname, because the entry is what
 * a dispute is settled from after the row is gone.
 *
 * <h2>What goes with it</h2>
 *
 * <ul>
 * <li>The {@code certificate} rows cascade. They are metadata; the key was never here and
 *     the node discards its copy when the route leaves its spec.</li>
 * <li>The pending verification job is cancelled, so a check does not run against an id that
 *     no longer resolves to anything.</li>
 * <li>If the hostname was the primary, the oldest remaining one is promoted. A service with
 *     hostnames and no primary would show the customer no address at all, and
 *     {@code domain_primary_per_service_idx} makes the fix a single row change.</li>
 * </ul>
 */
@Component
public class RemoveDomain {

    private final DomainRepository domains;
    private final JobQueue jobs;
    private final PublishNodeSpec specs;
    private final AuditTrail audit;

    public RemoveDomain(DomainRepository domains, JobQueue jobs, PublishNodeSpec specs,
                        AuditTrail audit) {
        this.domains = domains;
        this.jobs = jobs;
        this.specs = specs;
        this.audit = audit;
    }

    /**
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        if the hostname is not this
     *                                                  organization's
     */
    @Transactional
    public Domain remove(AuditActor actor, Membership membership, UUID serviceId, UUID domainId) {
        membership.requireWrite("domain.remove");
        Domain domain = domains.findOwnedBy(domainId, serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("domain", domainId));

        domains.delete(domain);
        // Before the successor is promoted: the partial unique index allows one PRIMARY per
        // service, and deleting first is what makes room for the promotion in the same
        // transaction.
        String promoted = promoteSuccessor(domain);

        jobs.cancel(DomainVerificationTasks.VERIFY, domainId.toString());
        specs.forService(serviceId, "hostname " + domain.hostname() + " removed");

        audit.record(AuditEntry.succeeded(actor, "domain.remove",
                AuditTarget.of("domain", domain.id(), domain.hostname()),
                membership.organizationId(),
                promoted == null
                        ? "Released " + domain.hostname()
                        : "Released " + domain.hostname() + "; " + promoted
                                + " is now the primary hostname"));
        return domain;
    }

    /**
     * Gives a service its address back after the primary was removed.
     *
     * @return the hostname that was promoted, or null when nothing needed promoting
     */
    private String promoteSuccessor(Domain removed) {
        if (!removed.isPrimary()) {
            return null;
        }
        List<Domain> remaining = domains.findByServiceId(removed.serviceId());
        if (remaining.isEmpty()) {
            return null;
        }
        // findByServiceId is primary-first then alphabetical, and there is no primary left,
        // so the head is the first hostname alphabetically - a stable, explainable choice
        // rather than whichever row the database happened to return.
        Domain successor = remaining.get(0);
        domains.save(successor.as(DomainKind.PRIMARY));
        return successor.hostname();
    }
}
