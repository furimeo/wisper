package lhqm.furimeo.wisper.org;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Chooses the tier a new organization lands on when nobody picks one.
 *
 * <p>Its own use-case rather than a flag on a plan form, because it is the one plan change
 * that touches a second row. {@code plan_single_default_idx} is a partial unique index
 * over {@code is_default}, and it is not deferrable: the outgoing default has to be
 * cleared by a statement that runs <strong>before</strong> the incoming one is set, or the
 * transaction dies on a constraint the operator did nothing wrong to hit. That ordering is
 * the entire reason this file exists, and it is why it is not two lines in a controller.
 */
@Component
public class MakePlanDefault {

    private final PlanRepository plans;
    private final AuditTrail audit;

    public MakePlanDefault(PlanRepository plans, AuditTrail audit) {
        this.plans = plans;
        this.audit = audit;
    }

    /**
     * @throws NotFoundException if there is no such plan
     * @throws RequestRejected   if it is archived, or it is already the default
     */
    @Transactional
    public Plan makeDefault(AuditActor actor, UUID planId) {
        Plan plan = plans.findById(planId)
                .orElseThrow(() -> NotFoundException.of("plan", planId));
        if (!plan.isSelectable()) {
            throw new RequestRejected(null, "An archived plan cannot be the default. Restore "
                    + plan.code() + " first.");
        }
        if (plan.isDefault()) {
            throw new RequestRejected(null, plan.code() + " is already the default.");
        }

        Optional<Plan> outgoing = plans.findDefault();
        outgoing.ifPresent(previous -> plans.save(withDefault(previous, false)));
        Plan incoming = plans.save(withDefault(plan, true));

        audit.record(AuditEntry.succeeded(actor, "plan.default",
                AuditTarget.of("plan", incoming.id(), incoming.code()), null,
                outgoing.map(previous -> "Replaced " + previous.code())
                        .orElse("There was no default before")));
        return incoming;
    }

    private static Plan withDefault(Plan plan, boolean value) {
        return new Plan(plan.id(), plan.code(), plan.name(), plan.description(), value,
                plan.archivedAt(), plan.createdAt(), plan.updatedAt(), plan.version());
    }
}
