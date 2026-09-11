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
 * Gives one organization a limit its plan does not.
 *
 * <p>The alternative an operator reaches for otherwise is inventing a plan per customer,
 * which ends with forty plans nobody can compare and a tier structure that means nothing.
 * An override keeps the tier intact and puts the exception where it belongs: on the
 * customer, with a reason and an author attached.
 *
 * <p>Overrides go down as well as up. Capping one noisy tenant at fewer deployments per
 * day than their plan allows is the same mechanism, and it is the reason this takes a
 * limit rather than an increase.
 *
 * <p>Granting twice for the same resource revises the existing row instead of failing on
 * {@code quota_override_organization_resource_key}. The unique index is the invariant -
 * one exception per resource, so there is never a question of which one wins - and
 * re-granting is what an operator means by "extend it".
 */
@Component
public class GrantQuotaOverride {

    private final OrganizationRepository organizations;
    private final QuotaOverrideRepository overrides;
    private final AuditTrail audit;

    public GrantQuotaOverride(OrganizationRepository organizations,
                              QuotaOverrideRepository overrides, AuditTrail audit) {
        this.organizations = organizations;
        this.overrides = overrides;
        this.audit = audit;
    }

    /**
     * @param actor            the operator, for the trail
     * @param organizationId   the tenant
     * @param resource         which limit is being replaced
     * @param limitValue       the ceiling that applies instead of the plan's
     * @param reason           why this customer is different; required and non-blank
     * @param expiresAt        when it lapses back to the plan, or null to stand
     * @param grantedByAccount the operator's account, kept on the row
     * @throws NotFoundException if there is no such organization
     * @throws RequestRejected   if the reason is blank, the limit is negative, or the
     *                           expiry is already in the past
     */
    @Transactional
    public QuotaOverride grant(AuditActor actor, UUID organizationId, QuotaResource resource,
                               long limitValue, String reason, Instant expiresAt,
                               UUID grantedByAccount) {
        Organization organization = organizations.findById(organizationId)
                .orElseThrow(() -> NotFoundException.of("organization", organizationId));

        if (reason == null || reason.isBlank()) {
            throw new RequestRejected("reason",
                    "Say why. An override nobody can explain is one nobody dares remove.");
        }
        if (limitValue < 0) {
            throw new RequestRejected("limitValue", "A limit is zero or more.");
        }
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new RequestRejected("expiresAt", "That expiry has already passed, so the "
                    + "override would have no effect. Leave it empty for no expiry.");
        }

        QuotaOverride saved = overrides.save(overrides
                .findByOrganizationIdAndResource(organizationId, resource)
                .map(existing ->
                        existing.revisedTo(limitValue, reason.strip(), expiresAt, grantedByAccount))
                .orElseGet(() -> QuotaOverride.granted(organizationId, resource, limitValue,
                        reason.strip(), expiresAt, grantedByAccount)));

        audit.record(AuditEntry.succeeded(actor, "quota_override.grant",
                AuditTarget.of("quota_override", saved.id(), resource.name()), organization.id(),
                resource.name() + " set to " + limitValue
                        + (expiresAt == null ? " with no expiry" : " until " + expiresAt)));
        return saved;
    }
}
