package lhqm.furimeo.wisper.placement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.node.NodeSettings;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.sql.SqlTimestamp;

/**
 * Closes the second half of a move: lets go of the machine a service has left.
 *
 * <p>A migration - whether an operator asked for one or a drain caused it - leaves two
 * rows: the replacement, {@code ACTIVE} on the new node, and the original, {@code DRAINING}
 * on the old one. The original stays in the old node's spec, published as stopped, so that
 * machine keeps the container and its logs while the new copy comes up. Something has to
 * decide when it may go, and that decision needs a fact only the node can supply.
 *
 * <p>A binding is released when either of these is true:
 *
 * <ul>
 * <li>the old node has reported the workload stopped, which is the ordinary path and takes
 *     one reconcile interval;</li>
 * <li>the old node has been unreachable for longer than the heartbeat window. Releasing
 *     then is safe and is not a guess about what is running: the workload simply is not in
 *     the spec the node receives when it comes back, and omission is deletion. Waiting
 *     forever for a machine that may never return would instead leave a service pinned to
 *     a record nobody can delete.</li>
 * </ul>
 *
 * <p>Releasing is followed by republishing to the old node, because the container is
 * removed by its absence from the next spec and by nothing else. Volume data is untouched:
 * the node deletes bytes only on an explicit purge (AGENTS.md §4.5).
 */
@Component
public class FinishDrainedPlacements {

    private static final Logger log = LoggerFactory.getLogger(FinishDrainedPlacements.class);

    private static final String SQL = """
            SELECT d.id AS placement_id, d.node_id, d.service_id
              FROM placement d
              JOIN placement a
                ON a.service_id = d.service_id
               AND a.state = 'ACTIVE'
               AND a.node_id <> d.node_id
              LEFT JOIN node_status ns ON ns.node_id = d.node_id
             WHERE d.state = 'DRAINING'
               AND (d.reported_state IN ('STOPPED', 'CRASHED')
                    OR ns.node_id IS NULL
                    OR (ns.connection_state = 'DISCONNECTED'
                        AND (ns.last_disconnected_at IS NULL
                             OR ns.last_disconnected_at < :staleBefore)))
             ORDER BY d.placed_at
            """;

    private final JdbcClient jdbc;
    private final PlacementRepository placements;
    private final PublishNodeSpec specs;
    private final NodeSettings nodeSettings;

    public FinishDrainedPlacements(JdbcClient jdbc, PlacementRepository placements,
                                   PublishNodeSpec specs, NodeSettings nodeSettings) {
        this.jdbc = jdbc;
        this.placements = placements;
        this.specs = specs;
        this.nodeSettings = nodeSettings;
    }

    /**
     * One pass over the finished moves.
     *
     * @return how many bindings were released
     */
    @Transactional
    public int sweep() {
        Instant now = Instant.now();
        List<Finished> finished = jdbc.sql(SQL)
                .param("staleBefore", SqlTimestamp.at(now.minus(nodeSettings.heartbeatTimeout())))
                .query(FinishDrainedPlacements::map)
                .list();
        if (finished.isEmpty()) {
            return 0;
        }

        Set<UUID> republish = new LinkedHashSet<>();
        int released = 0;
        for (Finished row : finished) {
            if (placements.release(row.placementId(), "moved to another node", now) > 0) {
                released++;
                republish.add(row.nodeId());
                log.info("Service {} has finished moving off node {}", row.serviceId(),
                        row.nodeId());
            }
        }
        for (UUID nodeId : republish) {
            specs.toNode(nodeId, "a migrated workload was released");
        }
        return released;
    }

    private static Finished map(ResultSet row, int rowNumber) throws SQLException {
        return new Finished(row.getObject("placement_id", UUID.class),
                row.getObject("node_id", UUID.class),
                row.getObject("service_id", UUID.class));
    }

    /** One binding the old node may now forget about. */
    private record Finished(UUID placementId, UUID nodeId, UUID serviceId) {
    }
}
