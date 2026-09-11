package lhqm.furimeo.wisper.stats;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Which spans of time one rollup run recomputes, and from which table.
 *
 * <p>All the arithmetic that decides what a bucket contains lives here, away from the SQL,
 * because getting a boundary wrong is silent: the chart still draws, the numbers are just
 * quietly wrong at the edges. The three windows below are the three ways that happens.
 *
 * <h2>Hourly buckets are recomputed, not appended</h2>
 *
 * <p>The bucket covering "now" is still filling. Writing it once and moving on would leave
 * every hour permanently missing the samples that arrived after the job ran, so each run
 * recomputes the last {@code rollup-lookback} of hours and the upsert corrects them. That
 * also fixes the other case the design guarantees: a node that reconnects backfills samples
 * with old timestamps, and a bucket that was complete an hour ago no longer is.
 *
 * <h2>Daily buckets come from two places, and the seam has to be exact</h2>
 *
 * <p>{@code schema.md} §3: a daily bucket is computed from raw samples where they still
 * exist and from hourly buckets weighted by {@code sample_count} where they do not.
 * Averaging averages loses accuracy, so raw is preferred - but only for days the raw table
 * covers <em>completely</em>. The day that contains the raw horizon is half deleted, and
 * building it from what is left would report a quiet morning as the whole day.
 *
 * <p>So the seam is the first midnight after the horizon: everything from there on comes
 * from raw, everything before it comes from hourly buckets. No overlap, no gap, and the
 * hourly branch never overwrites a raw-derived bucket because it inserts with
 * {@code DO NOTHING}.
 */
public record RollupWindows(Window hour, Window dayFromSamples, Window dayFromHours) {

    /** A half-open span: {@code from} included, {@code to} excluded, like every other range. */
    public record Window(Instant from, Instant to) {

        /** Nothing to do. */
        public static final Window NONE = new Window(Instant.EPOCH, Instant.EPOCH);

        public boolean isEmpty() {
            return !from.isBefore(to);
        }

        public Duration length() {
            return isEmpty() ? Duration.ZERO : Duration.between(from, to);
        }
    }

    /**
     * The windows for a run happening at {@code now}.
     *
     * @param settings the retentions and the lookback
     */
    public static RollupWindows at(Instant now, StatsSettings settings) {
        Instant currentHour = now.truncatedTo(ChronoUnit.HOURS);
        Window hour = new Window(
                now.minus(settings.rollupLookback()).truncatedTo(ChronoUnit.HOURS),
                // The hour that is still filling is included, so it is complete by the time
                // anybody looks at it and correct again on the next run.
                currentHour.plus(1, ChronoUnit.HOURS));

        Instant rawHorizon = now.minus(settings.rawRetention());
        // The first midnight after the oldest surviving sample: the earliest day the raw
        // table can still describe in full.
        Instant firstWholeRawDay = rawHorizon.truncatedTo(ChronoUnit.DAYS)
                .plus(1, ChronoUnit.DAYS);
        Instant tomorrow = now.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS);

        Window fromSamples = firstWholeRawDay.isBefore(tomorrow)
                ? new Window(firstWholeRawDay, tomorrow)
                : Window.NONE;

        Instant backfillFloor = now.minus(settings.backfillWindow()).truncatedTo(ChronoUnit.DAYS);
        Window fromHours = backfillFloor.isBefore(firstWholeRawDay)
                ? new Window(backfillFloor, firstWholeRawDay)
                : Window.NONE;

        return new RollupWindows(hour, fromSamples, fromHours);
    }
}
