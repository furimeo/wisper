package lhqm.furimeo.wisper.database;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes how big a customer database is, as measured by the node that holds it.
 *
 * <p>Two columns, and they are the only two on {@code managed_database} a node owns:
 * {@code used_bytes} and {@code used_bytes_measured_at} (schema.md §2). Everything else on
 * that row is panel intent, so this writes a statement of its own rather than saving the
 * aggregate - a {@code save} would carry the whole record and could quietly undo a state
 * change made a millisecond earlier by somebody pressing a button.
 *
 * <h2>Which node may write which row</h2>
 *
 * <p>The {@code EXISTS} clause is not an optimisation. A node authenticates as itself and
 * then names grant ids; without the join it could report on any database in the table,
 * including one on somebody else's machine. A node may write only the databases on the
 * engines it hosts, and an id that does not satisfy that updates nothing.
 *
 * <p>{@code updated_at} is deliberately not touched. It is the panel's marker for when
 * somebody changed something, and moving it every fifteen seconds would make "last
 * modified" mean "last measured" on every screen that shows it.
 */
@Component
public class TrackDatabaseSize {

    private static final String SQL = """
            UPDATE managed_database AS md
               SET used_bytes             = :usedBytes,
                   used_bytes_measured_at = :measuredAt,
                   version                = md.version + 1
             WHERE md.id = :databaseId
               AND EXISTS (SELECT 1
                             FROM database_engine de
                            WHERE de.id = md.database_engine_id
                              AND de.node_id = :nodeId)
            """;

    private static final String ENGINE_SQL = """
            UPDATE database_engine AS de
               SET reported_state  = :reportedState,
                   reported_at     = :reportedAt,
                   disk_bytes_used = :diskBytesUsed,
                   last_error      = :lastError,
                   version         = de.version + 1
             WHERE de.id = :engineId AND de.node_id = :nodeId
            """;

    private final JdbcClient jdbc;

    public TrackDatabaseSize(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records one measurement.
     *
     * @param sizeBytes  what the node measured. Negative is impossible and would violate
     *                   {@code managed_database_used_not_negative}, so it is clamped rather
     *                   than allowed to fail a whole status batch
     * @param measuredAt when the node looked, not when the panel was told - a chart drawn
     *                   on arrival time shows spikes that never happened
     * @return true when a row was updated, false when the id names nothing this node holds
     */
    @Transactional
    public boolean record(UUID nodeId, UUID databaseId, long sizeBytes, Instant measuredAt) {
        return jdbc.sql(SQL)
                .param("databaseId", databaseId)
                .param("nodeId", nodeId)
                .param("usedBytes", Math.max(0L, sizeBytes))
                .param("measuredAt", timestamp(measuredAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update() == 1;
    }

    /**
     * Records what a node's report implies about the engine container itself.
     *
     * <p>The wire has no engine-level status message: {@code DatabaseStatus} is per grant,
     * so the engine's four node-owned columns are derived from the grants on it. An engine
     * that answered about at least one database is running; one whose every database
     * reports an error is failed; anything else is left alone, because a report that says
     * nothing about an engine is not a report that it is down.
     *
     * @param reportedState {@code RUNNING}, {@code FAILED} or {@code UNKNOWN}, matching
     *                      {@code database_engine_reported_state_known}
     * @param diskBytesUsed the sum of the sizes of the databases the node could see
     */
    @Transactional
    public boolean recordEngine(UUID nodeId, UUID engineId, String reportedState,
                                Instant reportedAt, long diskBytesUsed, String lastError) {
        return jdbc.sql(ENGINE_SQL)
                .param("engineId", engineId)
                .param("nodeId", nodeId)
                .param("reportedState", reportedState, Types.VARCHAR)
                .param("reportedAt", timestamp(reportedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("diskBytesUsed", Math.max(0L, diskBytesUsed), Types.BIGINT)
                .param("lastError", lastError, Types.VARCHAR)
                .update() == 1;
    }

    /**
     * PgJDBC binds {@code timestamptz} from an {@link OffsetDateTime}; handing it an
     * {@link Instant} works on some driver versions and not others, and one conversion here
     * is cheaper than finding out which.
     */
    private static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
