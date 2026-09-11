package lhqm.furimeo.wisper.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Where one bucket ends and the next begins.
 *
 * <p>Every failure this guards against is silent. A rollup that stops at the hour boundary
 * leaves the hour that was still filling permanently short of the samples that arrived
 * after the job ran; a daily bucket built from a day the raw table has half deleted reports
 * a quiet morning as the whole day; a gap between the raw-derived days and the
 * hourly-derived ones leaves a hole in a year-long chart that nothing ever fills. In all
 * three cases the chart still draws.
 */
class RollupWindowsTest {

    private static final StatsSettings SETTINGS = new StatsSettings(
            Duration.ofHours(48), Duration.ofDays(30), Duration.ofDays(400),
            Duration.ofMinutes(5), Duration.ofHours(3), Duration.ofDays(7),
            Duration.ofHours(1), 5000, Duration.ofMinutes(5), 720,
            Duration.ofMinutes(30), Duration.ofSeconds(20), Duration.ofMinutes(30));

    /** Deliberately not on any boundary: 10:37:19 is what a scheduler actually fires at. */
    private static final Instant NOW = Instant.parse("2026-03-10T10:37:19Z");

    @Test
    void theHourlyWindowStartsOnAnHourAndIncludesTheOneStillFilling() {
        RollupWindows windows = RollupWindows.at(NOW, SETTINGS);

        assertThat(windows.hour().from()).isEqualTo(Instant.parse("2026-03-10T07:00:00Z"));
        // 11:00, not 10:37: the bucket covering "now" has to be in the window or it is
        // never completed, and the upsert corrects it on every subsequent run.
        assertThat(windows.hour().to()).isEqualTo(Instant.parse("2026-03-10T11:00:00Z"));
    }

    @Test
    void theHourlyLookbackCoversTheBucketBeforeLastSoALateSampleStillLands() {
        RollupWindows windows = RollupWindows.at(NOW, SETTINGS);

        assertThat(windows.hour().length()).isEqualTo(Duration.ofHours(4));
    }

    @Test
    void dailyBucketsComeFromRawOnlyForDaysTheRawTableStillCoversInFull() {
        RollupWindows windows = RollupWindows.at(NOW, SETTINGS);

        // The oldest surviving sample is from 2026-03-08T10:37, so 03-08 is half deleted
        // and building it from what is left would report half a day as a whole one.
        assertThat(windows.dayFromSamples().from())
                .isEqualTo(Instant.parse("2026-03-09T00:00:00Z"));
        assertThat(windows.dayFromSamples().to()).isEqualTo(Instant.parse("2026-03-11T00:00:00Z"));
    }

    @Test
    void theTwoDailySourcesMeetExactlyWithNoGapAndNoOverlap() {
        RollupWindows windows = RollupWindows.at(NOW, SETTINGS);

        assertThat(windows.dayFromHours().to()).isEqualTo(windows.dayFromSamples().from());
        assertThat(windows.dayFromHours().from()).isEqualTo(Instant.parse("2026-03-03T00:00:00Z"));
    }

    @Test
    void theBackfillFromHourlyBucketsIsBoundedSoOneRunCannotFoldUpAYear() {
        RollupWindows windows = RollupWindows.at(NOW, SETTINGS);

        assertThat(windows.dayFromHours().length()).isEqualTo(Duration.ofDays(6));
    }

    @Test
    void atExactlyMidnightTheSeamStaysOnADayBoundary() {
        RollupWindows windows = RollupWindows.at(Instant.parse("2026-03-10T00:00:00Z"), SETTINGS);

        assertThat(windows.dayFromSamples().from())
                .isEqualTo(Instant.parse("2026-03-09T00:00:00Z"));
        assertThat(windows.dayFromSamples().to()).isEqualTo(Instant.parse("2026-03-11T00:00:00Z"));
        assertThat(windows.dayFromHours().to()).isEqualTo(windows.dayFromSamples().from());
    }

    @Test
    void aBackfillWindowInsideTheRawWindowLeavesNothingForTheHourlySource() {
        StatsSettings shortBackfill = new StatsSettings(
                Duration.ofHours(48), Duration.ofDays(30), Duration.ofDays(400),
                Duration.ofMinutes(5), Duration.ofHours(3), Duration.ofHours(6),
                Duration.ofHours(1), 5000, Duration.ofMinutes(5), 720,
                Duration.ofMinutes(30), Duration.ofSeconds(20), Duration.ofMinutes(30));

        RollupWindows windows = RollupWindows.at(NOW, shortBackfill);

        // Six hours back is still today, which raw covers, so there is nothing older for
        // the hourly branch to build and it must not run over the raw-derived days.
        assertThat(windows.dayFromHours().isEmpty()).isTrue();
    }

    @Test
    void aWindowThatEndsWhereItStartsIsEmptyRatherThanNegative() {
        assertThat(RollupWindows.Window.NONE.isEmpty()).isTrue();
        assertThat(RollupWindows.Window.NONE.length()).isEqualTo(Duration.ZERO);
    }
}
