package lhqm.furimeo.wisper.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The rollup, against a real PostgreSQL 17.
 *
 * <p>{@link RollupWindowsTest} covers which spans a run recomputes. This covers what the
 * three statements in {@link RollUpSamples} actually produce, which is the half that cannot
 * be reasoned about from Java: the {@code date_trunc(... AT TIME ZONE 'UTC')} that has to
 * land on the boundary {@code stat_rollup_bucket_aligned} demands, the {@code ON CONFLICT}
 * that has to infer an index declared {@code NULLS NOT DISTINCT}, and the weighted averages
 * that decide what every chart older than two days says.
 *
 * <p>Getting any of them wrong is silent. The chart still draws; the numbers are quietly
 * wrong at the edges, and a customer arguing about a bill or an outage is arguing with a
 * graph nobody can check.
 *
 * <p>Node-level samples throughout, both because a machine reading needs no service row and
 * because {@code service_id IS NULL} is the case {@code NULLS NOT DISTINCT} exists for - the
 * one an ordinary unique index would silently duplicate.
 *
 * <p>Needs the {@code wisper_test} database from
 * {@code docs/contracts/panel-configuration.md}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RollUpSamplesTest {

    private static final Instant NOW = Instant.parse("2026-03-10T10:20:00Z");

    /** Raw 48h, hourly 30d, daily 400d, three hours of lookback, a week of backfill. */
    private static final StatsSettings SETTINGS = new StatsSettings(
            Duration.ofHours(48), Duration.ofDays(30), Duration.ofDays(400),
            Duration.ofMinutes(5), Duration.ofHours(3), Duration.ofDays(7),
            Duration.ofHours(1), 5000, Duration.ofMinutes(5), 720,
            Duration.ofMinutes(30), Duration.ofSeconds(20), Duration.ofMinutes(30));

    private StatsTestDatabase database;
    private JdbcClient jdbc;
    private UUID nodeId;
    private StoreSampleReading store;
    private RollUpSamples rollUp;

    @BeforeAll
    void startAgainstTheTestDatabase() throws SQLException {
        database = StatsTestDatabase.migrated("rollup");
        jdbc = database.jdbc();
        nodeId = database.nodeId();
        store = new StoreSampleReading(jdbc);
        rollUp = new RollUpSamples(jdbc, SETTINGS, Clock.fixed(NOW, ZoneOffset.UTC));
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
    void samplesEitherSideOfAnHourBoundaryBecomeTwoBuckets() {
        storeTheFourSamplesAcrossNineOClock();

        rollUp.sweep();

        List<Bucket> hours = bucketsOf("HOUR");
        assertThat(hours).extracting(Bucket::start).containsExactly(
                Instant.parse("2026-03-10T08:00:00Z"), Instant.parse("2026-03-10T09:00:00Z"));
        assertThat(hours).extracting(Bucket::sampleCount).containsExactly(2, 2);
        assertThat(hours).extracting(Bucket::cpuMax).containsExactly(300L, 400L);
        // Counters are summed, because the column already holds the amount in the window.
        assertThat(hours).extracting(Bucket::networkRx).containsExactly(30L, 70L);
    }

    @Test
    void anAverageIsWeightedByHowLongEachSampleCovered() {
        storeTheFourSamplesAcrossNineOClock();

        rollUp.sweep();

        // 09:00 holds 200 millicores over 15 seconds and 400 over 45. A plain mean would say
        // 300; the truth is 350, and the difference is a node that sampled unevenly being
        // reported as quieter than it was.
        assertThat(bucketAt("HOUR", "2026-03-10T09:00:00Z").cpuAverage()).isEqualTo(350);
        assertThat(bucketAt("HOUR", "2026-03-10T09:00:00Z").memoryAverage()).isEqualTo(3500);
        assertThat(bucketAt("HOUR", "2026-03-10T08:00:00Z").cpuAverage()).isEqualTo(200);
    }

    @Test
    void reRunningTheWindowCorrectsABucketInsteadOfDuplicatingIt() {
        storeTheFourSamplesAcrossNineOClock();
        rollUp.sweep();
        rollUp.sweep();

        assertThat(bucketsOf("HOUR")).hasSize(2);

        // A node that reconnects backfills a sample with an old timestamp. The bucket that
        // was complete a moment ago is not any more, and the next run has to correct it.
        store.store(sample("2026-03-10T08:59:45Z", 15, 500, 5000, 50));
        rollUp.sweep();

        Bucket corrected = bucketAt("HOUR", "2026-03-10T08:00:00Z");
        assertThat(bucketsOf("HOUR")).hasSize(2);
        assertThat(corrected.sampleCount()).isEqualTo(3);
        assertThat(corrected.cpuAverage()).isEqualTo(300);
        assertThat(corrected.cpuMax()).isEqualTo(500);
        assertThat(corrected.networkRx()).isEqualTo(80);
    }

    @Test
    void aDayIsBuiltFromRawSamplesWhileTheyStillExist() {
        storeTheFourSamplesAcrossNineOClock();

        rollUp.sweep();

        Bucket day = bucketAt("DAY", "2026-03-10T00:00:00Z");
        assertThat(day.sampleCount()).isEqualTo(4);
        // (100*15 + 300*15 + 200*15 + 400*45) / 90
        assertThat(day.cpuAverage()).isEqualTo(300);
        assertThat(day.cpuMax()).isEqualTo(400);
        assertThat(day.networkRx()).isEqualTo(100);
    }

    @Test
    void aDayTheRawTableNoLongerCoversIsBuiltFromItsHourlyBucketsWeightedBySampleCount() {
        // Five days ago: past the 48-hour raw horizon, inside the seven-day backfill window.
        storeHourlyBucket("2026-03-05T01:00:00Z", 4, 100, 150, 1000, 1500, 5);
        storeHourlyBucket("2026-03-05T02:00:00Z", 2, 400, 500, 4000, 4500, 1);

        rollUp.sweep();

        Bucket day = bucketAt("DAY", "2026-03-05T00:00:00Z");
        assertThat(day.sampleCount()).isEqualTo(6);
        // (100*4 + 400*2) / 6. Weighting by sample_count is what stops a quiet hour with
        // four readings counting the same as a busy one with two.
        assertThat(day.cpuAverage()).isEqualTo(200);
        assertThat(day.cpuMax()).isEqualTo(500);
        assertThat(day.memoryAverage()).isEqualTo(2000);
        assertThat(day.networkRx()).isEqualTo(6);
    }

    @Test
    void aRepeatedSampleIsRejectedRatherThanDoublingTheChart() {
        SampleReading reading = sample("2026-03-10T09:00:00Z", 15, 200, 2000, 30);

        assertThat(store.store(reading)).isTrue();
        // A reconnecting node resends whatever it is not sure landed. NULLS NOT DISTINCT on
        // stat_sample_reading_key is what makes the duplicate a no-op for a node-level
        // reading, whose service_id is null.
        assertThat(store.store(reading)).isFalse();

        rollUp.sweep();

        assertThat(bucketAt("HOUR", "2026-03-10T09:00:00Z").sampleCount()).isEqualTo(1);
    }

    private void storeTheFourSamplesAcrossNineOClock() {
        store.store(sample("2026-03-10T08:59:00Z", 15, 100, 1000, 10));
        store.store(sample("2026-03-10T08:59:30Z", 15, 300, 3000, 20));
        store.store(sample("2026-03-10T09:00:00Z", 15, 200, 2000, 30));
        store.store(sample("2026-03-10T09:00:15Z", 45, 400, 4000, 40));
    }

    private SampleReading sample(String at, int windowSeconds, long cpuMillicores,
                                 long memoryBytes, long networkRxBytes) {
        return new SampleReading(nodeId, null, Instant.parse(at), windowSeconds, cpuMillicores,
                memoryBytes, null, 0, networkRxBytes, 0, 0, 0, 0);
    }

    /**
     * An hourly bucket a previous run would have written, for the day the raw table no
     * longer covers.
     */
    private void storeHourlyBucket(String start, int sampleCount, long cpuAverage, long cpuMax,
                                   long memoryAverage, long memoryMax, long networkRx) {
        jdbc.sql("""
                        INSERT INTO stat_rollup (id, node_id, service_id, granularity,
                                                 bucket_start, sample_count,
                                                 cpu_millicores_avg, cpu_millicores_max,
                                                 memory_bytes_avg, memory_bytes_max,
                                                 disk_bytes_max, network_rx_bytes,
                                                 network_tx_bytes, disk_read_bytes,
                                                 disk_write_bytes, restart_count)
                        VALUES (:id, :node, NULL, 'HOUR', :start, :count, :cpuAvg, :cpuMax,
                                :memAvg, :memMax, 0, :rx, 0, 0, 0, 0)
                        """)
                .param("id", UUID.randomUUID())
                .param("node", nodeId)
                .param("start", Instant.parse(start).atOffset(ZoneOffset.UTC))
                .param("count", sampleCount)
                .param("cpuAvg", cpuAverage)
                .param("cpuMax", cpuMax)
                .param("memAvg", memoryAverage)
                .param("memMax", memoryMax)
                .param("rx", networkRx)
                .update();
    }

    private List<Bucket> bucketsOf(String granularity) {
        return jdbc.sql("""
                        SELECT bucket_start, sample_count, cpu_millicores_avg, cpu_millicores_max,
                               memory_bytes_avg, network_rx_bytes
                          FROM stat_rollup
                         WHERE node_id = :node AND granularity = :granularity
                         ORDER BY bucket_start
                        """)
                .param("node", nodeId)
                .param("granularity", granularity)
                .query(RollUpSamplesTest::mapBucket)
                .list();
    }

    private Bucket bucketAt(String granularity, String start) {
        Instant wanted = Instant.parse(start);
        return bucketsOf(granularity).stream()
                .filter(bucket -> bucket.start().equals(wanted))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No " + granularity + " bucket at " + start + "; there are "
                                + bucketsOf(granularity)));
    }

    private static Bucket mapBucket(ResultSet row, int rowNumber) throws SQLException {
        return new Bucket(
                row.getObject("bucket_start", OffsetDateTime.class).toInstant(),
                row.getInt("sample_count"),
                row.getLong("cpu_millicores_avg"),
                row.getLong("cpu_millicores_max"),
                row.getLong("memory_bytes_avg"),
                row.getLong("network_rx_bytes"));
    }

    private record Bucket(Instant start, int sampleCount, long cpuAverage, long cpuMax,
                          long memoryAverage, long networkRx) {
    }
}
