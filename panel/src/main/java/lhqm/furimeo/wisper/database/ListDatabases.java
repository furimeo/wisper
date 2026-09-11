package lhqm.furimeo.wisper.database;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.node.NodeConnections;

/**
 * The customer's database list, assembled from the five tables one row of it spans.
 *
 * <p>{@link ManagedDatabaseRepository} maps the {@code managed_database} aggregate and an
 * aggregate does not reach into {@code database_engine}, {@code project} or {@code node} -
 * but a database with no host, no project name and no idea which machine it is on is a row
 * nobody can read. So the join lives here, once, and every screen uses it.
 *
 * <p>{@link #visibleTo} walks the ownership join from the account rather than being handed
 * an organization, the same shape {@code project}'s dashboard query uses and for the same
 * reason: the list must not depend on the chrome and the URL agreeing about which tenant is
 * current. The other three methods take the organization, because they are reached from a
 * screen that already resolved a membership - and the tenant is in the predicate rather
 * than checked afterwards, so a database id from another organization produces the same
 * empty result a nonexistent one does (panel-http.md).
 *
 * <p>Reachability comes from {@link NodeConnections} and not from a column, because it is a
 * fact about right now.
 */
@Component
public class ListDatabases {

    private static final String COLUMNS = """
            SELECT md.id, md.project_id, md.name, md.db_username, md.db_charset,
                   md.quota_bytes, md.used_bytes, md.used_bytes_measured_at, md.state,
                   md.last_error, md.provisioned_at, md.password_rotated_at,
                   p.name AS project_name, p.organization_id,
                   o.name AS organization_name,
                   de.engine, de.engine_version, de.host, de.port, de.mode,
                   de.node_id, n.name AS node_name
              FROM managed_database md
              JOIN project p ON p.id = md.project_id
              JOIN organization o ON o.id = p.organization_id
              JOIN database_engine de ON de.id = md.database_engine_id
              JOIN node n ON n.id = de.node_id
            """;

    private static final String VISIBLE = COLUMNS + """
              JOIN member m ON m.organization_id = p.organization_id
             WHERE m.account_id = :accountId
               AND m.accepted_at IS NOT NULL
             ORDER BY o.name, p.name, md.name
            """;

    private static final String BY_PROJECT = COLUMNS + """
             WHERE md.project_id = :projectId AND p.organization_id = :organizationId
             ORDER BY md.name
            """;

    private static final String BY_ORGANIZATION = COLUMNS + """
             WHERE p.organization_id = :organizationId
             ORDER BY p.name, md.name
            """;

    private static final String ONE = COLUMNS + """
             WHERE md.id = :databaseId AND p.organization_id = :organizationId
            """;

    /**
     * Matches the {@code READY} half of {@code managed_database_usage_idx}; the suspended
     * half is a short list by definition, because a database only becomes suspended by
     * appearing in the ready half first.
     */
    private static final String OVER_QUOTA = COLUMNS + """
             WHERE md.state IN ('READY', 'SUSPENDED')
               AND md.used_bytes IS NOT NULL
               AND md.used_bytes > md.quota_bytes
             ORDER BY md.used_bytes - md.quota_bytes DESC
            """;

    private final JdbcClient jdbc;
    private final NodeConnections nodes;

    public ListDatabases(JdbcClient jdbc, NodeConnections nodes) {
        this.jdbc = jdbc;
        this.nodes = nodes;
    }

    /** Every database in every organization this account has accepted membership of. */
    public List<ManagedDatabaseView> visibleTo(UUID accountId) {
        if (accountId == null) {
            return List.of();
        }
        return jdbc.sql(VISIBLE).param("accountId", accountId).query(this::map).list();
    }

    /** Every database one organization has, grouped by project. */
    public List<ManagedDatabaseView> forOrganization(UUID organizationId) {
        return jdbc.sql(BY_ORGANIZATION)
                .param("organizationId", organizationId)
                .query(this::map)
                .list();
    }

    /** The databases of one project, for the project's own tab. */
    public List<ManagedDatabaseView> forProject(UUID organizationId, UUID projectId) {
        return jdbc.sql(BY_PROJECT)
                .param("organizationId", organizationId)
                .param("projectId", projectId)
                .query(this::map)
                .list();
    }

    /**
     * Every database past its ceiling, across every tenant, worst first.
     *
     * <p>Operator-facing, for {@code /admin/databases}: a node whose disk is filling is
     * usually a handful of databases doing it, and this is the list of them. Not reachable
     * by a customer, which is why it does not take an organization.
     */
    public List<ManagedDatabaseView> overQuota() {
        return jdbc.sql(OVER_QUOTA).query(this::map).list();
    }

    /** One database, but only if it belongs to this organization. */
    public Optional<ManagedDatabaseView> one(UUID organizationId, UUID databaseId) {
        return jdbc.sql(ONE)
                .param("organizationId", organizationId)
                .param("databaseId", databaseId)
                .query(this::map)
                .optional();
    }

    private ManagedDatabaseView map(ResultSet row, int rowNumber) throws SQLException {
        UUID nodeId = row.getObject("node_id", UUID.class);
        long quotaBytes = row.getLong("quota_bytes");
        Long usedBytes = row.getObject("used_bytes", Long.class);
        EngineKind engine = EngineKind.valueOf(row.getString("engine"));
        return new ManagedDatabaseView(
                row.getObject("id", UUID.class),
                row.getObject("organization_id", UUID.class),
                row.getString("organization_name"),
                row.getObject("project_id", UUID.class),
                row.getString("project_name"),
                row.getString("name"),
                row.getString("db_username"),
                engine,
                engine.label(),
                row.getString("engine_version"),
                row.getString("host"),
                row.getInt("port"),
                !"SHARED".equals(row.getString("mode")),
                ManagedDatabaseState.valueOf(row.getString("state")),
                quotaBytes,
                usedBytes,
                instant(row, "used_bytes_measured_at"),
                percentUsed(usedBytes, quotaBytes),
                usedBytes != null && usedBytes > quotaBytes,
                instant(row, "provisioned_at"),
                instant(row, "password_rotated_at"),
                row.getString("last_error"),
                nodeId,
                row.getString("node_name"),
                nodes.isConnected(nodeId));
    }

    /** -1 for "not measured yet", which a bar at zero would misrepresent as empty. */
    private static int percentUsed(Long usedBytes, long quotaBytes) {
        if (usedBytes == null || quotaBytes <= 0) {
            return -1;
        }
        return (int) Math.min(usedBytes * 100 / quotaBytes, 100);
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
