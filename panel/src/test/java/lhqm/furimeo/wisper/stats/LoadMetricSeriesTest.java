package lhqm.furimeo.wisper.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * Which table answers a window.
 *
 * <p>Both ways of choosing badly look like a working page. A month read out of
 * {@code stat_sample} comes back empty, because raw samples are deleted after two days; ten
 * minutes read out of {@code stat_rollup} comes back as one hourly bar. Neither raises
 * anything, so this is the test that says what the rule is.
 *
 * <p>No {@code JdbcClient} is needed: the choice is arithmetic over the clock and the
 * retentions, which is exactly why it is a method of its own.
 */
class LoadMetricSeriesTest {

    private static final Instant NOW = Instant.parse("2026-03-10T10:00:00Z");

    private static final StatsSettings SETTINGS = new StatsSettings(
            Duration.ofHours(48), Duration.ofDays(30), Duration.ofDays(400),
            Duration.ofMinutes(5), Duration.ofHours(3), Duration.ofDays(7),
            Duration.ofHours(1), 5000, Duration.ofMinutes(5), 720,
            Duration.ofMinutes(30), Duration.ofSeconds(20), Duration.ofMinutes(30));

    private final LoadMetricSeries series =
            new LoadMetricSeries(null, SETTINGS, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void theLastHalfHourComesFromRawSamples() {
        assertThat(sourceForLast(Duration.ofMinutes(30))).isEqualTo(MetricSource.RAW);
    }

    @Test
    void aWindowInsideRetentionButTooDenseToDrawMovesToHourlyBuckets() {
        // Six hours of fifteen-second samples is 1,440 points; a phone renders none of them.
        assertThat(sourceForLast(Duration.ofHours(6))).isEqualTo(MetricSource.HOUR);
    }

    @Test
    void aWeekComesFromHourlyBuckets() {
        assertThat(sourceForLast(Duration.ofDays(7))).isEqualTo(MetricSource.HOUR);
    }

    @Test
    void aWindowOlderThanTheHourlyRetentionComesFromDailyBuckets() {
        assertThat(sourceForLast(Duration.ofDays(120))).isEqualTo(MetricSource.DAY);
    }

    @Test
    void aWindowThatStartsBeforeTheRawHorizonIsNeverAnsweredFromRaw() {
        // Two hours wide, so it is sparse enough for raw - but it begins three days ago,
        // and those rows were deleted. Answering from raw would draw an empty chart.
        Instant from = NOW.minus(Duration.ofDays(3));
        assertThat(series.sourceFor(from, from.plus(Duration.ofHours(2))))
                .isEqualTo(MetricSource.HOUR);
    }

    @Test
    void bucketsAreSnappedToTheGridTheCheckConstraintEnforces() {
        Instant awkward = Instant.parse("2026-03-10T10:37:19Z");

        assertThat(MetricSource.HOUR.floorOf(awkward))
                .isEqualTo(Instant.parse("2026-03-10T10:00:00Z"));
        assertThat(MetricSource.DAY.floorOf(awkward))
                .isEqualTo(Instant.parse("2026-03-10T00:00:00Z"));
        // A raw point is not a bucket and must not be moved.
        assertThat(MetricSource.RAW.floorOf(awkward)).isEqualTo(awkward);
    }

    private MetricSource sourceForLast(Duration window) {
        return series.sourceFor(NOW.minus(window), NOW);
    }
}
