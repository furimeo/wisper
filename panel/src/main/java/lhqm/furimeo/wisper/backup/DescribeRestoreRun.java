package lhqm.furimeo.wisper.backup;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads one restore, and the recent ones, for the page the restore button leads to.
 *
 * <p>The ownership check is in the statement rather than after it: the join to
 * {@code restore_point} is the only thing that ties a run to a tenant, since
 * {@code restore_run} has no {@code organization_id} of its own. Filtering in Java after a
 * lookup by id would be the version of this that somebody forgets.
 */
@Component
public class DescribeRestoreRun {

    private static final String COLUMNS = """
            SELECT r.id, r.restore_point_id, r.mode, r.state, r.node_id, r.started_at,
                   r.finished_at, r.bytes_restored, r.error_message, r.log,
                   r.safety_restore_point_id,
                   rp.target_label, rp.started_at AS snapshot_taken_at,
                   a.email AS requested_by
              FROM restore_run r
              JOIN restore_point rp ON rp.id = r.restore_point_id
              LEFT JOIN account a ON a.id = r.requested_by_account_id
            """;

    private static final String BY_ID = COLUMNS + """
             WHERE r.id = :id AND rp.organization_id = :organizationId
            """;

    private static final String RECENT = COLUMNS + """
             WHERE rp.organization_id = :organizationId
             ORDER BY r.created_at DESC
             LIMIT :limit
            """;

    private final JdbcClient jdbc;

    public DescribeRestoreRun(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One run, or empty when it is not this organization's. */
    public Optional<RestoreRunView> byId(UUID id, UUID organizationId) {
        return jdbc.sql(BY_ID)
                .param("id", id)
                .param("organizationId", organizationId)
                .query(DescribeRestoreRun::map)
                .optional();
    }

    /** The organization's recent restores, newest first, for the history panel. */
    public List<RestoreRunView> recentFor(UUID organizationId, int limit) {
        return jdbc.sql(RECENT)
                .param("organizationId", organizationId)
                .param("limit", Math.max(1, limit))
                .query(DescribeRestoreRun::map)
                .list();
    }

    private static RestoreRunView map(ResultSet row, int rowNumber) throws SQLException {
        return new RestoreRunView(
                row.getObject("id", UUID.class),
                row.getObject("restore_point_id", UUID.class),
                row.getString("target_label"),
                instant(row, "snapshot_taken_at"),
                RestoreMode.valueOf(row.getString("mode")),
                RestoreState.valueOf(row.getString("state")),
                row.getObject("node_id", UUID.class),
                instant(row, "started_at"),
                instant(row, "finished_at"),
                row.getObject("bytes_restored", Long.class),
                row.getString("error_message"),
                row.getString("log"),
                row.getObject("safety_restore_point_id", UUID.class),
                row.getString("requested_by"));
    }

    /**
     * PgJDBC maps {@code timestamptz} to {@link OffsetDateTime}; asking it for an
     * {@link Instant} directly throws.
     */
    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
