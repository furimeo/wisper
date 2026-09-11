package lhqm.furimeo.wisper.stats;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything under {@code wisper.stats}: how long readings are kept, how often they are
 * folded up, and how much of them one chart may ask for.
 *
 * <p>The three retentions are the shape of the whole feature. Raw samples answer "what is
 * happening right now" and are enormous - one row per workload per node every fifteen
 * seconds - so they live for two days. Hourly buckets answer "what did last week look
 * like" and daily ones answer "is this growing", and both are small enough to keep for as
 * long as they are interesting. The defaults match {@code schema.md} §3; changing one here
 * changes what the sweep deletes and nothing else, because no query assumes a horizon it
 * has not been told.
 *
 * <p>Declared in the package that reads it (panel-configuration.md), with
 * {@link DefaultValue} on every component: record binding has no constructor fallback, so
 * a missing key binds to null and surfaces as a {@code NullPointerException} inside a
 * scheduled job nobody is watching.
 *
 * @param rawRetention      how long {@code stat_sample} rows live. Two days, so a customer
 *                          can still see the spike that happened while they were asleep at
 *                          full resolution
 * @param hourRetention     how long {@code HOUR} buckets live
 * @param dayRetention      how long {@code DAY} buckets live
 * @param rollupInterval    how often the rollup job runs. Five minutes: a chart that is
 *                          reading buckets is reading history, and history that is five
 *                          minutes stale is history
 * @param rollupLookback    how far back each run recomputes hourly buckets. Must be more
 *                          than one hour, or the bucket that was still filling when the
 *                          job last ran is never completed. Samples also arrive late after
 *                          a node reconnects, and re-running the upsert corrects a bucket
 *                          rather than duplicating it
 * @param backfillWindow    how far back a run will build daily buckets from hourly ones.
 *                          Bounded, because after a long outage the alternative is one job
 *                          run that folds up a year
 * @param sweepInterval     how often expired rows are deleted
 * @param sweepBatchSize    rows per {@code DELETE}. An unbounded delete over two days of
 *                          samples holds locks long enough for everything else on the pool
 *                          to notice
 * @param maxFutureSkew     how far ahead of the panel's clock a sample may claim to have
 *                          been taken. A node whose clock is wrong would otherwise write
 *                          buckets in the future that nothing ever reads and the sweep
 *                          never reaches
 * @param maxSeriesPoints   the most points one chart request may return. Past this the
 *                          query moves to a coarser source rather than refusing: a phone
 *                          rendering ten thousand points draws nothing at all
 * @param liveWindow        how much history the live chart shows before it starts
 *                          following
 * @param streamKeepAlive   how often an idle SSE stream sends a comment. A tunnel closes a
 *                          connection that has said nothing, and a chart with no traffic on
 *                          it says nothing for minutes at a time
 * @param streamMaxDuration how long one SSE connection lives before the browser is asked
 *                          to reconnect. Finite on purpose: an immortal stream through a
 *                          tunnel that has silently gone holds a servlet async context
 *                          until the process restarts
 */
@ConfigurationProperties("wisper.stats")
public record StatsSettings(
        @DefaultValue("48h") Duration rawRetention,
        @DefaultValue("30d") Duration hourRetention,
        @DefaultValue("400d") Duration dayRetention,
        @DefaultValue("5m") Duration rollupInterval,
        @DefaultValue("3h") Duration rollupLookback,
        @DefaultValue("7d") Duration backfillWindow,
        @DefaultValue("1h") Duration sweepInterval,
        @DefaultValue("5000") int sweepBatchSize,
        @DefaultValue("5m") Duration maxFutureSkew,
        @DefaultValue("720") int maxSeriesPoints,
        @DefaultValue("30m") Duration liveWindow,
        @DefaultValue("20s") Duration streamKeepAlive,
        @DefaultValue("30m") Duration streamMaxDuration) {

    public StatsSettings {
        requirePositive("raw-retention", rawRetention);
        requirePositive("hour-retention", hourRetention);
        requirePositive("day-retention", dayRetention);
        requirePositive("rollup-interval", rollupInterval);
        requirePositive("sweep-interval", sweepInterval);
        requirePositive("stream-keep-alive", streamKeepAlive);
        requirePositive("stream-max-duration", streamMaxDuration);
        if (rollupLookback.compareTo(Duration.ofHours(1)) <= 0) {
            throw new IllegalArgumentException("wisper.stats.rollup-lookback must be longer than "
                    + "an hour, or the bucket that was still filling when the job last ran is "
                    + "never completed. It was " + rollupLookback + ".");
        }
        if (hourRetention.compareTo(rawRetention) < 0) {
            throw new IllegalArgumentException("wisper.stats.hour-retention must not be shorter "
                    + "than raw-retention, or a chart would lose history as it got older instead "
                    + "of getting coarser.");
        }
        if (dayRetention.compareTo(hourRetention) < 0) {
            throw new IllegalArgumentException("wisper.stats.day-retention must not be shorter "
                    + "than hour-retention, for the same reason.");
        }
        if (sweepBatchSize <= 0) {
            throw new IllegalArgumentException(
                    "wisper.stats.sweep-batch-size must be positive, not " + sweepBatchSize);
        }
        if (maxSeriesPoints <= 0) {
            throw new IllegalArgumentException(
                    "wisper.stats.max-series-points must be positive, not " + maxSeriesPoints);
        }
    }

    private static void requirePositive(String key, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "wisper.stats." + key + " must be a positive duration, not " + value);
        }
    }
}
