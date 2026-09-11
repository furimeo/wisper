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
 * The engine fleet, for {@code /admin/databases}.
 *
 * <p>Three things a row needs that {@code database_engine} does not hold: the node's name,
 * the owning organization's name for a dedicated instance, and how many customer databases
 * the engine is carrying. The last is both the load figure and the reason an engine cannot
 * be deleted, so it is counted in the same statement rather than asked for per row.
 *
 * <p>The administrative password is deliberately not selected. Nothing in the panel
 * displays it: it reaches the node inside the spec and is never on a screen.
 */
@Component
public class ListDatabaseEngines {

    private static final String COLUMNS = """
            SELECT de.id, de.engine, de.engine_version, de.image, de.mode, de.organization_id,
                   de.node_id, de.host, de.port, de.data_path, de.desired_state,
                   de.reported_state, de.reported_at, de.disk_bytes_used, de.last_error,
                   de.created_at,
                   n.name AS node_name,
                   COALESCE(o.name, '') AS owner,
                   (SELECT count(*) FROM managed_database md
                     WHERE md.database_engine_id = de.id
                       AND md.state <> 'DELETING') AS database_count
              FROM database_engine de
              JOIN node n ON n.id = de.node_id
              LEFT JOIN organization o ON o.id = de.organization_id
            """;

    private static final String ALL = COLUMNS + " ORDER BY de.engine, n.name, de.port";

    private static final String ONE = COLUMNS + " WHERE de.id = :engineId";

    private static final String ON_NODE = COLUMNS + """
             WHERE de.node_id = :nodeId
             ORDER BY de.engine, de.port
            """;

    private final JdbcClient jdbc;
    private final NodeConnections nodes;

    public ListDatabaseEngines(JdbcClient jdbc, NodeConnections nodes) {
        this.jdbc = jdbc;
        this.nodes = nodes;
    }

    /** Every engine on every node. */
    public List<DatabaseEngineView> all() {
        return jdbc.sql(ALL).query(this::map).list();
    }

    /** The engines one machine is carrying. */
    public List<DatabaseEngineView> onNode(UUID nodeId) {
        return jdbc.sql(ON_NODE).param("nodeId", nodeId).query(this::map).list();
    }

    /** One engine, for its own page. */
    public Optional<DatabaseEngineView> one(UUID engineId) {
        return jdbc.sql(ONE).param("engineId", engineId).query(this::map).optional();
    }

    private DatabaseEngineView map(ResultSet row, int rowNumber) throws SQLException {
        UUID nodeId = row.getObject("node_id", UUID.class);
        EngineKind engine = EngineKind.valueOf(row.getString("engine"));
        return new DatabaseEngineView(
                row.getObject("id", UUID.class),
                engine,
                engine.label(),
                row.getString("engine_version"),
                row.getString("image"),
                EngineMode.valueOf(row.getString("mode")),
                row.getObject("organization_id", UUID.class),
                row.getString("owner"),
                nodeId,
                row.getString("node_name"),
                nodes.isConnected(nodeId),
                row.getString("host"),
                row.getInt("port"),
                row.getString("data_path"),
                EngineDesiredState.valueOf(row.getString("desired_state")),
                row.getString("reported_state"),
                instant(row, "reported_at"),
                row.getObject("disk_bytes_used", Long.class),
                row.getString("last_error"),
                row.getLong("database_count"),
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
