package lhqm.furimeo.wisper.placement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.node.NodeSettings;

/**
 * Sizes every node the scheduler is allowed to use, in one statement.
 *
 * <p>Two figures per resource, and the difference between them is the whole of why this
 * query is shaped the way it is:
 *
 * <ul>
 * <li><strong>Total</strong> comes from {@code node_status}, which the node writes. A node
 *     that has never reported has no totals, sizes as zero and takes no work - "we do not
 *     know how big this machine is" is a reason to wait, not a reason to guess.</li>
 * <li><strong>Committed</strong> comes from the panel's own tables: the ceilings of every
 *     service placed here, the quotas of their volumes, and what the shared database
 *     engines on this node are holding. It is deliberately not
 *     {@code node_status.*_used}, because measured usage is what a workload happens to be
 *     doing this minute and a scheduler that packs against it fills a machine that then
 *     dies at the first spike (design §7.6).</li>
 * </ul>
 *
 * <p>The headroom percentages are applied here, through {@link NodeCapacity#of}, so
 * everything downstream reasons about a machine that is already smaller than the real one.
 */
@Component
public class LoadNodeCapacity {

    /**
     * Candidate nodes with what is spoken for on each.
     *
     * <p>The engine reservation is a multiplication rather than a sum of columns because
     * {@code database_engine} has no resource columns: the panel decides how big a shared
     * engine is, in {@code wisper.placement}, and sends the same numbers to the node in
     * the spec. One setting, two readers, no way for the reservation and the ceiling to
     * disagree.
     */
    private static final String SQL = """
            SELECT n.id, n.name,
                   COALESCE(ns.cpu_millicores_capacity, 0) AS cpu_total,
                   COALESCE(ns.memory_bytes_capacity, 0)   AS memory_total,
                   COALESCE(ns.disk_bytes_capacity, 0)     AS disk_total,
                   COALESCE(w.cpu, 0) + COALESCE(e.engines, 0) * :engineMillicores
                       AS cpu_committed,
                   COALESCE(w.memory, 0) + COALESCE(e.engines, 0) * :engineMemoryBytes
                       AS memory_committed,
                   COALESCE(w.disk, 0) + COALESCE(e.disk, 0) AS disk_committed
              FROM node n
              LEFT JOIN node_status ns ON ns.node_id = n.id
              LEFT JOIN (
                    SELECT p.node_id,
                           SUM(s.cpu_millicores)                    AS cpu,
                           SUM(s.memory_bytes)                      AS memory,
                           SUM(s.disk_bytes + COALESCE(v.bytes, 0)) AS disk
                      FROM placement p
                      JOIN service s ON s.id = p.service_id
                      LEFT JOIN (SELECT service_id, SUM(size_bytes) AS bytes
                                   FROM volume GROUP BY service_id) v
                             ON v.service_id = p.service_id
                     WHERE p.state <> 'RELEASED'
                     GROUP BY p.node_id) w ON w.node_id = n.id
              LEFT JOIN (
                    SELECT de.node_id,
                           count(DISTINCT de.id)            AS engines,
                           COALESCE(SUM(md.quota_bytes), 0) AS disk
                      FROM database_engine de
                      LEFT JOIN managed_database md ON md.database_engine_id = de.id
                     WHERE de.desired_state = 'RUNNING'
                     GROUP BY de.node_id) e ON e.node_id = n.id
             WHERE n.schedulable
               AND n.lifecycle = 'ENROLLED'
            """;

    private static final String ORDER = " ORDER BY n.name, n.id";

    private final JdbcClient jdbc;
    private final NodeSettings nodeSettings;
    private final PlacementSettings settings;

    public LoadNodeCapacity(JdbcClient jdbc, NodeSettings nodeSettings,
                            PlacementSettings settings) {
        this.jdbc = jdbc;
        this.nodeSettings = nodeSettings;
        this.settings = settings;
    }

    /**
     * Every node that is enrolled, schedulable and carrying all of {@code requiredTags}.
     *
     * <p>The tag filter is pushed into SQL as an array containment test, which is what the
     * GIN index {@code node_tags_idx} exists for. The fragment is built from the
     * <em>number</em> of tags and never from their text - each one is a bound parameter -
     * so a tag is a value here and can never become syntax.
     */
    public List<NodeCapacity> candidates(List<String> requiredTags) {
        List<String> tags = requiredTags == null ? List.of() : requiredTags;
        JdbcClient.StatementSpec statement = jdbc.sql(SQL + tagFilter(tags.size()) + ORDER)
                .param("engineMillicores", settings.databaseEngineMillicores())
                .param("engineMemoryBytes", settings.databaseEngineMemory().toBytes());
        for (int i = 0; i < tags.size(); i++) {
            statement = statement.param("tag" + i, tags.get(i));
        }
        return statement.query(this::map).list();
    }

    private static String tagFilter(int tagCount) {
        if (tagCount == 0) {
            return "";
        }
        StringBuilder filter = new StringBuilder(" AND n.tags @> ARRAY[");
        for (int i = 0; i < tagCount; i++) {
            filter.append(i == 0 ? ":tag" : ", :tag").append(i);
        }
        return filter.append("]::text[]").toString();
    }

    private NodeCapacity map(ResultSet row, int rowNumber) throws SQLException {
        return NodeCapacity.of(
                row.getObject("id", UUID.class),
                row.getString("name"),
                row.getLong("cpu_total"), row.getLong("cpu_committed"),
                row.getLong("memory_total"), row.getLong("memory_committed"),
                row.getLong("disk_total"), row.getLong("disk_committed"),
                nodeSettings.cpuHeadroomPercent(),
                nodeSettings.memoryHeadroomPercent(),
                nodeSettings.diskHeadroomPercent());
    }
}
