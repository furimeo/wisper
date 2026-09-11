package lhqm.furimeo.wisper.node;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.proto.v1.NodeHealth;
import lhqm.furimeo.wisper.proto.v1.StatusBatch;
import lhqm.furimeo.wisper.sql.SqlTimestamp;

/**
 * Writes the node-level half of a {@code StatusBatch}: the generation it converged to, its
 * health, and when the reconcile pass that produced it ran.
 *
 * <p>The per-object halves go elsewhere - workloads to {@code placement}, routes to
 * {@code domain}, databases to {@code database}, cron to {@code service} (panel-ports.md
 * §3). This is the header, and it is in {@code node} because the columns it writes are on
 * {@code node_status}.
 *
 * <h2>{@code partial} is the important field</h2>
 *
 * <p>A partial batch means Docker did not answer, so what the node is sending is
 * last-known rather than observed. Nothing may be concluded to have gone away from it, and
 * that is not an abstract concern: the counts below are the panel's idea of how much is on
 * a machine, and writing a zero because a socket timed out is how a fleet screen reports
 * an empty node that is in fact running two hundred containers. On a partial batch the
 * counts are left exactly as they were and the node is marked degraded (AGENTS.md §4.5).
 */
@Component
public class RecordStatusReport {

    private static final Logger log = LoggerFactory.getLogger(RecordStatusReport.class);

    private static final String SQL = """
            INSERT INTO node_status (node_id, connection_state, applied_generation,
                                     last_reconcile_at, reconcile_error, workload_count,
                                     running_workload_count, updated_at, version)
            VALUES (:nodeId, :state, :appliedGeneration, :observedAt, :healthDetail,
                    :workloadCount, 0, :at, 0)
            ON CONFLICT (node_id) DO UPDATE
               SET connection_state       = EXCLUDED.connection_state,
                   applied_generation     = GREATEST(node_status.applied_generation,
                                                     EXCLUDED.applied_generation),
                   last_reconcile_at      = EXCLUDED.last_reconcile_at,
                   reconcile_error        = EXCLUDED.reconcile_error,
                   -- Left alone on a partial batch: "cannot see it" is not "does not
                   -- exist", and the CHECK insists running <= total either way.
                   workload_count         = CASE WHEN CAST(:partial AS boolean)
                                                 THEN node_status.workload_count
                                                 ELSE GREATEST(EXCLUDED.workload_count,
                                                      node_status.running_workload_count) END,
                   updated_at             = EXCLUDED.updated_at,
                   version                = node_status.version + 1
            """;

    private final JdbcClient jdbc;
    private final NodeRepository nodes;
    private final DetectGenerationDrift drift;

    public RecordStatusReport(JdbcClient jdbc, NodeRepository nodes,
                              DetectGenerationDrift drift) {
        this.jdbc = jdbc;
        this.nodes = nodes;
        this.drift = drift;
    }

    /**
     * @param receivedAt when the panel read the batch, used when the node sent no
     *                   observation timestamp of its own
     * @return true when the batch revealed that two panels are driving this node and it
     *         was suspended, in which case the caller must stop publishing to it
     */
    @Transactional
    public boolean accept(UUID nodeId, StatusBatch batch, Instant receivedAt) {
        Instant observedAt = batch.hasObservedAt()
                ? Instant.ofEpochSecond(batch.getObservedAt().getSeconds(),
                        batch.getObservedAt().getNanos())
                : receivedAt;

        jdbc.sql(SQL)
                .param("nodeId", nodeId)
                .param("state", stateOf(batch).name())
                .param("appliedGeneration", batch.getAppliedGeneration())
                .param("observedAt", SqlTimestamp.at(observedAt))
                .param("healthDetail", batch.getHealthDetail().isBlank()
                        ? null : batch.getHealthDetail())
                .param("workloadCount", batch.getWorkloadsCount())
                .param("partial", batch.getPartial())
                .param("at", SqlTimestamp.at(receivedAt))
                .update();

        if (batch.getPartial()) {
            log.warn("Node {} sent a partial status batch ({}); nothing is concluded to have "
                    + "gone away", nodeId, batch.getHealthDetail());
        }

        String name = nodes.findById(nodeId).map(Node::name).orElse(nodeId.toString());
        return drift.check(nodeId, name, batch.getAppliedGeneration(), "status drift");
    }

    private static NodeConnectionState stateOf(StatusBatch batch) {
        if (batch.getPartial() || batch.getHealth() == NodeHealth.NODE_HEALTH_DEGRADED) {
            return NodeConnectionState.DEGRADED;
        }
        return NodeConnectionState.CONNECTED;
    }
}
