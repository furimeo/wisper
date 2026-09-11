package lhqm.furimeo.wisper.org;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;

/**
 * Opens a new tenant and makes the person who asked for it its owner.
 *
 * <p>The two writes are one transaction on purpose. An organization with no members is
 * unreachable by anybody, including the person who just created it, and it cannot be
 * repaired from any screen in the panel - there is no route that grants membership to an
 * organization you are not already a member of.
 */
@Component
public class CreateOrganization {

    private final OrganizationRepository organizations;
    private final MemberRepository members;
    private final PlanRepository plans;
    private final AuditTrail audit;

    public CreateOrganization(OrganizationRepository organizations, MemberRepository members,
                              PlanRepository plans, AuditTrail audit) {
        this.organizations = organizations;
        this.members = members;
        this.plans = plans;
        this.audit = audit;
    }

    /**
     * @param actor          who is asking, for the trail
     * @param ownerAccountId the account that becomes the first owner
     * @param name           what the customer calls it
     * @param slug           the URL fragment; lower-cased and trimmed here
     * @param planId         the plan to start on, or null for the default one
     * @throws RequestRejected if the slug is malformed or taken, or the chosen plan
     *                         cannot be used
     */
    @Transactional
    public Organization create(AuditActor actor, UUID ownerAccountId, String name, String slug,
                               UUID planId) {
        String normalisedSlug = normalise(slug);
        if (!normalisedSlug.matches(Organization.SLUG_PATTERN)) {
            throw new RequestRejected("slug", "An address is 2 to 63 characters of lower-case "
                    + "letters, digits and dashes, and starts with a letter or a digit.");
        }
        if (organizations.existsBySlug(normalisedSlug)) {
            throw new RequestRejected("slug", "That address is already taken.");
        }

        Plan plan = choosePlan(planId);
        Instant now = Instant.now();

        Organization organization = organizations.save(
                Organization.opened(UUID.randomUUID(), name.strip(), normalisedSlug, plan.id()));
        members.save(Member.founding(UUID.randomUUID(), organization.id(), ownerAccountId, now));

        audit.record(AuditEntry.succeeded(actor, "organization.create",
                AuditTarget.of("organization", organization.id(), organization.name()),
                organization.id(), "Opened on plan " + plan.code()));
        return organization;
    }

    /**
     * The plan the customer picked, or the default one.
     *
     * <p>An archived plan is refused rather than quietly swapped: somebody who picked it
     * from a stale page needs to be told, not moved onto a tier they did not choose. No
     * default at all is refused too - a tenant on no plan has every limit at zero and
     * looks like a broken panel rather than like a missing seed row.
     */
    private Plan choosePlan(UUID planId) {
        if (planId != null) {
            return plans.findById(planId)
                    .filter(Plan::isSelectable)
                    .orElseThrow(() -> new RequestRejected("planId",
                            "That plan is no longer available."));
        }
        return plans.findDefault().orElseThrow(() -> new RequestRejected("planId",
                "No default plan has been configured yet. An operator has to create one "
                        + "before organizations can be opened."));
    }

    private static String normalise(String slug) {
        return slug == null ? "" : slug.strip().toLowerCase(Locale.ROOT);
    }
}
