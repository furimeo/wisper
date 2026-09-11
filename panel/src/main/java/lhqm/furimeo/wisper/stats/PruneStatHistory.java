package lhqm.furimeo.wisper.stats;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Deletes readings that are past their retention.
 *
 * <p>{@code stat_sample} is the largest table in the schema by a wide margin - one row per
 * workload per node every fifteen seconds - and the only thing standing between it and the
 * disk is this. Two days of raw, thirty of hourly, four hundred of daily
 * ({@code schema.md} §3).
 *
 * <h2>Batched, and each batch is its own transaction</h2>
 *
 * <p>A single {@code DELETE} covering an outage's worth of samples takes locks and WAL for
 * as long as it runs, and everything else on the connection pool waits behind it. Deleting
 * in bounded batches means the sweep can be interrupted at any point and simply carries on
 * next hour, which is the property that matters for a job whose only job is to keep a table
 * from growing.
 *
 * <p>The batches are also the reason this class holds no transaction of its own: each
 * statement commits on its own, so a run that is cut short by a restart has still made
 * progress.
 */
@Component
public class PruneStatHistory {

    private static final Logger log = LoggerFactory.getLogger(PruneStatHistory.class);

    /**
     * Most batches one run will do, per table.
     *
     * <p>A ceiling rather than "until it is empty", so a sweep that finds a year of backlog
     * takes an hour off between passes instead of holding a connection for the afternoon.
     * The job runs hourly; the arrears clear over a few runs.
     */
    private static final int MAX_BATCHES = 40;

    private static final String DELETE_SAMPLES = """
            DELETE FROM stat_sample
             WHERE id IN (SELECT id FROM stat_sample
                           WHERE sampled_at < :before
                           ORDER BY sampled_at
                           LIMIT :batchSize)
            """;

    private static final String DELETE_ROLLUPS = """
            DELETE FROM stat_rollup
             WHERE id IN (SELECT id FROM stat_rollup
                           WHERE granularity = :granularity AND bucket_start < :before
                           ORDER BY bucket_start
                           LIMIT :batchSize)
            """;

    private final JdbcClient jdbc;
    private final CounterDeltas deltas;
    private final StatsSettings settings;
    private final Clock clock;

    @Autowired
    public PruneStatHistory(JdbcClient jdbc, CounterDeltas deltas, StatsSettings settings) {
        this(jdbc, deltas, settings, Clock.systemUTC());
    }

    PruneStatHistory(JdbcClient jdbc, CounterDeltas deltas, StatsSettings settings, Clock clock) {
        this.jdbc = jdbc;
        this.deltas = deltas;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Removes everything past its horizon, and forgets the counter totals of subjects that
     * have stopped reporting.
     *
     * @return how many rows went
     */
    public int sweep() {
        Instant now = Instant.now(clock);
        int samples = drain(DELETE_SAMPLES, null, now.minus(settings.rawRetention()));
        int hours = drain(DELETE_ROLLUPS, MetricSource.HOUR.granularity(),
                now.minus(settings.hourRetention()));
        int days = drain(DELETE_ROLLUPS, MetricSource.DAY.granularity(),
                now.minus(settings.dayRetention()));

        // A workload that no longer reports keeps four longs alive for as long as the panel
        // runs. Small, but unbounded, and unbounded is the property that matters.
        int forgotten = deltas.forget(now.minus(settings.rawRetention()));

        int total = samples + hours + days;
        if (total > 0 || forgotten > 0) {
            log.info("Stats sweep: {} raw sample(s), {} hourly and {} daily bucket(s) deleted; "
                    + "{} idle subject(s) forgotten", samples, hours, days, forgotten);
        }
        return total;
    }

    private int drain(String statement, String granularity, Instant before) {
        int deleted = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int rows = deleteBatch(statement, granularity, before);
            deleted += rows;
            if (rows < settings.sweepBatchSize()) {
                return deleted;
            }
        }
        log.info("Stats sweep hit its batch ceiling deleting rows older than {}; the rest goes "
                + "next run", before);
        return deleted;
    }

    /**
     * One batch.
     *
     * <p>Deliberately not {@code @Transactional} and deliberately not called from inside
     * one: with no ambient transaction each statement runs on its own connection with
     * autocommit, so every batch is durable the moment it finishes. Wrapping the whole
     * sweep would turn "delete two hundred thousand rows in bounded pieces" back into one
     * long-running transaction, which is the thing the batching exists to avoid.
     *
     * <p>The horizon goes across as an {@link OffsetDateTime}: PgJDBC refuses to infer a SQL
     * type for an {@link Instant} parameter and throws before the statement is sent. UTC,
     * which is the offset every instant in this schema is stored at.
     */
    private int deleteBatch(String statement, String granularity, Instant before) {
        JdbcClient.StatementSpec spec = jdbc.sql(statement)
                .param("before", before.atOffset(ZoneOffset.UTC))
                .param("batchSize", settings.sweepBatchSize());
        if (granularity != null) {
            spec = spec.param("granularity", granularity);
        }
        return spec.update();
    }
}
