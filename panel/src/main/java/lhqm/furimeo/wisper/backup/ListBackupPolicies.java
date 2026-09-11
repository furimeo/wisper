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
 * Reads an organization's backup policies with everything the list screen needs, in one
 * statement.
 *
 * <p>Three sub-selects rather than three queries per row. The alternative - a repository
 * call for the policies and then a count, a maximum and a sum for each one - is the N+1 that
 * makes a page with twenty policies take sixty round trips, and it is invisible until
 * somebody has twenty policies.
 *
 * <p>The target label is built here rather than stored, because a policy's label has to
 * follow a rename. That is the opposite of {@code restore_point.target_label}, which is
 * frozen at the moment the snapshot is taken and must survive its target being deleted -
 * two different requirements, so two different mechanisms.
 */
@Component
public class ListBackupPolicies {

    private static final String SQL = """
            SELECT b.id, b.name, b.target_kind, b.schedule, b.timezone, b.enabled,
                   b.retention_count, b.retention_days, b.last_run_at, b.last_status,
                   b.last_error, b.next_run_at,
                   d.id AS destination_id, d.name AS destination_name,
                   d.kind AS destination_kind,
                   coalesce(s.name || ' / ' || v.name, md.name, '(deleted)') AS target_label,
                   (SELECT count(*) FROM restore_point rp
                     WHERE rp.backup_id = b.id AND rp.state = 'AVAILABLE') AS snapshot_count,
                   (SELECT max(rp.started_at) FROM restore_point rp
                     WHERE rp.backup_id = b.id AND rp.state = 'AVAILABLE') AS latest_snapshot_at,
                   (SELECT coalesce(sum(rp.size_bytes), 0) FROM restore_point rp
                     WHERE rp.backup_id = b.id AND rp.state = 'AVAILABLE') AS stored_bytes
              FROM backup b
              JOIN backup_destination d ON d.id = b.destination_id
              LEFT JOIN volume v ON v.id = b.volume_id
              LEFT JOIN service s ON s.id = v.service_id
              LEFT JOIN managed_database md ON md.id = b.managed_database_id
             WHERE b.organization_id = :organizationId
             ORDER BY b.name
            """;

    private final JdbcClient jdbc;

    public ListBackupPolicies(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every policy this organization owns, by name. */
    public List<BackupView> forOrganization(UUID organizationId) {
        return jdbc.sql(SQL)
                .param("organizationId", organizationId)
                .query(ListBackupPolicies::map)
                .list();
    }

    private static BackupView map(ResultSet row, int rowNumber) throws SQLException {
        String lastStatus = row.getString("last_status");
        return new BackupView(
                row.getObject("id", UUID.class),
                row.getString("name"),
                BackupTargetKind.valueOf(row.getString("target_kind")),
                row.getString("target_label"),
                row.getObject("destination_id", UUID.class),
                row.getString("destination_name"),
                DestinationKind.valueOf(row.getString("destination_kind")),
                row.getString("schedule"),
                row.getString("timezone"),
                row.getBoolean("enabled"),
                row.getInt("retention_count"),
                row.getInt("retention_days"),
                instant(row, "last_run_at"),
                lastStatus == null ? null : BackupRunStatus.valueOf(lastStatus),
                row.getString("last_error"),
                instant(row, "next_run_at"),
                row.getLong("snapshot_count"),
                instant(row, "latest_snapshot_at"),
                row.getLong("stored_bytes"));
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
