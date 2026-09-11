package lhqm.furimeo.wisper.deploy;

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
 * The deployment history of one service, with who started each and what it restored.
 *
 * <p>Two joins that {@link DeploymentRepository} cannot do, because that repository maps
 * the {@code deployment} aggregate and an aggregate does not reach into {@code account}
 * or into a second copy of itself. Both joins are outer: a webhook has no account behind
 * it, and most deployments restore nothing.
 *
 * <p>Ordered and limited exactly as {@code deployment_by_service_recent_idx} is built, so
 * the page that is opened most often on a service reads an index and stops.
 */
@Component
public class ListDeployments {

    private static final String COLUMNS = """
            SELECT d.id, d.sequence, d.status, d.trigger, d.source, d.git_ref, d.commit_sha,
                   d.commit_message, d.commit_author, d.is_current, d.node_id, d.release_path,
                   d.queued_at, d.started_at, d.finished_at, d.duration_ms, d.error_message,
                   a.email AS triggered_by,
                   restored.sequence AS rolled_back_from_sequence
              FROM deployment d
              LEFT JOIN account a ON a.id = d.triggered_by_account_id
              LEFT JOIN deployment restored ON restored.id = d.rolled_back_from_deployment_id
            """;

    private static final String RECENT = COLUMNS + """
             WHERE d.service_id = :serviceId
             ORDER BY d.sequence DESC
             LIMIT :limit
            """;

    private static final String ONE = COLUMNS + """
             WHERE d.service_id = :serviceId AND d.id = :deploymentId
            """;

    private final JdbcClient jdbc;

    public ListDeployments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The newest {@code limit} deployments of a service, newest first. */
    public List<DeploymentSummary> recent(UUID serviceId, int limit) {
        return jdbc.sql(RECENT)
                .param("serviceId", serviceId)
                .param("limit", Math.max(1, limit))
                .query(ListDeployments::map)
                .list();
    }

    /**
     * One deployment, but only if it belongs to that service.
     *
     * <p>The service id is in the predicate rather than checked afterwards, so a
     * deployment id from another customer's service produces the same empty result a
     * nonexistent one does - which is the answer that tells an attacker nothing.
     */
    public Optional<DeploymentSummary> one(UUID serviceId, UUID deploymentId) {
        return jdbc.sql(ONE)
                .param("serviceId", serviceId)
                .param("deploymentId", deploymentId)
                .query(ListDeployments::map)
                .optional();
    }

    private static DeploymentSummary map(ResultSet row, int rowNumber) throws SQLException {
        return new DeploymentSummary(
                row.getObject("id", UUID.class),
                row.getLong("sequence"),
                DeploymentStatus.valueOf(row.getString("status")),
                DeploymentTrigger.valueOf(row.getString("trigger")),
                DeploymentSource.valueOf(row.getString("source")),
                row.getString("git_ref"),
                row.getString("commit_sha"),
                firstLine(row.getString("commit_message")),
                row.getString("commit_author"),
                row.getBoolean("is_current"),
                row.getObject("node_id", UUID.class),
                row.getString("release_path"),
                instant(row, "queued_at"),
                instant(row, "started_at"),
                instant(row, "finished_at"),
                row.getObject("duration_ms", Long.class),
                row.getString("error_message"),
                row.getString("triggered_by"),
                // getObject, not getLong: the join is outer and getLong turns a null
                // into a zero, which would read as "rolled back to #0".
                row.getObject("rolled_back_from_sequence", Long.class));
    }

    /**
     * The commit subject. A deployment list is one line per row and a commit message is
     * not obliged to be.
     */
    private static String firstLine(String message) {
        if (message == null) {
            return null;
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
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
