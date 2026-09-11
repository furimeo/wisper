package lhqm.furimeo.wisper.placement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Every live placement on the platform, for the operator's board.
 *
 * <p>{@link LoadPlacedServices} answers the same question for one node because that is
 * what building a spec needs. An operator deciding where to move something needs the
 * opposite shape - all of them at once, across tenants - and asking the per-node query in
 * a loop would be one statement per node on a page that exists to be looked at while
 * something is wrong.
 *
 * <p>{@code RELEASED} rows are excluded. They are history, kept so the audit trail can
 * answer "where did it run", and a board that showed them would show every service on
 * every node it has ever touched.
 *
 * <p>The volume sum is a subquery rather than a join so a service with three volumes
 * stays one row. Getting that wrong would triple a service on the board and, worse, make
 * its committed disk look like three times what it is.
 */
@Component
public class LoadPlacementBoard {

    private static final String SQL = """
            SELECT s.id            AS service_id,
                   s.name          AS service_name,
                   s.slug          AS service_slug,
                   s.kind          AS kind,
                   s.desired_state AS desired_state,
                   p.id            AS project_id,
                   p.slug          AS project_slug,
                   o.id            AS organization_id,
                   o.name          AS organization_name,
                   n.id            AS node_id,
                   n.name          AS node_name,
                   pl.state        AS state,
                   pl.pinned       AS pinned,
                   pl.placed_at    AS placed_at,
                   pl.reason       AS reason,
                   COALESCE(v.bytes, 0) AS volume_bytes
              FROM placement pl
              JOIN service s      ON s.id = pl.service_id
              JOIN project p      ON p.id = s.project_id
              JOIN organization o ON o.id = p.organization_id
              JOIN node n         ON n.id = pl.node_id
              LEFT JOIN (SELECT service_id, SUM(size_bytes) AS bytes
                           FROM volume GROUP BY service_id) v
                     ON v.service_id = s.id
             WHERE pl.state <> 'RELEASED'
             ORDER BY n.name, o.name, p.slug, s.slug
            """;

    private final JdbcClient jdbc;

    public LoadPlacementBoard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Everything currently placed, ordered by node and then by who owns it. */
    public List<PlacedServiceRow> everything() {
        return jdbc.sql(SQL).query(LoadPlacementBoard::map).list();
    }

    private static PlacedServiceRow map(ResultSet row, int rowNumber) throws SQLException {
        String state = row.getString("state");
        return new PlacedServiceRow(
                row.getObject("service_id", UUID.class),
                row.getString("service_name"),
                row.getString("service_slug"),
                row.getString("kind"),
                row.getString("desired_state"),
                row.getObject("project_id", UUID.class),
                row.getString("project_slug"),
                row.getObject("organization_id", UUID.class),
                row.getString("organization_name"),
                row.getObject("node_id", UUID.class),
                row.getString("node_name"),
                state,
                row.getBoolean("pinned"),
                PlacementState.DRAINING.name().equals(state),
                row.getLong("volume_bytes"),
                row.getTimestamp("placed_at").toInstant(),
                row.getString("reason"));
    }
}
