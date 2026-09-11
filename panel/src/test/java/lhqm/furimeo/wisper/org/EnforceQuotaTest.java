package lhqm.furimeo.wisper.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import lhqm.furimeo.wisper.org.QuotaAllowance.QuotaSource;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The limit arithmetic, at the three places it is worth being sure about: exactly at the
 * ceiling, one past it, and the case where nobody wrote a ceiling down at all.
 *
 * <p>The third is the one that matters most. There is no unlimited plan in this design,
 * and the test that a missing row means zero rather than infinity is what stops somebody
 * "fixing" the resolution order later and shipping an unmetered platform.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnforceQuotaTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID PLAN = UUID.randomUUID();

    @Mock
    private OrganizationRepository organizations;

    @Mock
    private QuotaRepository quotas;

    @Mock
    private QuotaOverrideRepository overrides;

    @Mock
    private MeasureQuotaUsage usage;

    @InjectMocks
    private EnforceQuota enforceQuota;

    @BeforeEach
    void organizationIsActiveOnAPlan() {
        given(organizations.findById(ORGANIZATION)).willReturn(Optional.of(active()));
        given(overrides.findByOrganizationIdAndResource(eq(ORGANIZATION), any()))
                .willReturn(Optional.empty());
        given(overrides.findByOrganizationId(ORGANIZATION)).willReturn(List.of());
        given(quotas.findByPlanId(PLAN)).willReturn(List.of());
    }

    @Nested
    class TheEdgeOfALimit {

        @Test
        void oneUnderTheLimitIsAllowed() {
            planAllows(QuotaResource.PROJECT, 5);
            alreadyUsing(QuotaResource.PROJECT, 3);

            assertThatNoException().isThrownBy(
                    () -> enforceQuota.require(ORGANIZATION, QuotaResource.PROJECT, 1));
        }

        @Test
        void theRequestThatLandsExactlyOnTheLimitIsAllowed() {
            planAllows(QuotaResource.PROJECT, 5);
            alreadyUsing(QuotaResource.PROJECT, 4);

            assertThatNoException().isThrownBy(
                    () -> enforceQuota.require(ORGANIZATION, QuotaResource.PROJECT, 1));
        }

        @Test
        void theRequestThatWouldGoOneOverIsRefused() {
            planAllows(QuotaResource.PROJECT, 5);
            alreadyUsing(QuotaResource.PROJECT, 5);

            QuotaExceeded refused = catchQuotaExceeded(QuotaResource.PROJECT, 1);

            assertThat(refused.organizationId()).isEqualTo(ORGANIZATION);
            assertThat(refused.resource()).isEqualTo(QuotaResource.PROJECT);
            assertThat(refused.requested()).isEqualTo(1);
            assertThat(refused.allowance().limit()).isEqualTo(5);
            assertThat(refused.allowance().used()).isEqualTo(5);
            assertThat(refused.message()).contains("6 of 5");
        }

        @Test
        void abyteAmountIsCheckedAgainstTheWholeIncrease() {
            planAllows(QuotaResource.VOLUME_BYTES, 10_737_418_240L);
            alreadyUsing(QuotaResource.VOLUME_BYTES, 10_737_418_239L);

            assertThatNoException().isThrownBy(
                    () -> enforceQuota.require(ORGANIZATION, QuotaResource.VOLUME_BYTES, 1));
            assertThatExceptionOfType(QuotaExceeded.class).isThrownBy(
                    () -> enforceQuota.require(ORGANIZATION, QuotaResource.VOLUME_BYTES, 2));
        }

        @Test
        void aHugeLimitDoesNotOverflowIntoPermittingEverything() {
            planAllows(QuotaResource.BACKUP_BYTES, Long.MAX_VALUE);
            alreadyUsing(QuotaResource.BACKUP_BYTES, Long.MAX_VALUE - 1);

            assertThatNoException().isThrownBy(
                    () -> enforceQuota.require(ORGANIZATION, QuotaResource.BACKUP_BYTES, 1));
            assertThatExceptionOfType(QuotaExceeded.class).isThrownBy(
                    () -> enforceQuota.require(ORGANIZATION, QuotaResource.BACKUP_BYTES, 2));
        }

        @Test
        void usageAboveALoweredLimitLeavesNothingRemainingRatherThanANegativeNumber() {
            planAllows(QuotaResource.SERVICE, 2);
            alreadyUsing(QuotaResource.SERVICE, 7);

            QuotaAllowance allowance =
                    enforceQuota.allowanceFor(ORGANIZATION, QuotaResource.SERVICE);

            assertThat(allowance.remaining()).isZero();
            assertThat(allowance.permits(0)).isFalse();
            assertThatExceptionOfType(QuotaExceeded.class).isThrownBy(
                    () -> enforceQuota.require(ORGANIZATION, QuotaResource.SERVICE, 1));
        }
    }

    @Nested
    class ThereIsNoUnlimitedPlan {

        @Test
        void aResourceWithNoRowOnThePlanIsLimitedToZero() {
            noPlanRowFor(QuotaResource.MANAGED_DATABASE);
            alreadyUsing(QuotaResource.MANAGED_DATABASE, 0);

            QuotaExceeded refused = catchQuotaExceeded(QuotaResource.MANAGED_DATABASE, 1);

            assertThat(refused.allowance().limit()).isZero();
            assertThat(refused.allowance().source()).isEqualTo(QuotaSource.UNSET);
            assertThat(refused.message()).contains("does not include");
        }

        @Test
        void anExplicitZeroIsIndistinguishableInEffectButNotInSource() {
            planAllows(QuotaResource.MANAGED_DATABASE, 0);
            alreadyUsing(QuotaResource.MANAGED_DATABASE, 0);

            QuotaExceeded refused = catchQuotaExceeded(QuotaResource.MANAGED_DATABASE, 1);

            assertThat(refused.allowance().limit()).isZero();
            assertThat(refused.allowance().source()).isEqualTo(QuotaSource.PLAN);
        }

        @Test
        void generosityHasToBeWrittenDownAsANumber() {
            planAllows(QuotaResource.DEPLOYMENTS_PER_DAY, 1_000_000);
            alreadyUsing(QuotaResource.DEPLOYMENTS_PER_DAY, 999_999);

            assertThatNoException().isThrownBy(() ->
                    enforceQuota.require(ORGANIZATION, QuotaResource.DEPLOYMENTS_PER_DAY, 1));
            assertThatExceptionOfType(QuotaExceeded.class).isThrownBy(() ->
                    enforceQuota.require(ORGANIZATION, QuotaResource.DEPLOYMENTS_PER_DAY, 2));
        }
    }

    @Nested
    class AnOverrideBeatsThePlan {

        @Test
        void aLiveOverrideRaisesTheCeiling() {
            planAllows(QuotaResource.DOMAIN, 2);
            overrideOf(QuotaResource.DOMAIN, 10, Instant.now().plus(7, ChronoUnit.DAYS));
            alreadyUsing(QuotaResource.DOMAIN, 5);

            QuotaAllowance allowance =
                    enforceQuota.allowanceFor(ORGANIZATION, QuotaResource.DOMAIN);

            assertThat(allowance.limit()).isEqualTo(10);
            assertThat(allowance.source()).isEqualTo(QuotaSource.ORGANIZATION_OVERRIDE);
            assertThatNoException().isThrownBy(
                    () -> enforceQuota.require(ORGANIZATION, QuotaResource.DOMAIN, 5));
        }

        @Test
        void anOverrideLowersTheCeilingJustAsReadily() {
            planAllows(QuotaResource.CRON_TASK, 50);
            overrideOf(QuotaResource.CRON_TASK, 1, null);
            alreadyUsing(QuotaResource.CRON_TASK, 1);

            assertThatExceptionOfType(QuotaExceeded.class).isThrownBy(
                    () -> enforceQuota.require(ORGANIZATION, QuotaResource.CRON_TASK, 1));
        }

        @Test
        void anExpiredOverrideFallsBackToThePlanWithNoSweep() {
            planAllows(QuotaResource.DOMAIN, 2);
            overrideOf(QuotaResource.DOMAIN, 10, Instant.now().minusSeconds(1));
            alreadyUsing(QuotaResource.DOMAIN, 2);

            QuotaAllowance allowance =
                    enforceQuota.allowanceFor(ORGANIZATION, QuotaResource.DOMAIN);

            assertThat(allowance.limit()).isEqualTo(2);
            assertThat(allowance.source()).isEqualTo(QuotaSource.PLAN);
            assertThatExceptionOfType(QuotaExceeded.class).isThrownBy(
                    () -> enforceQuota.require(ORGANIZATION, QuotaResource.DOMAIN, 1));
        }

        @Test
        void anOverrideWithNoExpiryStands() {
            noPlanRowFor(QuotaResource.API_TOKEN);
            overrideOf(QuotaResource.API_TOKEN, 3, null);
            alreadyUsing(QuotaResource.API_TOKEN, 2);

            assertThatNoException().isThrownBy(
                    () -> enforceQuota.require(ORGANIZATION, QuotaResource.API_TOKEN, 1));
        }
    }

    @Nested
    class SuspensionRefusesEverything {

        @Test
        void evenWhenThereIsPlentyOfRoom() {
            given(organizations.findById(ORGANIZATION)).willReturn(Optional.of(
                    active().suspended("Unpaid invoice", Instant.now())));
            planAllows(QuotaResource.PROJECT, 100);
            alreadyUsing(QuotaResource.PROJECT, 0);

            QuotaExceeded refused = catchQuotaExceeded(QuotaResource.PROJECT, 1);

            assertThat(refused.message()).contains("suspended").contains("Unpaid invoice");
            assertThat(refused.allowance().limit()).isEqualTo(100);
        }

        @Test
        void butReadingTheAllowanceStillWorksSoTheScreenCanExplainItself() {
            given(organizations.findById(ORGANIZATION)).willReturn(Optional.of(
                    active().suspended("Unpaid invoice", Instant.now())));
            planAllows(QuotaResource.PROJECT, 100);
            alreadyUsing(QuotaResource.PROJECT, 4);

            QuotaAllowance allowance =
                    enforceQuota.allowanceFor(ORGANIZATION, QuotaResource.PROJECT);

            assertThat(allowance.limit()).isEqualTo(100);
            assertThat(allowance.remaining()).isEqualTo(96);
        }
    }

    @Nested
    class TheWholePicture {

        @Test
        void allowancesAnswersForEveryResourceInDeclarationOrder() {
            Map<QuotaResource, Long> used = new EnumMap<>(QuotaResource.class);
            for (QuotaResource resource : QuotaResource.values()) {
                used.put(resource, 1L);
            }
            given(usage.all(ORGANIZATION)).willReturn(used);
            given(quotas.findByPlanId(PLAN)).willReturn(List.of(
                    Quota.of(PLAN, QuotaResource.PROJECT, 4)));
            given(overrides.findByOrganizationId(ORGANIZATION)).willReturn(List.of(
                    QuotaOverride.granted(ORGANIZATION, QuotaResource.SERVICE, 9, "Migration",
                            null, null),
                    QuotaOverride.granted(ORGANIZATION, QuotaResource.DOMAIN, 99, "Lapsed",
                            Instant.now().minusSeconds(1), null)));

            List<QuotaAllowance> all = enforceQuota.allowances(ORGANIZATION);

            assertThat(all).hasSize(QuotaResource.values().length);
            assertThat(all.stream().map(QuotaAllowance::resource))
                    .containsExactly(QuotaResource.values());
            assertThat(byResource(all, QuotaResource.PROJECT).source()).isEqualTo(QuotaSource.PLAN);
            assertThat(byResource(all, QuotaResource.PROJECT).limit()).isEqualTo(4);
            assertThat(byResource(all, QuotaResource.SERVICE).source())
                    .isEqualTo(QuotaSource.ORGANIZATION_OVERRIDE);
            assertThat(byResource(all, QuotaResource.SERVICE).limit()).isEqualTo(9);
            // The lapsed override is not applied, and there is no plan row behind it.
            assertThat(byResource(all, QuotaResource.DOMAIN).source()).isEqualTo(QuotaSource.UNSET);
            assertThat(byResource(all, QuotaResource.DOMAIN).limit()).isZero();
            assertThat(byResource(all, QuotaResource.MEMBER).used()).isEqualTo(1);
        }
    }

    @Nested
    class Refusals {

        @Test
        void anUnknownOrganizationIsNotFound() {
            UUID missing = UUID.randomUUID();
            given(organizations.findById(missing)).willReturn(Optional.empty());

            assertThatExceptionOfType(NotFoundException.class).isThrownBy(
                    () -> enforceQuota.require(missing, QuotaResource.PROJECT, 1));
        }

        @Test
        void aNegativeAmountIsAProgrammingMistakeAndSaysSo() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> enforceQuota.require(ORGANIZATION, QuotaResource.PROJECT, -1))
                    .withMessageContaining("shrinking resize");
        }
    }

    private static Organization active() {
        return new Organization(ORGANIZATION, "Acme", "acme", PLAN, OrganizationStatus.ACTIVE,
                null, null, Instant.now(), Instant.now(), 1L);
    }

    private void planAllows(QuotaResource resource, long limit) {
        given(quotas.findByPlanIdAndResource(PLAN, resource))
                .willReturn(Optional.of(Quota.of(PLAN, resource, limit)));
    }

    private void noPlanRowFor(QuotaResource resource) {
        given(quotas.findByPlanIdAndResource(PLAN, resource)).willReturn(Optional.empty());
    }

    private void overrideOf(QuotaResource resource, long limit, Instant expiresAt) {
        given(overrides.findByOrganizationIdAndResource(ORGANIZATION, resource))
                .willReturn(Optional.of(QuotaOverride.granted(ORGANIZATION, resource, limit,
                        "Because", expiresAt, null)));
    }

    private void alreadyUsing(QuotaResource resource, long amount) {
        given(usage.of(ORGANIZATION, resource)).willReturn(amount);
    }

    private QuotaExceeded catchQuotaExceeded(QuotaResource resource, long amount) {
        try {
            enforceQuota.require(ORGANIZATION, resource, amount);
        } catch (QuotaExceeded exceeded) {
            return exceeded;
        }
        throw new AssertionError("Expected " + resource + " to be refused");
    }

    private static QuotaAllowance byResource(List<QuotaAllowance> all, QuotaResource resource) {
        return all.stream().filter(allowance -> allowance.resource() == resource)
                .findFirst().orElseThrow();
    }
}
