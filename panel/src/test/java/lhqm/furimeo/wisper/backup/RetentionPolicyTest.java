package lhqm.furimeo.wisper.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The retention arithmetic, which is the part of this package that decides what a customer
 * still has when they need it.
 *
 * <p>Every case here is one somebody has been bitten by. The grandfather-father-son rule
 * looks obvious written down and has three traps in it: a tier counting archives rather than
 * buckets, a day boundary taken in the wrong timezone, and an age rule that outranks
 * "keep the newest" and quietly deletes a dormant project's only backup.
 *
 * <p>Over 300 lines and deliberately not split (AGENTS.md §3.2): this is one pure function
 * with four tiers and a timezone, and the cases only mean anything read against each other.
 * The {@code @Nested} classes are the split.
 */
class RetentionPolicyTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private static final ZoneId SAIGON = ZoneId.of("Asia/Ho_Chi_Minh");

    @Nested
    class KeepingTheNewest {

        @Test
        void everythingIsKeptWhenThereIsLessThanTheCount() {
            RetentionPolicy policy = new RetentionPolicy(7, 7, 4, 12, UTC);

            RetentionDecision decision = policy.select(daily("2026-03-01T03:00:00Z", 3));

            assertThat(decision.keptCount()).isEqualTo(3);
            assertThat(decision.expired()).isEmpty();
        }

        @Test
        void theNewestFewAreKeptWhateverTheirAge() {
            // One a year, so no daily, weekly or monthly bucket can be claimed twice by the
            // same snapshot and keepLast is the only rule that can save anything.
            List<RetainedSnapshot> yearly = List.of(
                    snapshot("2026-01-01T00:00:00Z"),
                    snapshot("2025-01-01T00:00:00Z"),
                    snapshot("2024-01-01T00:00:00Z"),
                    snapshot("2023-01-01T00:00:00Z"));
            RetentionPolicy policy = new RetentionPolicy(2, 0, 0, 0, UTC);

            RetentionDecision decision = policy.select(yearly);

            assertThat(decision.kept()).containsExactly(yearly.get(0).id(), yearly.get(1).id());
            assertThat(decision.expired()).containsExactly(yearly.get(2).id(), yearly.get(3).id());
        }

        @Test
        void aDormantProjectsOnlySnapshotIsNeverExpired() {
            // Years old, every tier switched off. keepLast is clamped to at least one
            // precisely so that this cannot come back empty.
            RetentionPolicy policy = new RetentionPolicy(0, 0, 0, 0, UTC);

            RetentionDecision decision = policy.select(List.of(snapshot("2019-06-01T00:00:00Z")));

            assertThat(decision.expired()).isEmpty();
            assertThat(decision.keptCount()).isEqualTo(1);
        }
    }

    @Nested
    class TheDailyTier {

        @Test
        void keepsOneSnapshotPerDayForAsManyDaysAsTheTierAllows() {
            RetentionPolicy policy = new RetentionPolicy(1, 7, 0, 0, UTC);

            RetentionDecision decision = policy.select(daily("2026-03-10T03:00:00Z", 10));

            assertThat(decision.keptCount()).isEqualTo(7);
            assertThat(decision.expiredCount()).isEqualTo(3);
        }

        @Test
        void countsDaysAndNotSnapshots() {
            // Three a day for four days: a tier that counted archives would keep seven and
            // cover two days. It has to keep four, one for each of the last four days.
            List<RetainedSnapshot> thrice = new ArrayList<>();
            for (int day = 0; day < 4; day++) {
                thrice.add(snapshot("2026-03-0" + (day + 1) + "T02:00:00Z"));
                thrice.add(snapshot("2026-03-0" + (day + 1) + "T10:00:00Z"));
                thrice.add(snapshot("2026-03-0" + (day + 1) + "T18:00:00Z"));
            }
            RetentionPolicy policy = new RetentionPolicy(1, 7, 0, 0, UTC);

            RetentionDecision decision = policy.select(thrice);

            assertThat(decision.keptCount()).isEqualTo(4);
            assertThat(decision.expiredCount()).isEqualTo(8);
        }

        @Test
        void keepsTheNewestSnapshotOfEachDay() {
            RetainedSnapshot morning = snapshot("2026-03-01T03:00:00Z");
            RetainedSnapshot evening = snapshot("2026-03-01T21:00:00Z");
            RetentionPolicy policy = new RetentionPolicy(1, 7, 0, 0, UTC);

            RetentionDecision decision = policy.select(List.of(morning, evening));

            assertThat(decision.kept()).containsExactly(evening.id());
            assertThat(decision.expired()).containsExactly(morning.id());
        }

        @Test
        void aDayEndsWhereThePolicysTimezoneSaysItDoes() {
            // 23:30 UTC and 00:30 UTC the next day are two days apart in London and the same
            // morning in Ho Chi Minh City. A customer who asked for one a day gets one a day
            // in their own reckoning, not in the server's.
            List<RetainedSnapshot> straddling = List.of(
                    snapshot("2026-01-01T23:30:00Z"),
                    snapshot("2026-01-02T00:30:00Z"));

            assertThat(new RetentionPolicy(1, 2, 0, 0, UTC).select(straddling).keptCount())
                    .isEqualTo(2);
            assertThat(new RetentionPolicy(1, 2, 0, 0, SAIGON).select(straddling).keptCount())
                    .isEqualTo(1);
        }
    }

    @Nested
    class TheWeeklyAndMonthlyTiers {

        @Test
        void weeklyKeepsOnePerIsoWeek() {
            // Four Wednesdays, so four distinct ISO weeks.
            List<RetainedSnapshot> wednesdays = List.of(
                    snapshot("2026-03-25T03:00:00Z"),
                    snapshot("2026-03-18T03:00:00Z"),
                    snapshot("2026-03-11T03:00:00Z"),
                    snapshot("2026-03-04T03:00:00Z"));
            RetentionPolicy policy = new RetentionPolicy(1, 0, 3, 0, UTC);

            RetentionDecision decision = policy.select(wednesdays);

            assertThat(decision.keptCount()).isEqualTo(3);
            assertThat(decision.expired()).containsExactly(wednesdays.get(3).id());
        }

        @Test
        void twoWeekOnesAYearApartAreNotTheSameBucket() {
            // Both are ISO week 1, of different week-based years. Keying on the week number
            // alone would collapse them and silently drop a year-old archive the rule says
            // to keep.
            RetainedSnapshot week1of2020 = snapshot("2020-01-02T03:00:00Z");
            RetainedSnapshot week1of2021 = snapshot("2021-01-04T03:00:00Z");
            RetentionPolicy policy = new RetentionPolicy(1, 0, 2, 0, UTC);

            RetentionDecision decision = policy.select(List.of(week1of2021, week1of2020));

            assertThat(decision.expired()).isEmpty();
            assertThat(decision.kept()).containsExactly(week1of2021.id(), week1of2020.id());
        }

        @Test
        void monthlyKeepsOnePerCalendarMonth() {
            List<RetainedSnapshot> monthly = List.of(
                    snapshot("2026-03-15T03:00:00Z"),
                    snapshot("2026-02-15T03:00:00Z"),
                    snapshot("2026-01-15T03:00:00Z"));
            RetentionPolicy policy = new RetentionPolicy(1, 0, 0, 2, UTC);

            RetentionDecision decision = policy.select(monthly);

            assertThat(decision.kept()).containsExactly(monthly.get(0).id(), monthly.get(1).id());
            assertThat(decision.expired()).containsExactly(monthly.get(2).id());
        }

        @Test
        void thePlatformDefaultOverThreeMonthsOfNightlies() {
            // Ninety nightly snapshots under 7 daily, 4 weekly, 3 monthly: seven days, then
            // the weeks and the months the dailies do not already cover.
            RetentionPolicy policy = new RetentionPolicy(1, 7, 4, 3, UTC);

            RetentionDecision decision = policy.select(daily("2026-03-31T03:00:00Z", 90));

            // Seven consecutive days ending on a Tuesday already cover the two newest ISO
            // weeks, so the weekly tier adds only the two before them; the three months are
            // March, February and January, and March is covered by the dailies. Seven plus
            // two plus two.
            assertThat(decision.keptCount()).isEqualTo(11);
            assertThat(decision.expiredCount()).isEqualTo(79);
            assertThat(decision.kept()).doesNotHaveDuplicates();
        }
    }

    @Nested
    class DerivedFromThePolicyRow {

        @Test
        void theTiersAreCappedByTheDaysTheCustomerAskedFor() {
            RetentionPolicy thirtyDays = RetentionPolicy.of(
                    BackupFixture.policyKeeping(5, 30, "UTC"), BackupFixture.settings());

            assertThat(thirtyDays.keepLast()).isEqualTo(5);
            assertThat(thirtyDays.keepDaily()).isEqualTo(7);
            assertThat(thirtyDays.keepWeekly()).isEqualTo(4);
            assertThat(thirtyDays.keepMonthly()).isEqualTo(1);
        }

        @Test
        void aThreeDayPolicyKeepsNoWeekliesAndNoMonthlies() {
            RetentionPolicy threeDays = RetentionPolicy.of(
                    BackupFixture.policyKeeping(2, 3, "UTC"), BackupFixture.settings());

            assertThat(threeDays.keepDaily()).isEqualTo(3);
            assertThat(threeDays.keepWeekly()).isZero();
            assertThat(threeDays.keepMonthly()).isZero();
        }

        @Test
        void aYearLongPolicyReachesThePlatformCeilings() {
            RetentionPolicy aYear = RetentionPolicy.of(
                    BackupFixture.policyKeeping(10, 365, "UTC"), BackupFixture.settings());

            assertThat(aYear.keepDaily()).isEqualTo(7);
            assertThat(aYear.keepWeekly()).isEqualTo(4);
            assertThat(aYear.keepMonthly()).isEqualTo(12);
        }

        @Test
        void thePolicysTimezoneIsWhatBucketsTheDays() {
            RetentionPolicy saigon = RetentionPolicy.of(
                    BackupFixture.policyKeeping(1, 30, "Asia/Ho_Chi_Minh"),
                    BackupFixture.settings());

            assertThat(saigon.zone()).isEqualTo(SAIGON);
        }

        @Test
        void aTimezoneNobodyRecognisesFallsBackToUtcRatherThanStoppingTheSweep() {
            RetentionPolicy broken = RetentionPolicy.of(
                    BackupFixture.policyKeeping(1, 30, "Mars/Olympus_Mons"),
                    BackupFixture.settings());

            assertThat(broken.zone()).isEqualTo(UTC);
        }
    }

    @Nested
    class Edges {

        @Test
        void nothingInDecidesNothing() {
            RetentionPolicy policy = new RetentionPolicy(7, 7, 4, 12, UTC);

            assertThat(policy.select(List.of()).changesAnything()).isFalse();
            assertThat(policy.select(null).kept()).isEmpty();
        }

        @Test
        void theAnswerDoesNotDependOnTheOrderTheRowsArrivedIn() {
            List<RetainedSnapshot> newestFirst = daily("2026-03-10T03:00:00Z", 10);
            List<RetainedSnapshot> shuffled = new ArrayList<>(newestFirst);
            Collections.reverse(shuffled);
            RetentionPolicy policy = new RetentionPolicy(2, 5, 2, 1, UTC);

            assertThat(policy.select(shuffled).kept())
                    .isEqualTo(policy.select(newestFirst).kept());
        }

        @Test
        void keptAndExpiredPartitionTheInput() {
            List<RetainedSnapshot> snapshots = daily("2026-03-31T03:00:00Z", 45);
            RetentionPolicy policy = new RetentionPolicy(3, 7, 4, 2, UTC);

            RetentionDecision decision = policy.select(snapshots);

            assertThat(decision.kept()).doesNotHaveDuplicates();
            assertThat(decision.expired()).doesNotHaveDuplicates();
            assertThat(decision.kept()).doesNotContainAnyElementsOf(decision.expired());
            assertThat(decision.keptCount() + decision.expiredCount()).isEqualTo(snapshots.size());
        }

        @Test
        void keptIsOrderedNewestFirst() {
            RetentionPolicy policy = new RetentionPolicy(3, 0, 0, 0, UTC);
            List<RetainedSnapshot> snapshots = daily("2026-03-10T03:00:00Z", 5);

            List<UUID> kept = policy.select(snapshots).kept();

            assertThat(kept).containsExactly(snapshots.get(0).id(), snapshots.get(1).id(),
                    snapshots.get(2).id());
        }
    }

    /** {@code count} snapshots, one a day, the newest at {@code newest}, newest first. */
    private static List<RetainedSnapshot> daily(String newest, int count) {
        Instant start = Instant.parse(newest);
        List<RetainedSnapshot> snapshots = new ArrayList<>(count);
        for (int day = 0; day < count; day++) {
            snapshots.add(new RetainedSnapshot(UUID.randomUUID(),
                    start.minusSeconds(86_400L * day)));
        }
        return snapshots;
    }

    private static RetainedSnapshot snapshot(String at) {
        return new RetainedSnapshot(UUID.randomUUID(), Instant.parse(at));
    }
}
