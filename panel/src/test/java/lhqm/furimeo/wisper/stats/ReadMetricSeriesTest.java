package lhqm.furimeo.wisper.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The chart query, against a real PostgreSQL 17.
 *
 * <p>{@link LoadMetricSeriesTest} covers which table answers a window, which is arithmetic.
 * This covers what comes back out of it, which is not: the column aliases, the window edges
 * snapped to the bucket grid, the {@code service_id IS NULL} that keeps a machine's own
 * readings apart from the workloads it is carrying, and the {@code wasNull} dance that turns
 * an absent memory limit into a null rather than a zero the page would draw a limit line at.
 *
 * <p>Needs the {@code wisper_test} database from
 * {@code docs/contracts/panel-configuration.md}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReadMetricSeriesTest {

    private static final Instant NOW = Instant.parse("2026-08-20T14:00:00Z");

    private static final StatsSettings SETTINGS = new StatsSettings(
            Duration.ofHours(48), Duration.ofDays(30), Duration.ofDays(400),
            Duration.ofMinutes(5), Duration.ofHours(3), Duration.ofDays(7),
            Duration.ofHours(1), 5000, Duration.ofMinutes(5), 720,
            Duration.ofMinutes(30), Duration.ofSeconds(20), Duration.ofMinutes(30));

    private StatsTestDatabase database;
    private JdbcClient jdbc;
    private UUID nodeId;
    private StoreSampleReading store;
    private LoadMetricSeries series;

    @BeforeAll
    void startAgainstTheTestDatabase() throws SQLException {
        database = StatsTestDatabase.migrated("series");
        jdbc = database.jdbc();
        nodeId = database.nodeId();
        store = new StoreSampleReading(jdbc);
        series = new LoadMetricSeries(jdbc, SETTINGS, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @BeforeEach
    void removeWhatTheLastTestWrote() {
        database.clearReadings();
    }

    @AfterAll
    void stop() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void aRecentWindowComesBackFromTheRawTableOldestFirst() {
        store.store(reading(NOW.minus(10, ChronoUnit.MINUTES), 100, 4096L));
        store.store(reading(NOW.minus(5, ChronoUnit.MINUTES), 250, 4096L));

        MetricSeries chart = series.forNode(nodeId, NOW.minus(30, ChronoUnit.MINUTES), NOW);

        assertThat(chart.source()).isEqualTo(MetricSource.RAW);
        assertThat(chart.points()).extracting(MetricPoint::cpuMillicores)
                .containsExactly(100L, 250L);
        // A raw sample is a bucket of one: its average and its maximum are the same reading.
        assertThat(chart.points().getFirst().cpuMillicoresMax()).isEqualTo(100);
        assertThat(chart.points().getFirst().sampleCount()).isEqualTo(1);
        assertThat(chart.latest().cpuMillicores()).isEqualTo(250);
        assertThat(chart.peakCpuMillicores()).isEqualTo(250);
        assertThat(chart.isEmpty()).isFalse();
    }

    @Test
    void aReadingWithNoMemoryLimitComesBackWithNoneRatherThanZero() {
        store.store(reading(NOW.minus(1, ChronoUnit.MINUTES), 100, null));

        MetricSeries chart = series.forNode(nodeId, NOW.minus(30, ChronoUnit.MINUTES), NOW);

        // Zero would be drawn as a limit line at the bottom of the chart, which reads as a
        // workload permanently over its ceiling.
        assertThat(chart.points().getFirst().memoryLimitBytes()).isNull();
    }

    @Test
    void aWindowTooWideForRawIsReadFromHourlyBucketsAndSnappedToTheGrid() {
        storeHourlyBucket(NOW.minus(4, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS));
        storeHourlyBucket(NOW.minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS));

        MetricSeries chart = series.forNode(nodeId, NOW.minus(6, ChronoUnit.HOURS), NOW);

        assertThat(chart.source()).isEqualTo(MetricSource.HOUR);
        assertThat(chart.from()).isEqualTo(NOW.minus(6, ChronoUnit.HOURS)
                .truncatedTo(ChronoUnit.HOURS));
        assertThat(chart.points()).hasSize(2);
        assertThat(chart.points().getFirst().sampleCount()).isEqualTo(240);
        // A bucket has no single limit: it may have changed inside the window, and drawing
        // the last value across the whole hour is a line that was never true.
        assertThat(chart.points().getFirst().memoryLimitBytes()).isNull();
    }

    @Test
    void aWindowNothingWasMeasuredInIsAnEmptySeriesAndNotAFailure() {
        MetricSeries chart = series.forNode(nodeId, NOW.minus(30, ChronoUnit.MINUTES), NOW);

        assertThat(chart.isEmpty()).isTrue();
        assertThat(chart.latest()).isNull();
        assertThat(chart.subjectId()).isEqualTo(nodeId);
    }

    private SampleReading reading(Instant at, long cpuMillicores, Long memoryLimitBytes) {
        return new SampleReading(nodeId, null, at, 15, cpuMillicores, 2048, memoryLimitBytes,
                0, 0, 0, 0, 0, 0);
    }

    private void storeHourlyBucket(Instant start) {
        jdbc.sql("""
                        INSERT INTO stat_rollup (id, node_id, service_id, granularity,
                                                 bucket_start, sample_count,
                                                 cpu_millicores_avg, cpu_millicores_max,
                                                 memory_bytes_avg, memory_bytes_max)
                        VALUES (:id, :node, NULL, 'HOUR', :start, 240, 120, 400, 2048, 4096)
                        """)
                .param("id", UUID.randomUUID())
                .param("node", nodeId)
                .param("start", start.atOffset(ZoneOffset.UTC))
                .update();
    }
}
