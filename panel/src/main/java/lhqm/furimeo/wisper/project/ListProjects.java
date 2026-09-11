package lhqm.furimeo.wisper.project;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The dashboard query: every project one account can reach, with its service counts, in
 * one statement.
 *
 * <p>One statement rather than a list plus a count per project, because the dashboard is
 * the page a customer opens most often and a project each is a round trip each. The
 * aggregate is a {@code FILTER} rather than two joins for the same reason.
 *
 * <p>It walks the ownership join from the account rather than being handed an
 * organization, so the dashboard needs no answer to "which tenant are you looking at" -
 * a question the chrome answers with a session value and a URL, and which would give the
 * wrong list every time the two disagreed. Somebody in four organizations sees all four,
 * grouped.
 *
 * <p>It reads {@code service} directly. That is the same shape {@code org}'s quota
 * measurement uses, and the alternative - asking the {@code service} package for counts
 * and stitching them together in Java - trades one query for two and a map lookup, to
 * hide a column name that a migration fixes anyway.
 */
@Component
public class ListProjects {

    private static final String SQL = """
            SELECT p.id, p.organization_id, o.name AS organization_name,
                   p.name, p.slug, p.description, p.archived_at, p.created_at,
                   count(s.id) FILTER (WHERE s.archived_at IS NULL) AS service_count,
                   count(s.id) FILTER (WHERE s.archived_at IS NULL
                                         AND s.desired_state = 'RUNNING') AS running_count
              FROM project p
              JOIN organization o ON o.id = p.organization_id
              JOIN member m ON m.organization_id = p.organization_id
              LEFT JOIN service s ON s.project_id = p.id
             WHERE m.account_id = :accountId
               AND m.accepted_at IS NOT NULL
             GROUP BY p.id, p.organization_id, o.name, p.name, p.slug, p.description,
                      p.archived_at, p.created_at
             ORDER BY o.name, (p.archived_at IS NOT NULL), p.name
            """;

    private final JdbcClient jdbc;

    public ListProjects(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every project in every organization this account has accepted membership of, live
     * ones first within each tenant.
     *
     * <p>Archived projects stay in the answer. A customer who archived something and
     * cannot find it again has lost it, which is not what the word means.
     */
    public List<ProjectSummary> visibleTo(UUID accountId) {
        if (accountId == null) {
            return List.of();
        }
        return jdbc.sql(SQL)
                .param("accountId", accountId)
                .query(ListProjects::map)
                .list();
    }

    private static ProjectSummary map(ResultSet row, int rowNumber) throws SQLException {
        return new ProjectSummary(
                row.getObject("id", UUID.class),
                row.getObject("organization_id", UUID.class),
                row.getString("organization_name"),
                row.getString("name"),
                row.getString("slug"),
                row.getString("description"),
                instant(row, "archived_at"),
                instant(row, "created_at"),
                row.getLong("service_count"),
                row.getLong("running_count"));
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
