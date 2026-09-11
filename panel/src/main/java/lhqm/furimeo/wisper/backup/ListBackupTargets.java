package lhqm.furimeo.wisper.backup;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Everything in an organization that a backup policy could be pointed at.
 *
 * <p>One statement over two tables, joined by a union rather than by two calls, so the form
 * gets a single list in a stable order and the page does not have to merge and sort two.
 * Both halves walk the same ownership path - {@code organization -> project -> ...} - which
 * is the only place tenancy is established (schema.md §3).
 */
@Component
public class ListBackupTargets {

    private static final String SQL = """
            SELECT 'VOLUME' AS kind, v.id AS target_id,
                   s.name || ' / ' || v.name AS label,
                   v.backup_enabled AS eligible,
                   (SELECT count(*) FROM backup b WHERE b.volume_id = v.id) AS policy_count
              FROM volume v
              JOIN service s ON s.id = v.service_id
              JOIN project p ON p.id = s.project_id
             WHERE p.organization_id = :organizationId
            UNION ALL
            SELECT 'DATABASE', md.id,
                   p.name || ' / ' || md.name,
                   md.state IN ('READY', 'SUSPENDED'),
                   (SELECT count(*) FROM backup b WHERE b.managed_database_id = md.id)
              FROM managed_database md
              JOIN project p ON p.id = md.project_id
             WHERE p.organization_id = :organizationId
             ORDER BY 1, 3
            """;

    private final JdbcClient jdbc;

    public ListBackupTargets(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Databases first, then volumes, each alphabetically - which is what {@code ORDER BY 1, 3}
     * gives, since {@code DATABASE} sorts before {@code VOLUME}. */
    public List<BackupTargetOption> forOrganization(UUID organizationId) {
        return jdbc.sql(SQL)
                .param("organizationId", organizationId)
                .query(ListBackupTargets::map)
                .list();
    }

    private static BackupTargetOption map(ResultSet row, int rowNumber) throws SQLException {
        return new BackupTargetOption(
                BackupTargetKind.valueOf(row.getString("kind")),
                row.getObject("target_id", UUID.class),
                row.getString("label"),
                row.getBoolean("eligible"),
                row.getLong("policy_count"));
    }
}
