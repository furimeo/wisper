package lhqm.furimeo.wisper.domain;

import java.util.Optional;
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
 * Chooses which of a service's hostnames the panel calls its address.
 *
 * <p>Cosmetic on the node - {@code Route} carries no notion of a primary, so the spec does
 * not change and nothing is republished - and not cosmetic to the customer, who reads the
 * primary in every place the panel says "your site is at". It is also what a deploy
 * notification and a share link should use.
 *
 * <h2>Why the swap is two writes in one transaction</h2>
 *
 * <p>{@code domain_primary_per_service_idx} is a partial unique index, so there cannot be a
 * moment with two primaries. Demoting the incumbent first and promoting the successor second
 * is the only order that never has two, and both statements committing together is what
 * stops a failure between them leaving a service with none.
 */
@Component
public class SetPrimaryDomain {

    /**
     * Not in {@code AuditAction.ALL} yet.
     *
     * <p>panel-ports.md §2.4 lists three domain actions and this is a fourth. The database
     * enforces the shape and not the vocabulary, so the entry is written and readable; what
     * it is missing until the contract is updated is a place in the filter on
     * {@code /admin/audit}. An unaudited state change would be worse than a vocabulary that
     * has grown, which is the same call the {@code audit} package already made for
     * {@code files.create_directory}, {@code job.retry} and {@code job.discard}.
     */
    static final String ACTION = "domain.set_primary";

    private final DomainRepository domains;
    private final AuditTrail audit;

    public SetPrimaryDomain(DomainRepository domains, AuditTrail audit) {
        this.domains = domains;
        this.audit = audit;
    }

    /**
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        if the hostname is not this
     *                                                  organization's
     * @throws RequestRejected                          for a wildcard row, which cannot be a
     *                                                  primary: {@code domain_wildcard_shape}
     *                                                  ties the kind to the {@code *.} prefix
     */
    @Transactional
    public Domain promote(AuditActor actor, Membership membership, UUID serviceId, UUID domainId) {
        membership.requireWrite(ACTION);
        Domain domain = domains.findOwnedBy(domainId, serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("domain", domainId));

        if (domain.kind() == DomainKind.WILDCARD) {
            throw new RequestRejected(null, domain.hostname() + " is a wildcard and cannot be a "
                    + "service's main address. Add the exact hostname you want to show.");
        }
        if (domain.isPrimary()) {
            throw new RequestRejected(null, domain.hostname()
                    + " is already this service's main address.");
        }

        Optional<Domain> incumbent = domains.findPrimaryOf(serviceId);
        incumbent.ifPresent(previous -> domains.save(previous.as(DomainKind.ALIAS)));
        Domain promoted = domains.save(domain.as(DomainKind.PRIMARY));

        audit.record(AuditEntry.succeeded(actor, ACTION,
                AuditTarget.of("domain", promoted.id(), promoted.hostname()),
                membership.organizationId(),
                incumbent.map(previous -> "Replaced " + previous.hostname()
                                + " as the main address")
                        .orElse("Set as the main address")));
        return promoted;
    }
}
