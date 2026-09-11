package lhqm.furimeo.wisper.backup;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads the snapshots an organization can restore from, with the two facts that come from
 * elsewhere: whether anybody has ever proved this one restores, and whether a restore is
 * reading it right now.
 *
 * <p>The first is the point of the whole screen. A list that only says "backed up
 * successfully" is a list of uploads; design §8.3 asks for a list of things that have been
 * put back, and the sub-select over {@code restore_run} is what turns one into the other.
 *
 * <p>Every state is listed, not only {@code AVAILABLE}. A customer looking for a snapshot
 * that is not there needs to see that it failed on Tuesday, and a list that silently omitted
 * the failures would leave them believing a backup exists.
 */
@Component
public class ListRestorePoints {

    private static final String BY_ORGANIZATION = """
            SELECT rp.id, rp.target_kind, rp.target_label, rp.state, rp.trigger, rp.size_bytes,
                   rp.encrypted, rp.object_key, rp.started_at, rp.finished_at, rp.expires_at,
                   rp.error_message,
                   d.name AS destination_name, b.name AS backup_name,
                   (SELECT max(r.finished_at) FROM restore_run r
                     WHERE r.restore_point_id = rp.id
                       AND r.state = 'SUCCEEDED') AS last_verified_at,
                   (SELECT count(*) FROM restore_run r
                     WHERE r.restore_point_id = rp.id
                       AND r.state IN ('QUEUED', 'RUNNING')) AS active_restores
              FROM restore_point rp
              JOIN backup_destination d ON d.id = rp.destination_id
              LEFT JOIN backup b ON b.id = rp.backup_id
             WHERE rp.organization_id = :organizationId
             ORDER BY rp.started_at DESC
             LIMIT :limit
            """;

    private final JdbcClient jdbc;

    public ListRestorePoints(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The newest snapshots this organization owns. */
    public List<RestorePointView> forOrganization(UUID organizationId, int limit) {
        return jdbc.sql(BY_ORGANIZATION)
                .param("organizationId", organizationId)
                .param("limit", Math.max(1, limit))
                .query(ListRestorePoints::map)
                .list();
    }

    private static RestorePointView map(ResultSet row, int rowNumber) throws SQLException {
        RestorePointState state = RestorePointState.valueOf(row.getString("state"));
        String objectKey = row.getString("object_key");
        Long size = row.getObject("size_bytes", Long.class);
        return new RestorePointView(
                row.getObject("id", UUID.class),
                BackupTargetKind.valueOf(row.getString("target_kind")),
                row.getString("target_label"),
                state,
                RestorePointTrigger.valueOf(row.getString("trigger")),
                size,
                row.getBoolean("encrypted"),
                objectKey,
                instant(row, "started_at"),
                instant(row, "finished_at"),
                instant(row, "expires_at"),
                row.getString("error_message"),
                row.getString("destination_name"),
                row.getString("backup_name"),
                instant(row, "last_verified_at"),
                row.getLong("active_restores"),
                state.isRestorable() && objectKey != null && !objectKey.isBlank());
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
