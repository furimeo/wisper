package lhqm.furimeo.wisper.org;

import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;

/**
 * Adds a tier.
 *
 * <p>A plan starts with no {@link Quota} rows at all, which means every limit on it is
 * zero - and that is the safe direction to be wrong in. An operator then raises the ones
 * the tier is meant to include through {@link SetPlanQuota}. The opposite default, a new
 * plan that allows everything until somebody remembers to constrain it, is the mistake
 * the whole resolution order in {@link EnforceQuota} is arranged to make impossible.
 *
 * <p>New plans are never the default. Becoming the default displaces another plan and is
 * therefore its own decision, made in {@link MakePlanDefault}.
 */
@Component
public class CreatePlan {

    private final PlanRepository plans;
    private final AuditTrail audit;

    public CreatePlan(PlanRepository plans, AuditTrail audit) {
        this.plans = plans;
        this.audit = audit;
    }

    /**
     * @param actor       the operator, for the trail
     * @param code        stable machine name, lower case, used in URLs and seed data
     * @param name        what the picker shows
     * @param description one line saying who the tier is for
     * @throws RequestRejected if the code is malformed or already used
     */
    @Transactional
    public Plan create(AuditActor actor, String code, String name, String description) {
        String normalised = code == null ? "" : code.strip().toLowerCase(Locale.ROOT);
        if (!normalised.matches(Plan.CODE_PATTERN)) {
            throw new RequestRejected("code", "A code is lower-case letters, digits and dashes, "
                    + "starting with a letter or a digit.");
        }
        if (plans.findByCode(normalised).isPresent()) {
            throw new RequestRejected("code", "There is already a plan called " + normalised + ".");
        }

        Plan plan = plans.save(new Plan(UUID.randomUUID(), normalised, name.strip(),
                description == null ? "" : description.strip(), false, null, null, null, null));

        audit.record(AuditEntry.succeeded(actor, "plan.create",
                AuditTarget.of("plan", plan.id(), plan.code()), null,
                "Created with every limit at zero"));
        return plan;
    }
}
