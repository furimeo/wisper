package lhqm.furimeo.wisper.stats;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a chart's worth of points for one service or one node.
 *
 * <p>The caller asks for a window, not a table. Which of the three sources answers is
 * decided here, from how far back the window reaches and how many points it would produce,
 * and the answer says which one it used so the axis can be honest about its resolution.
 *
 * <p>Choosing badly is what this class exists to prevent, in both directions: reading a
 * month out of {@code stat_sample} returns nothing at all, because raw samples are deleted
 * after two days; reading ten minutes out of {@code stat_rollup} returns a single hourly
 * bar. Neither looks like an error on screen, which is why the choice is one method and not
 * a parameter.
 */
@Component
public class LoadMetricSeries {

    private static final String SELECT_SAMPLES = """
            SELECT sampled_at AS at, cpu_millicores, memory_bytes, memory_limit_bytes, disk_bytes,
                   network_rx_bytes, network_tx_bytes, disk_read_bytes, disk_write_bytes,
                   restart_count
              FROM stat_sample
             WHERE %s
               AND sampled_at >= :from AND sampled_at < :to
             ORDER BY sampled_at
             LIMIT :limit
            """;

    private static final String SELECT_ROLLUPS = """
            SELECT bucket_start AS at, sample_count, cpu_millicores_avg, cpu_millicores_max,
                   memory_bytes_avg, memory_bytes_max, disk_bytes_max, network_rx_bytes,
                   network_tx_bytes, disk_read_bytes, disk_write_bytes, restart_count
              FROM stat_rollup
             WHERE granularity = :granularity
               AND %s
               AND bucket_start >= :from AND bucket_start < :to
             ORDER BY bucket_start
             LIMIT :limit
            """;

    /** A service's own readings. */
    private static final String FOR_SERVICE = "service_id = :subjectId";

    /**
     * The machine's own readings.
     *
     * <p>{@code service_id IS NULL} matters: without it a node chart would add up every
     * workload on the machine as well as the machine, and show a node using twice the CPU
     * it has.
     */
    private static final String FOR_NODE = "node_id = :subjectId AND service_id IS NULL";

    private final JdbcClient jdbc;
    private final StatsSettings settings;
    private final Clock clock;

    public LoadMetricSeries(JdbcClient jdbc, StatsSettings settings) {
        this(jdbc, settings, Clock.systemUTC());
    }

    LoadMetricSeries(JdbcClient jdbc, StatsSettings settings, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.clock = clock;
    }

    /** One service's chart. */
    @Transactional(readOnly = true)
    public MetricSeries forService(UUID serviceId, Instant from, Instant to) {
        return load(serviceId, FOR_SERVICE, from, to);
    }

    /** One machine's chart, excluding the workloads it is carrying. */
    @Transactional(readOnly = true)
    public MetricSeries forNode(UUID nodeId, Instant from, Instant to) {
        return load(nodeId, FOR_NODE, from, to);
    }

    /**
     * Which table can answer a window, and at what resolution.
     *
     * <p>Package-private and separate from the query so the decision can be tested without
     * a database. It is the part that is easy to get wrong and impossible to notice.
     */
    MetricSource sourceFor(Instant from, Instant to) {
        Instant now = Instant.now(clock);
        Duration window = Duration.between(from, to);
        boolean withinRaw = !from.isBefore(now.minus(settings.rawRetention()));
        if (withinRaw && MetricSource.RAW.pointsIn(window) <= settings.maxSeriesPoints()) {
            return MetricSource.RAW;
        }
        boolean withinHours = !from.isBefore(now.minus(settings.hourRetention()));
        if (withinHours && MetricSource.HOUR.pointsIn(window) <= settings.maxSeriesPoints()) {
            return MetricSource.HOUR;
        }
        return MetricSource.DAY;
    }

    private MetricSeries load(UUID subjectId, String subjectClause, Instant from, Instant to) {
        Instant start = from == null ? Instant.now(clock).minus(settings.liveWindow()) : from;
        Instant end = to == null ? Instant.now(clock) : to;
        if (!start.isBefore(end)) {
            return new MetricSeries(subjectId, MetricSource.RAW, start, end, List.of());
        }
        MetricSource source = sourceFor(start, end);
        // Snapping to the bucket grid keeps the first and last bar from being a fraction of
        // the width of the others, which reads as a dip that never happened.
        Instant alignedFrom = source.floorOf(start);
        Instant alignedTo = source.isRaw() ? end : source.floorOf(end).plus(source.bucket());

        List<MetricPoint> points = source.isRaw()
                ? rawPoints(subjectId, subjectClause, alignedFrom, alignedTo)
                : bucketPoints(subjectId, subjectClause, source, alignedFrom, alignedTo);
        return new MetricSeries(subjectId, source, alignedFrom, alignedTo, points);
    }

    private List<MetricPoint> rawPoints(UUID subjectId, String subjectClause, Instant from,
                                        Instant to) {
        return jdbc.sql(SELECT_SAMPLES.formatted(subjectClause))
                .param("subjectId", subjectId)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .param("limit", settings.maxSeriesPoints())
                .query(LoadMetricSeries::mapSample)
                .list();
    }

    private List<MetricPoint> bucketPoints(UUID subjectId, String subjectClause,
                                           MetricSource source, Instant from, Instant to) {
        return jdbc.sql(SELECT_ROLLUPS.formatted(subjectClause))
                .param("granularity", source.granularity())
                .param("subjectId", subjectId)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .param("limit", settings.maxSeriesPoints())
                .query(LoadMetricSeries::mapBucket)
                .list();
    }

    private static MetricPoint mapSample(ResultSet row, int rowNumber) throws SQLException {
        long cpu = row.getLong("cpu_millicores");
        long memory = row.getLong("memory_bytes");
        long limit = row.getLong("memory_limit_bytes");
        // wasNull() answers about the column that was just read, so it has to be asked
        // here and not inside the constructor call, where two more reads happen first.
        boolean unlimited = row.wasNull() || limit <= 0;
        return new MetricPoint(
                instant(row, "at"),
                cpu, cpu,
                memory, memory,
                unlimited ? null : limit,
                row.getLong("disk_bytes"),
                row.getLong("network_rx_bytes"),
                row.getLong("network_tx_bytes"),
                row.getLong("disk_read_bytes"),
                row.getLong("disk_write_bytes"),
                row.getInt("restart_count"),
                1);
    }

    private static MetricPoint mapBucket(ResultSet row, int rowNumber) throws SQLException {
        return new MetricPoint(
                instant(row, "at"),
                row.getLong("cpu_millicores_avg"),
                row.getLong("cpu_millicores_max"),
                row.getLong("memory_bytes_avg"),
                row.getLong("memory_bytes_max"),
                // A bucket has no single limit: it may have changed inside the window, and
                // drawing the last value across the whole hour is a line that was never true.
                null,
                row.getLong("disk_bytes_max"),
                row.getLong("network_rx_bytes"),
                row.getLong("network_tx_bytes"),
                row.getLong("disk_read_bytes"),
                row.getLong("disk_write_bytes"),
                row.getInt("restart_count"),
                row.getInt("sample_count"));
    }

    /**
     * PgJDBC maps {@code timestamptz} to {@link OffsetDateTime}; asking it for an
     * {@link Instant} directly throws.
     */
    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /**
     * The same mismatch in the other direction: PgJDBC refuses to infer a SQL type for an
     * {@link Instant} parameter, so a window bound has to be sent as an
     * {@link OffsetDateTime}. UTC, which is the offset every instant in this schema is
     * stored at.
     */
    private static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
