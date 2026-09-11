package lhqm.furimeo.wisper.org;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.org.QuotaAllowance.QuotaSource;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The one implementation of {@link QuotaGuard}: resolves a limit, measures the usage and
 * refuses what does not fit.
 *
 * <h2>Resolution order</h2>
 *
 * <ol>
 * <li>An unexpired {@link QuotaOverride} for the organization and resource.</li>
 * <li>Otherwise the {@link Quota} row on the organization's plan.</li>
 * <li>Otherwise <strong>zero</strong>.</li>
 * </ol>
 *
 * <p>There is no unlimited. Not as a sentinel, not as a null, not as a negative number:
 * the type is a {@code long} that is always a real ceiling, and the only way to grant a
 * customer a very large number of something is to write a very large number down. Reading
 * a missing row as "no limit" is the failure this whole mechanism exists to prevent -
 * it turns forgetting to seed one line of a plan into an unmetered platform, and nobody
 * finds out until a node fills up.
 *
 * <p>A suspended organization is refused everything, before any limit is even looked at.
 * Enumerating that condition at each of the twenty-odd call sites is how one of them gets
 * missed.
 *
 * <p>{@code require} runs in the caller's transaction ({@code MANDATORY} would be too
 * strict for the read-only methods, so the annotation is {@code SUPPORTS} and the rule
 * stays where panel-ports.md puts it: call it inside the write transaction, before the
 * insert). Reading the usage inside that transaction is what makes the count and the
 * insert see the same snapshot.
 */
@Component
public class EnforceQuota implements QuotaGuard {

    private final OrganizationRepository organizations;
    private final QuotaRepository quotas;
    private final QuotaOverrideRepository overrides;
    private final MeasureQuotaUsage usage;

    public EnforceQuota(OrganizationRepository organizations, QuotaRepository quotas,
                        QuotaOverrideRepository overrides, MeasureQuotaUsage usage) {
        this.organizations = organizations;
        this.quotas = quotas;
        this.overrides = overrides;
        this.usage = usage;
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public void require(UUID organizationId, QuotaResource resource, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Ask for what is being added, or do not ask: "
                    + "a shrinking resize frees quota and needs no permission");
        }
        Organization organization = load(organizationId);
        QuotaAllowance allowance = resolve(organization, resource,
                usage.of(organizationId, resource), Instant.now());

        if (organization.isSuspended()) {
            throw QuotaExceeded.suspended(organizationId, resource, amount, allowance,
                    organization.suspensionReason());
        }
        if (!allowance.permits(amount)) {
            throw new QuotaExceeded(organizationId, resource, amount, allowance);
        }
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public QuotaAllowance allowanceFor(UUID organizationId, QuotaResource resource) {
        Organization organization = load(organizationId);
        return resolve(organization, resource, usage.of(organizationId, resource), Instant.now());
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<QuotaAllowance> allowances(UUID organizationId) {
        Organization organization = load(organizationId);
        Instant now = Instant.now();
        Map<QuotaResource, Long> used = usage.all(organizationId);
        Map<QuotaResource, Long> planLimits = planLimits(organization.planId());
        Map<QuotaResource, QuotaOverride> live = liveOverrides(organizationId, now);

        List<QuotaAllowance> all = new ArrayList<>(QuotaResource.values().length);
        for (QuotaResource resource : QuotaResource.values()) {
            all.add(combine(resource, used.getOrDefault(resource, 0L),
                    Optional.ofNullable(live.get(resource)),
                    Optional.ofNullable(planLimits.get(resource))));
        }
        return List.copyOf(all);
    }

    private Organization load(UUID organizationId) {
        if (organizationId == null) {
            throw new NotFoundException("No organization was named");
        }
        return organizations.findById(organizationId)
                .orElseThrow(() -> NotFoundException.of("organization", organizationId));
    }

    private QuotaAllowance resolve(Organization organization, QuotaResource resource,
                                   long used, Instant now) {
        Optional<QuotaOverride> override = overrides
                .findByOrganizationIdAndResource(organization.id(), resource)
                .filter(candidate -> candidate.isLiveAt(now));
        Optional<Long> planLimit = quotas
                .findByPlanIdAndResource(organization.planId(), resource)
                .map(Quota::limitValue);
        return combine(resource, used, override, planLimit);
    }

    private static QuotaAllowance combine(QuotaResource resource, long used,
                                          Optional<QuotaOverride> override,
                                          Optional<Long> planLimit) {
        if (override.isPresent()) {
            return new QuotaAllowance(resource, override.get().limitValue(), used,
                    QuotaSource.ORGANIZATION_OVERRIDE);
        }
        if (planLimit.isPresent()) {
            return new QuotaAllowance(resource, planLimit.get(), used, QuotaSource.PLAN);
        }
        // Step three, and the reason this method has no fourth branch.
        return new QuotaAllowance(resource, 0L, used, QuotaSource.UNSET);
    }

    private Map<QuotaResource, Long> planLimits(UUID planId) {
        Map<QuotaResource, Long> limits = new EnumMap<>(QuotaResource.class);
        for (Quota quota : quotas.findByPlanId(planId)) {
            limits.put(quota.resource(), quota.limitValue());
        }
        return limits;
    }

    private Map<QuotaResource, QuotaOverride> liveOverrides(UUID organizationId, Instant now) {
        Map<QuotaResource, QuotaOverride> live = new EnumMap<>(QuotaResource.class);
        for (QuotaOverride override : overrides.findByOrganizationId(organizationId)) {
            if (override.isLiveAt(now)) {
                live.put(override.resource(), override);
            }
        }
        return live;
    }
}
