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
 * Retires a tier, or brings it back.
 *
 * <p>Archiving, never deleting. {@code organization.plan_id} is {@code RESTRICT}, so a
 * plan somebody is on cannot be removed at all - and that is the right constraint: the
 * tenants on a withdrawn tier keep exactly the limits they had, and the tier drops out of
 * the picker so nobody new lands on it.
 *
 * <p>The default plan cannot be archived. Archiving it would leave
 * {@link CreateOrganization} with nothing to fall back on, and the failure would show up
 * later, on somebody else's screen, as "no default plan has been configured".
 */
@Component
public class ArchivePlan {

    private final PlanRepository plans;
    private final OrganizationRepository organizations;
    private final AuditTrail audit;

    public ArchivePlan(PlanRepository plans, OrganizationRepository organizations,
                       AuditTrail audit) {
        this.plans = plans;
        this.organizations = organizations;
        this.audit = audit;
    }

    /**
     * @throws NotFoundException if there is no such plan
     * @throws RequestRejected   if it is the default, or is archived already
     */
    @Transactional
    public Plan archive(AuditActor actor, UUID planId) {
        Plan plan = load(planId);
        if (plan.isDefault()) {
            throw new RequestRejected(null, plan.code() + " is the default plan. Make another "
                    + "plan the default first, otherwise new organizations would have nowhere "
                    + "to land.");
        }
        if (!plan.isSelectable()) {
            throw new RequestRejected(null, plan.code() + " is already archived.");
        }

        Plan archived = plans.save(withArchivedAt(plan, Instant.now()));
        long tenants = organizations.countByPlanId(planId);

        audit.record(AuditEntry.succeeded(actor, "plan.archive",
                AuditTarget.of("plan", archived.id(), archived.code()), null,
                tenants + " organization(s) stay on it"));
        return archived;
    }

    /** Puts a retired tier back in the picker. */
    @Transactional
    public Plan restore(AuditActor actor, UUID planId) {
        Plan plan = load(planId);
        if (plan.isSelectable()) {
            throw new RequestRejected(null, plan.code() + " is not archived.");
        }

        Plan restored = plans.save(withArchivedAt(plan, null));

        audit.record(AuditEntry.succeeded(actor, "plan.restore",
                AuditTarget.of("plan", restored.id(), restored.code()), null,
                "Selectable again"));
        return restored;
    }

    private Plan load(UUID planId) {
        return plans.findById(planId).orElseThrow(() -> NotFoundException.of("plan", planId));
    }

    private static Plan withArchivedAt(Plan plan, Instant archivedAt) {
        return new Plan(plan.id(), plan.code(), plan.name(), plan.description(), plan.isDefault(),
                archivedAt, plan.createdAt(), plan.updatedAt(), plan.version());
    }
}
