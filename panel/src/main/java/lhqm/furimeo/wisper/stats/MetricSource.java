package lhqm.furimeo.wisper.stats;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Where a series was read from, and how wide its points are.
 *
 * <p>Three resolutions for one reason: raw samples are kept for two days and buckets are
 * kept for a year, so the answer to "show me last March" has to come from somewhere else
 * than the answer to "show me the last ten minutes". Which one was used is returned to the
 * browser rather than inferred, because a chart that says "hourly averages" next to its
 * axis is honest and one that silently changes resolution mid-zoom is not.
 *
 * <p>The two bucket widths match {@code stat_rollup.granularity} exactly - the CHECK on
 * that column accepts {@code HOUR} and {@code DAY} and nothing else - so
 * {@link #granularity()} is the value written into the table.
 */
public enum MetricSource {

    /** {@code stat_sample}: every reading a node pushed, at its own cadence. */
    RAW(Duration.ofSeconds(15), null),

    /** {@code stat_rollup} with {@code granularity = 'HOUR'}. */
    HOUR(Duration.ofHours(1), "HOUR"),

    /** {@code stat_rollup} with {@code granularity = 'DAY'}. */
    DAY(Duration.ofDays(1), "DAY");

    private final Duration bucket;
    private final String granularity;

    MetricSource(Duration bucket, String granularity) {
        this.bucket = bucket;
        this.granularity = granularity;
    }

    /** How much time one point covers. Nominal for {@link #RAW}, whose cadence is a node's. */
    public Duration bucket() {
        return bucket;
    }

    /** The {@code stat_rollup.granularity} value, or null for {@link #RAW}. */
    public String granularity() {
        return granularity;
    }

    /** Whether this reads the raw table. */
    public boolean isRaw() {
        return this == RAW;
    }

    /**
     * An instant truncated to the start of its bucket, in UTC.
     *
     * <p>The same arithmetic as the {@code stat_rollup_bucket_aligned} CHECK. Doing it in
     * Java as well means a window's edges land on bucket boundaries before the query runs,
     * so a chart never shows a first bar that is a third of an hour tall.
     */
    public Instant floorOf(Instant at) {
        return switch (this) {
            case RAW -> at;
            case HOUR -> at.truncatedTo(ChronoUnit.HOURS);
            case DAY -> at.truncatedTo(ChronoUnit.DAYS);
        };
    }

    /** How many points a window of this width would produce, at least one. */
    public long pointsIn(Duration window) {
        if (this == RAW) {
            return Math.max(1, window.dividedBy(bucket));
        }
        return Math.max(1, window.dividedBy(bucket) + 1);
    }
}
