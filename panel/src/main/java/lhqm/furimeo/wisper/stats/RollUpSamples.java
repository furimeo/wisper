package lhqm.furimeo.wisper.stats;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Folds raw samples into hourly buckets and hourly buckets into daily ones.
 *
 * <p>Three set-based upserts, run every {@code wisper.stats.rollup-interval}. Which spans
 * they cover is {@link RollupWindows}; this class is the SQL.
 *
 * <h2>Why the ids come from the database here and nowhere else</h2>
 *
 * <p>{@code schema.md} §1 says a primary key is generated in Java before the insert,
 * because an id that only exists after a round trip cannot be logged or referenced. Neither
 * applies to a rollup bucket: nothing points at one, nothing logs one, and there are
 * thousands per run. Generating them in Java would mean reading every group back into the
 * panel and writing it out again - three statements become sixty thousand - to obtain a
 * value nothing will ever read. {@code gen_random_uuid()} is built in from PostgreSQL 13.
 *
 * <h2>Why it is an upsert</h2>
 *
 * <p>{@code stat_rollup_bucket_key} makes {@code (granularity, node, service, bucket)}
 * unique with {@code NULLS NOT DISTINCT}, so re-running a window corrects its buckets
 * instead of duplicating them. That is what lets the job recompute the hour that was still
 * filling last time, and what makes a node's backfill after a reconnect land in the right
 * bucket rather than beside it.
 *
 * <h2>Averages are weighted, and only ever taken once</h2>
 *
 * <p>An hourly average weights each sample by {@code window_seconds}: a node that sampled
 * every fifteen seconds for half an hour and every minute for the other half must not have
 * its quiet half count four times over. A daily average built from hourly ones weights by
 * {@code sample_count} for the same reason - and is only used for days the raw table can no
 * longer describe, because an average of averages of averages is a number that means
 * nothing.
 */
@Component
public class RollUpSamples {

    private static final Logger log = LoggerFactory.getLogger(RollUpSamples.class);

    private static final String UPSERT_COLUMNS = """
            INSERT INTO stat_rollup (id, node_id, service_id, granularity, bucket_start,
                                     sample_count, cpu_millicores_avg, cpu_millicores_max,
                                     memory_bytes_avg, memory_bytes_max, disk_bytes_max,
                                     network_rx_bytes, network_tx_bytes, disk_read_bytes,
                                     disk_write_bytes, restart_count, created_at, updated_at)
            """;

    /** Re-running a window corrects the bucket; every measured column is replaced. */
    private static final String ON_CONFLICT_REPLACE = """
            ON CONFLICT (granularity, node_id, service_id, bucket_start) DO UPDATE SET
                sample_count       = EXCLUDED.sample_count,
                cpu_millicores_avg = EXCLUDED.cpu_millicores_avg,
                cpu_millicores_max = EXCLUDED.cpu_millicores_max,
                memory_bytes_avg   = EXCLUDED.memory_bytes_avg,
                memory_bytes_max   = EXCLUDED.memory_bytes_max,
                disk_bytes_max     = EXCLUDED.disk_bytes_max,
                network_rx_bytes   = EXCLUDED.network_rx_bytes,
                network_tx_bytes   = EXCLUDED.network_tx_bytes,
                disk_read_bytes    = EXCLUDED.disk_read_bytes,
                disk_write_bytes   = EXCLUDED.disk_write_bytes,
                restart_count      = EXCLUDED.restart_count,
                updated_at         = now()
            """;

    /**
     * One granularity's worth of buckets, straight out of {@code stat_sample}.
     *
     * <p>{@code %s} is the {@code date_trunc} unit and the granularity literal, both of
     * which come from {@link MetricSource} and never from a request.
     */
    private static final String FROM_SAMPLES = UPSERT_COLUMNS + """
            SELECT gen_random_uuid(), s.node_id, s.service_id, '%1$s',
                   date_trunc('%2$s', s.sampled_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC',
                   count(*)::int,
                   sum(s.cpu_millicores * s.window_seconds) / sum(s.window_seconds),
                   max(s.cpu_millicores),
                   sum(s.memory_bytes * s.window_seconds) / sum(s.window_seconds),
                   max(s.memory_bytes),
                   max(s.disk_bytes),
                   sum(s.network_rx_bytes), sum(s.network_tx_bytes),
                   sum(s.disk_read_bytes), sum(s.disk_write_bytes),
                   sum(s.restart_count)::int,
                   now(), now()
              FROM stat_sample s
             WHERE s.sampled_at >= :from AND s.sampled_at < :to
             GROUP BY s.node_id, s.service_id,
                      date_trunc('%2$s', s.sampled_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
            """ + ON_CONFLICT_REPLACE;

