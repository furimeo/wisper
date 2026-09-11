package lhqm.furimeo.wisper.org;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Stops the panel writing anything new for a tenant.
 *
 * <p>Suspension is not a kill switch and deliberately does not behave like one. Every
 * container the customer has keeps running, every site keeps serving and every backup
 * keeps its schedule; what stops is creation - {@link EnforceQuota#require} refuses every
 * resource for a suspended organization, whatever its plan says.
 *
 * <p>The separation matters because the two decisions are made for different reasons and
 * by different people. "Stop accepting new work from this customer" is an account
 * decision; "take this customer's site off the internet" is an operational one, and it is
 * made per service, on the service's own screen, where the person doing it can see what
 * they are turning off.
 */
@Component
public class SuspendOrganization {

    private final OrganizationRepository organizations;
    private final AuditTrail audit;

    public SuspendOrganization(OrganizationRepository organizations, AuditTrail audit) {
        this.organizations = organizations;
        this.audit = audit;
    }

    /**
     * @param actor          the operator, for the trail
     * @param organizationId the tenant
     * @param reason         shown to the customer on every refusal, so being locked out is
     *                       not a mystery; required
     * @throws NotFoundException if there is no such organization
     * @throws RequestRejected   if the reason is blank, or it is suspended already
     */
    @Transactional
    public Organization suspend(AuditActor actor, UUID organizationId, String reason) {
        Organization organization = organizations.findById(organizationId)
                .orElseThrow(() -> NotFoundException.of("organization", organizationId));

        if (reason == null || reason.isBlank()) {
            throw new RequestRejected("reason", "Say why. The customer is shown this sentence "
                    + "every time something is refused.");
        }
        if (organization.isSuspended()) {
            throw new RequestRejected("reason", organization.name() + " is already suspended.");
        }

        Organization suspended = organizations.save(
                organization.suspended(reason.strip(), Instant.now()));

        audit.record(AuditEntry.succeeded(actor, "organization.suspend",
                AuditTarget.of("organization", suspended.id(), suspended.name()), suspended.id(),
                suspended.suspensionReason()));
        return suspended;
    }
}
