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
 * Removes an exception, putting the organization back on its plan's limit for that
 * resource.
 *
 * <p>The row is deleted rather than expired. An override that has lapsed already stops
 * winning the resolution without anybody touching it, so a second "revoked" state would
 * describe the same fact twice - and the trail of who granted it, when and why is in
 * {@code audit_log}, which is where it survives the row.
 *
 * <p>Revoking below current usage is allowed and is not a mistake. The customer keeps
 * what they have - {@link QuotaAllowance#remaining()} floors at zero rather than going
 * negative - and simply cannot create more. Deleting a customer's resources to fit a
 * limit is not a decision a quota mechanism gets to make.
 */
@Component
public class RevokeQuotaOverride {

    private final OrganizationRepository organizations;
    private final QuotaOverrideRepository overrides;
    private final AuditTrail audit;

    public RevokeQuotaOverride(OrganizationRepository organizations,
                               QuotaOverrideRepository overrides, AuditTrail audit) {
        this.organizations = organizations;
        this.overrides = overrides;
        this.audit = audit;
    }

    /**
     * @param actor          the operator, for the trail
     * @param organizationId the tenant
     * @param resource       which exception to remove
     * @throws NotFoundException if there is no such organization, or no override for that
     *                           resource
     */
    @Transactional
    public void revoke(AuditActor actor, UUID organizationId, QuotaResource resource) {
        Organization organization = organizations.findById(organizationId)
                .orElseThrow(() -> NotFoundException.of("organization", organizationId));
        QuotaOverride override = overrides
                .findByOrganizationIdAndResource(organizationId, resource)
                .orElseThrow(() -> new NotFoundException(
                        "No " + resource.name() + " override on " + organization.name()));

        overrides.delete(override);

        audit.record(AuditEntry.succeeded(actor, "quota_override.revoke",
                AuditTarget.of("quota_override", override.id(), resource.name()),
                organization.id(),
                resource.name() + " back to the plan's limit; was " + override.limitValue()));
    }
}
