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
 * The retention sweep, against a real PostgreSQL 17.
 *
 * <p>{@code stat_sample} is the largest table in the schema by a wide margin - one row per
 * workload per node every fifteen seconds - and this job is the only thing standing between
 * it and the disk. A sweep that silently deletes nothing looks exactly like a sweep that is
 * working, right up until the volume fills, so the horizons are worth asserting against a
 * real table rather than a mock.
 *
 * <p>The three horizons differ, and getting them the wrong way round would coarsen a chart
 * as it aged in one place and delete it outright in another. Node-level readings throughout,
 * so no service row is needed.
 *
 * <p>Needs the {@code wisper_test} database from
 * {@code docs/contracts/panel-configuration.md}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PruneStatHistoryTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    private static final StatsSettings SETTINGS = new StatsSettings(
            Duration.ofHours(48), Duration.ofDays(30), Duration.ofDays(400),
            Duration.ofMinutes(5), Duration.ofHours(3), Duration.ofDays(7),
            Duration.ofHours(1), 5000, Duration.ofMinutes(5), 720,
            Duration.ofMinutes(30), Duration.ofSeconds(20), Duration.ofMinutes(30));

    private StatsTestDatabase database;
    private JdbcClient jdbc;
    private UUID nodeId;
    private StoreSampleReading store;
    private CounterDeltas deltas;
    private PruneStatHistory prune;

    @BeforeAll
    void startAgainstTheTestDatabase() throws SQLException {
        database = StatsTestDatabase.migrated("prune");
        jdbc = database.jdbc();
        nodeId = database.nodeId();
        store = new StoreSampleReading(jdbc);
        deltas = new CounterDeltas();
        prune = new PruneStatHistory(jdbc, deltas, SETTINGS, Clock.fixed(NOW, ZoneOffset.UTC));
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
    void rawSamplesPastTwoDaysGoAndNewerOnesStay() {
        store.store(sample(NOW.minus(49, ChronoUnit.HOURS)));
        store.store(sample(NOW.minus(47, ChronoUnit.HOURS)));
        store.store(sample(NOW.minus(1, ChronoUnit.MINUTES)));

        prune.sweep();

        assertThat(remaining("stat_sample")).isEqualTo(2);
    }

    @Test
    void hourlyBucketsPastThirtyDaysGoWhileDailyOnesFromTheSameDayStay() {
        // Aligned to their granularity, which stat_rollup_bucket_aligned insists on.
        storeBucket("HOUR", NOW.minus(31, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS));
        storeBucket("HOUR", NOW.minus(29, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS));
        storeBucket("DAY", NOW.minus(31, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS));

        prune.sweep();

        assertThat(remaining("stat_rollup", "HOUR")).isEqualTo(1);
        // A day older than the hourly horizon is exactly the row the chart falls back to.
        assertThat(remaining("stat_rollup", "DAY")).isEqualTo(1);
    }

    @Test
    void dailyBucketsPastFourHundredDaysGo() {
        storeBucket("DAY", NOW.minus(401, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS));
        storeBucket("DAY", NOW.minus(399, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS));

        prune.sweep();

        assertThat(remaining("stat_rollup", "DAY")).isEqualTo(1);
    }

    @Test
    void aSubjectThatHasStoppedReportingIsForgottenSoTheDeltaMapStaysBounded() {
        deltas.since(nodeId, null, NOW.minus(72, ChronoUnit.HOURS),
                CounterDeltas.Totals.of(1, 2, 3, 4));
        assertThat(deltas.trackedSubjects()).isEqualTo(1);

        prune.sweep();

        assertThat(deltas.trackedSubjects()).isZero();
    }

    private SampleReading sample(Instant at) {
        return new SampleReading(nodeId, null, at, 15, 100, 1000, null, 0, 0, 0, 0, 0, 0);
    }

    private void storeBucket(String granularity, Instant start) {
        jdbc.sql("""
                        INSERT INTO stat_rollup (id, node_id, service_id, granularity,
                                                 bucket_start, sample_count)
                        VALUES (:id, :node, NULL, :granularity, :start, 1)
                        """)
                .param("id", UUID.randomUUID())
                .param("node", nodeId)
                .param("granularity", granularity)
                .param("start", start.atOffset(ZoneOffset.UTC))
                .update();
    }

    private long remaining(String table) {
        Long count = jdbc.sql("SELECT count(*) FROM " + table + " WHERE node_id = :node")
                .param("node", nodeId)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    private long remaining(String table, String granularity) {
        Long count = jdbc.sql("SELECT count(*) FROM " + table
                        + " WHERE node_id = :node AND granularity = :granularity")
                .param("node", nodeId)
                .param("granularity", granularity)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }
}
