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
 * Sets one limit on one plan, creating the row or replacing the number on it.
 *
 * <p>Upsert rather than separate add and edit, because {@code quota_plan_resource_key}
 * makes them the same operation: there is one row per {@code (plan, resource)} and an
 * operator typing a number into a field means that number, whether or not a row was there
 * before.
 *
 * <p>The change is immediate for every organization on the plan. There is no version of a
 * plan and no scheduled application: {@link EnforceQuota} resolves the limit on each call,
 * so lowering a tier stops new creation at once and touches nothing that already exists.
 */
@Component
public class SetPlanQuota {

    private final PlanRepository plans;
    private final QuotaRepository quotas;
    private final AuditTrail audit;

    public SetPlanQuota(PlanRepository plans, QuotaRepository quotas, AuditTrail audit) {
        this.plans = plans;
        this.quotas = quotas;
        this.audit = audit;
    }

    /**
     * @param actor      the operator, for the trail
     * @param planId     the tier
     * @param resource   which limit
     * @param limitValue the ceiling, in the resource's smallest unit
     * @throws NotFoundException if there is no such plan
     * @throws RequestRejected   if the limit is negative
     */
    @Transactional
    public Quota set(AuditActor actor, UUID planId, QuotaResource resource, long limitValue) {
        Plan plan = plans.findById(planId)
                .orElseThrow(() -> NotFoundException.of("plan", planId));
        if (limitValue < 0) {
            throw new RequestRejected("limitValue", "A limit is zero or more. Zero means the "
                    + "tier does not include this at all.");
        }

        Quota saved = quotas.save(quotas.findByPlanIdAndResource(planId, resource)
                .map(existing -> existing.withLimit(limitValue))
                .orElseGet(() -> Quota.of(planId, resource, limitValue)));

        audit.record(AuditEntry.succeeded(actor, "quota.set",
                AuditTarget.of("plan", plan.id(), plan.code()), null,
                resource.name() + " set to " + limitValue));
        return saved;
    }
}
