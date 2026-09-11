package lhqm.furimeo.wisper.org;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Lifts a suspension.
 *
 * <p>A separate file from {@link SuspendOrganization} rather than a boolean argument on
 * it, because the two carry different information - one needs a reason and the other
 * needs none - and because the audit trail reads as two actions,
 * {@code organization.suspend} and {@code organization.resume}, which is how an operator
 * reconstructs what happened without parsing a detail string.
 */
@Component
public class ResumeOrganization {

    private final OrganizationRepository organizations;
    private final AuditTrail audit;

    public ResumeOrganization(OrganizationRepository organizations, AuditTrail audit) {
        this.organizations = organizations;
        this.audit = audit;
    }

    /**
     * @throws NotFoundException if there is no such organization
     * @throws RequestRejected   if it was not suspended in the first place
     */
    @Transactional
    public Organization resume(AuditActor actor, UUID organizationId) {
        Organization organization = organizations.findById(organizationId)
                .orElseThrow(() -> NotFoundException.of("organization", organizationId));

        if (!organization.isSuspended()) {
            throw new RequestRejected(null, organization.name() + " is not suspended.");
        }

        String previousReason = organization.suspensionReason();
        Organization resumed = organizations.save(organization.resumed());

        audit.record(AuditEntry.succeeded(actor, "organization.resume",
                AuditTarget.of("organization", resumed.id(), resumed.name()), resumed.id(),
                "Was suspended: " + previousReason));
        return resumed;
    }
}
