package lhqm.furimeo.wisper.service;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.Timestamp;

import lhqm.furimeo.wisper.proto.v1.CronStatus;

/**
 * Writes what a node reports about the cron entries it is running.
 *
 * <p>Called by {@code grpc} for the {@code cron} half of a {@code StatusBatch}
 * (panel-ports.md §3), and it is the only path by which those five columns are ever
 * written. Nothing else in this package touches them, and this touches nothing else - the
 * schedule, the command and {@code next_run_at} are the panel's, and a status report that
 * could rewrite them would let a node argue with the customer about what they asked for.
 *
 * <h2>Which node may write which row</h2>
 *
 * <p>The {@code EXISTS} clause below is not an optimisation. A node authenticates as
 * itself and then names cron ids; without the join it could report on any row in the
 * table, including one belonging to a service on somebody else's machine. A node may write
 * only the entries of services placed on it, and an id that does not satisfy that updates
 * nothing and is counted as ignored.
 *
 * <h2>Two fields the wire does not carry</h2>
 *
 * <p>{@code CronStatus} has no finish timestamp and no duration, while {@code cron_task}
 * has a column for each. What it does carry is {@code running}, and the pair
 * "{@code last_run_at} set, {@code last_finished_at} null" is exactly that flag - so a run
 * in flight clears both, and a finished one records the moment the panel was told,
 * {@code observedAt}. That is an upper bound: the finish happened at some point in the
 * status interval before it. Good enough to show "took about four seconds", not good
 * enough to bill on, and it is recorded here so the next person does not read the column
 * as gospel.
 */
@Component
public class RecordCronStatus {

    private static final Logger log = LoggerFactory.getLogger(RecordCronStatus.class);

    private static final String SQL = """
            UPDATE cron_task AS c
               SET last_run_at      = :lastRunAt,
                   last_finished_at = :lastFinishedAt,
                   last_exit_code   = :lastExitCode,
                   last_duration_ms = :lastDurationMs,
                   last_error       = :lastError,
                   updated_at       = :observedAt,
                   version          = c.version + 1
             WHERE c.id = :cronId
               AND EXISTS (SELECT 1
                             FROM placement p
                            WHERE p.service_id = c.service_id
                              AND p.node_id = :nodeId
                              AND p.state IN ('PLANNED', 'ACTIVE', 'DRAINING'))
            """;

    private final JdbcClient jdbc;

    public RecordCronStatus(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param statuses  every cron entry the node observed in one reconcile pass
     * @param observedAt when the node's report reached the panel
     */
    @Transactional
    public void accept(UUID nodeId, List<CronStatus> statuses, Instant observedAt) {
        if (nodeId == null || statuses == null || statuses.isEmpty()) {
            return;
        }
        int ignored = 0;
        for (CronStatus status : statuses) {
            UUID cronId = parseId(status.getCronId());
            if (cronId == null || apply(nodeId, cronId, status, observedAt) == 0) {
                ignored++;
            }
        }
        if (ignored > 0) {
            // Not an error on its own: a report can arrive a moment after the customer
            // deleted the entry. Logged because a node persistently naming ids it does not
            // hold is worth someone noticing.
            log.info("Node {} reported {} cron status(es) for entries it does not hold", nodeId,
                    ignored);
        }
    }

    private int apply(UUID nodeId, UUID cronId, CronStatus status, Instant observedAt) {
        Instant lastRunAt = status.hasLastRunAt() ? toInstant(status.getLastRunAt()) : null;
        boolean finished = lastRunAt != null && !status.getRunning();

        Instant finishedAt = finished ? observedAt : null;
        Long durationMs = finished
                ? Math.max(0L, observedAt.toEpochMilli() - lastRunAt.toEpochMilli())
                : null;
        Integer exitCode = finished ? status.getLastExitCode() : null;
        String error = errorOf(status, finished);

        return jdbc.sql(SQL)
                .param("cronId", cronId)
                .param("nodeId", nodeId)
                .param("lastRunAt", timestamp(lastRunAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("lastFinishedAt", timestamp(finishedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("lastExitCode", exitCode, Types.INTEGER)
                .param("lastDurationMs", durationMs, Types.BIGINT)
                .param("lastError", error, Types.VARCHAR)
                .param("observedAt", timestamp(observedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    /**
     * A skipped run is reported as an error, because that is what the customer needs to
     * see: a schedule whose runs are being dropped looks identical to a broken schedule
     * from the outside, and {@code cron_task} has no column of its own for the difference.
     */
    private static String errorOf(CronStatus status, boolean finished) {
        if (status.getLastRunSkipped()) {
            return "Skipped: the previous run had not finished.";
        }
        String reported = status.getLastError();
        if (reported.isEmpty() || !finished) {
            return null;
        }
        return reported;
    }

    private static UUID parseId(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    /**
     * PgJDBC binds {@code timestamptz} from an {@link OffsetDateTime}; handing it an
     * {@link Instant} works on some driver versions and not others, and one conversion
     * here is cheaper than finding out which.
     */
    private static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