    /**
     * Daily buckets for days the raw table no longer covers.
     *
     * <p>{@code DO NOTHING}, not {@code DO UPDATE}: if a raw-derived bucket for that day
     * already exists it is the more accurate one, and this pass must never coarsen it.
     */
    private static final String DAYS_FROM_HOURS = UPSERT_COLUMNS + """
            SELECT gen_random_uuid(), r.node_id, r.service_id, 'DAY',
                   date_trunc('day', r.bucket_start AT TIME ZONE 'UTC') AT TIME ZONE 'UTC',
                   sum(r.sample_count)::int,
                   sum(r.cpu_millicores_avg * r.sample_count) / sum(r.sample_count),
                   max(r.cpu_millicores_max),
                   sum(r.memory_bytes_avg * r.sample_count) / sum(r.sample_count),
                   max(r.memory_bytes_max),
                   max(r.disk_bytes_max),
                   sum(r.network_rx_bytes), sum(r.network_tx_bytes),
                   sum(r.disk_read_bytes), sum(r.disk_write_bytes),
                   sum(r.restart_count)::int,
                   now(), now()
              FROM stat_rollup r
             WHERE r.granularity = 'HOUR'
               AND r.bucket_start >= :from AND r.bucket_start < :to
             GROUP BY r.node_id, r.service_id,
                      date_trunc('day', r.bucket_start AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
            ON CONFLICT (granularity, node_id, service_id, bucket_start) DO NOTHING
            """;

    private final JdbcClient jdbc;
    private final StatsSettings settings;
    private final Clock clock;

    public RollUpSamples(JdbcClient jdbc, StatsSettings settings) {
        this(jdbc, settings, Clock.systemUTC());
    }

    RollUpSamples(JdbcClient jdbc, StatsSettings settings, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Recomputes every window that is due.
     *
     * <p>One transaction. The three statements are a consistent view of the same moment,
     * and a run that fails half way leaves the previous buckets in place rather than a
     * mixture of two runs - which is a chart that disagrees with itself across a day
     * boundary.
     *
     * @return how many bucket rows were written or corrected
     */
    @Transactional
    public int sweep() {
        RollupWindows windows = RollupWindows.at(Instant.now(clock), settings);

        int hours = upsertFromSamples(MetricSource.HOUR, windows.hour());
        int days = upsertFromSamples(MetricSource.DAY, windows.dayFromSamples());
        int backfilled = backfillDaysFromHours(windows.dayFromHours());

        int total = hours + days + backfilled;
        if (total > 0) {
            log.debug("Rolled up {} hourly and {} daily bucket(s), and backfilled {} day(s) from "
                    + "hourly buckets", hours, days, backfilled);
        }
        return total;
    }

    private int upsertFromSamples(MetricSource source, RollupWindows.Window window) {
        if (window.isEmpty()) {
            return 0;
        }
        String unit = source == MetricSource.HOUR ? "hour" : "day";
        return jdbc.sql(FROM_SAMPLES.formatted(source.granularity(), unit))
                .param("from", timestamp(window.from()))
                .param("to", timestamp(window.to()))
                .update();
    }

    private int backfillDaysFromHours(RollupWindows.Window window) {
        if (window.isEmpty()) {
            return 0;
        }
        return jdbc.sql(DAYS_FROM_HOURS)
                .param("from", timestamp(window.from()))
                .param("to", timestamp(window.to()))
                .update();
    }

    /**
     * PgJDBC cannot bind an {@link Instant}: it refuses to infer a SQL type for one and
     * throws before the statement is sent. {@code timestamptz} takes an
     * {@link OffsetDateTime}, and UTC is the offset every bucket boundary is computed at.
     */
    private static OffsetDateTime timestamp(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
