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
 * Moves an organization onto a different plan.
 *
 * <p>An operator action, reachable only under {@code /admin/**}. There is no self-service
 * upgrade because there is no billing (design §12), and a customer-facing button that
 * silently raises their own limits would be the whole quota mechanism undone.
 *
 * <p>Nothing is migrated and nothing is destroyed. The new plan's limits apply from the
 * next call to {@link QuotaGuard#require}, so an organization moved down a tier keeps
 * what it already has and simply cannot create more - which is what the
 * {@link QuotaAllowance#remaining()} floor at zero exists for.
 */
@Component
public class AssignPlan {

    private final OrganizationRepository organizations;
    private final PlanRepository plans;
    private final AuditTrail audit;

    public AssignPlan(OrganizationRepository organizations, PlanRepository plans,
                      AuditTrail audit) {
        this.organizations = organizations;
        this.plans = plans;
        this.audit = audit;
    }

    /**
     * @param actor          the operator, for the trail
     * @param organizationId the tenant being moved
     * @param planId         the plan to move it to; must not be archived
     * @throws NotFoundException if either row is missing
     * @throws RequestRejected   if the plan is archived, or is the one already in force
     */
    @Transactional
    public Organization assign(AuditActor actor, UUID organizationId, UUID planId) {
        Organization organization = organizations.findById(organizationId)
                .orElseThrow(() -> NotFoundException.of("organization", organizationId));
        Plan plan = plans.findById(planId)
                .orElseThrow(() -> NotFoundException.of("plan", planId));

        if (!plan.isSelectable()) {
            throw new RequestRejected("planId", "Plan " + plan.code() + " is archived. Tenants "
                    + "already on it keep working, but nobody new can be moved onto it.");
        }
        if (organization.planId().equals(plan.id())) {
            throw new RequestRejected("planId",
                    organization.name() + " is already on plan " + plan.code() + ".");
        }

        Plan previous = plans.findById(organization.planId()).orElse(null);
        Organization moved = organizations.save(organization.onPlan(plan.id()));

        audit.record(AuditEntry.succeeded(actor, "plan.assign",
                AuditTarget.of("organization", moved.id(), moved.name()), moved.id(),
                (previous == null ? "unknown plan" : previous.code()) + " to " + plan.code()));
        return moved;
    }
}
