package lhqm.furimeo.wisper.service;

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
 * A project's services with the node's view of each, in one statement.
 *
 * <p>The join to {@code placement} is what makes the list worth looking at: without it the
 * page can only repeat what the customer already asked for. It is joined on
 * {@code state = 'ACTIVE'}, which the partial unique index guarantees is at most one row
 * per service, so the join cannot duplicate a service.
 *
 * <p>Archived services are included and flagged rather than filtered out. A customer
 * looking for a service they archived last month should find it here, greyed, with a
 * restore button - not conclude it was deleted.
 */
@Component
public class ListServicesInProject {

    private static final String SQL = """
            SELECT s.id, s.project_id, s.name, s.slug, s.kind, s.desired_state, s.archived_at,
                   s.image, s.build_preset, s.created_at,
                   pl.node_id, pl.reported_state, pl.health, pl.reported_at
              FROM service s
              LEFT JOIN placement pl ON pl.service_id = s.id AND pl.state = 'ACTIVE'
             WHERE s.project_id = :projectId
             ORDER BY (s.archived_at IS NOT NULL), s.name
            """;

    private final JdbcClient jdbc;

    public ListServicesInProject(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String ONE_SQL = """
            SELECT s.id, s.project_id, s.name, s.slug, s.kind, s.desired_state, s.archived_at,
                   s.image, s.build_preset, s.created_at,
                   pl.node_id, pl.reported_state, pl.health, pl.reported_at
              FROM service s
              LEFT JOIN placement pl ON pl.service_id = s.id AND pl.state = 'ACTIVE'
             WHERE s.id = :serviceId
            """;

    /**
     * The same row for one service, which is what the overview page draws its status pill
     * from.
     *
     * <p>Here rather than in a class of its own because it is the same projection and the
     * same join; two files would be two places to remember that {@code DRAINING} is not
     * the placement to report.
     */
    public Optional<ServiceSummary> one(UUID serviceId) {
        if (serviceId == null) {
            return Optional.empty();
        }
        return jdbc.sql(ONE_SQL)
                .param("serviceId", serviceId)
                .query(ListServicesInProject::map)
                .optional();
    }

    /** Every service in the project, live ones first, then archived. */
    public List<ServiceSummary> all(UUID projectId) {
        if (projectId == null) {
            return List.of();
        }
        return jdbc.sql(SQL)
                .param("projectId", projectId)
                .query(ListServicesInProject::map)
                .list();
    }

    private static ServiceSummary map(ResultSet row, int rowNumber) throws SQLException {
        String preset = row.getString("build_preset");
        return new ServiceSummary(
                row.getObject("id", UUID.class),
                row.getObject("project_id", UUID.class),
                row.getString("name"),
                row.getString("slug"),
                ServiceKind.valueOf(row.getString("kind")),
                DesiredState.valueOf(row.getString("desired_state")),
                instant(row, "archived_at"),
                row.getString("image"),
                preset == null ? null : BuildPreset.valueOf(preset),
                row.getObject("node_id", UUID.class),
                row.getString("reported_state"),
                row.getString("health"),
                instant(row, "reported_at"),
                instant(row, "created_at"));
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
