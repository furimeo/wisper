package lhqm.furimeo.wisper.backup;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Answers "where does this volume or database live right now, and what is it called".
 *
 * <p>Two statements, one per kind, because the two walk different joins and neither is a
 * special case of the other. Both end at the node the target is on today - a volume
 * through its service's {@code ACTIVE} placement, a database through the engine that holds
 * it - and that is the whole reason this class exists rather than each caller reading
 * {@code restore_point.node_id}. A service migrated between the backup and the restore
 * must restore onto its current machine, and a snapshot cannot know about a move that
 * happened after it was taken.
 *
 * <p>The queries are here rather than in {@code service} or {@code database} because they
 * are read-only lookups this package owns the shape of. Nothing is written, and no other
 * package's invariant is touched.
 */
@Component
public class ResolveBackupTarget {

    private static final String VOLUME_SQL = """
            SELECT v.id, v.name AS volume_name, v.backup_enabled, v.reported_state,
                   s.id AS service_id, s.name AS service_name,
                   p.id AS project_id, p.name AS project_name, p.organization_id,
                   pl.node_id
              FROM volume v
              JOIN service s ON s.id = v.service_id
              JOIN project p ON p.id = s.project_id
              LEFT JOIN placement pl ON pl.service_id = s.id AND pl.state = 'ACTIVE'
             WHERE v.id = :subjectId
            """;

    private static final String DATABASE_SQL = """
            SELECT md.id, md.name AS database_name, md.state,
                   p.id AS project_id, p.name AS project_name, p.organization_id,
                   e.engine, e.node_id
              FROM managed_database md
              JOIN project p ON p.id = md.project_id
              JOIN database_engine e ON e.id = md.database_engine_id
             WHERE md.id = :subjectId
            """;

    private final JdbcClient jdbc;

    public ResolveBackupTarget(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The target a policy points at, or empty when it has been deleted. */
    public Optional<BackupTargetRef> forPolicy(Backup policy) {
        return of(policy.targetKind(), policy.subjectId());
    }

    /** The target a snapshot was taken from, resolved as it is today. */
    public Optional<BackupTargetRef> forSnapshot(RestorePoint point) {
        UUID subjectId = point.subjectId();
        return subjectId == null ? Optional.empty() : of(point.targetKind(), subjectId);
    }

    /** The target of either kind, by id. */
    public Optional<BackupTargetRef> of(BackupTargetKind kind, UUID subjectId) {
        if (kind == null || subjectId == null) {
            return Optional.empty();
        }
        String sql = kind == BackupTargetKind.VOLUME ? VOLUME_SQL : DATABASE_SQL;
        return jdbc.sql(sql)
                .param("subjectId", subjectId)
                .query(kind == BackupTargetKind.VOLUME
                        ? ResolveBackupTarget::mapVolume
                        : ResolveBackupTarget::mapDatabase)
                .optional();
    }

    private static BackupTargetRef mapVolume(ResultSet row, int rowNumber) throws SQLException {
        UUID serviceId = row.getObject("service_id", UUID.class);
        String reported = row.getString("reported_state");
        return new BackupTargetRef(
                BackupTargetKind.VOLUME,
                row.getObject("id", UUID.class),
                row.getObject("organization_id", UUID.class),
                row.getObject("project_id", UUID.class),
                serviceId,
                row.getObject("node_id", UUID.class),
                serviceId.toString(),
                label(row.getString("project_name"), row.getString("service_name"),
                        row.getString("volume_name")),
                null,
                null,
                row.getBoolean("backup_enabled"),
                // Null means the node has not reported on the directory yet; READY and
                // MIGRATING both have bytes worth copying. MISSING and FAILED do not, and
                // copying nothing into an archive that then looks like a valid backup is
                // the failure this check exists to prevent.
                reported == null || "READY".equals(reported) || "MIGRATING".equals(reported));
    }

    private static BackupTargetRef mapDatabase(ResultSet row, int rowNumber) throws SQLException {
        String state = row.getString("state");
        return new BackupTargetRef(
                BackupTargetKind.DATABASE,
                row.getObject("id", UUID.class),
                row.getObject("organization_id", UUID.class),
                row.getObject("project_id", UUID.class),
                null,
                row.getObject("node_id", UUID.class),
                // A dump talks to the engine, not to the customer's container: there is no
                // workload to pause and nothing sensible to put here.
                "",
                label(row.getString("project_name"), "database", row.getString("database_name")),
                row.getString("engine"),
                row.getString("database_name"),
                true,
                "READY".equals(state) || "SUSPENDED".equals(state));
    }

    private static String label(String project, String middle, String leaf) {
        return project + " / " + middle + " / " + leaf;
    }
}
