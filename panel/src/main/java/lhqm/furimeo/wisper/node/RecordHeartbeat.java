package lhqm.furimeo.wisper.node;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.proto.v1.Capacity;
import lhqm.furimeo.wisper.proto.v1.Heartbeat;
import lhqm.furimeo.wisper.proto.v1.NodeHealth;

/**
 * Writes what arrives on the control stream every twenty seconds: liveness, drift and
 * capacity.
 *
 * <p>Small on purpose. "Is this node alive" has to be cheap enough to ask constantly, so a
 * heartbeat is one indexed upsert and, in the common case where the node is converged, one
 * generation read that finds nothing to do.
 *
 * <h2>Two mappings worth knowing about</h2>
 *
 * <p><strong>Nano-CPUs to millicores.</strong> The wire uses Docker's {@code NanoCPUs},
 * which is a rate rather than a share; {@code node_status} uses millicores because
 * schema.md fixed it that way. The division happens here and in {@link RecordMachineFacts}
 * and nowhere else, so nothing downstream ever has to know there are two units.
 *
 * <p><strong>{@code accepting_workloads} becomes {@code DEGRADED}.</strong> There is no
 * column for the node's own "I am too full" flag, and it does not need one: a node that
 * has decided it will take nothing more is degraded by the definition {@code node.proto}
 * gives the word - something is wrong, nothing has been destroyed because of it, and
 * everything keeps running. Placement filters on {@code CONNECTED}, so the flag has the
 * effect the node intended without a second field to keep in step (design §7.6).
 */
@Component
public class RecordHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(RecordHeartbeat.class);

    /** Past this, the node's clock is wrong enough to break ACME, and it is logged. */
    private static final Duration NOTEWORTHY_SKEW = Duration.ofSeconds(30);

    private static final String SQL = """
            INSERT INTO node_status (node_id, connection_state, last_heartbeat_at,
                                     applied_generation, reconcile_error,
                                     cpu_millicores_capacity, cpu_millicores_used,
                                     memory_bytes_capacity, memory_bytes_used,
                                     disk_bytes_capacity, disk_bytes_used,
                                     workload_count, running_workload_count,
                                     clock_skew_millis, updated_at, version)
            VALUES (:nodeId, :state, :at, :appliedGeneration, :healthDetail,
                    :cpuCapacity, :cpuUsed, :memoryCapacity, :memoryUsed,
                    :diskCapacity, :diskUsed, :running, :running, :skew, :at, 0)
            ON CONFLICT (node_id) DO UPDATE
               SET connection_state        = EXCLUDED.connection_state,
                   last_heartbeat_at       = EXCLUDED.last_heartbeat_at,
                   applied_generation      = GREATEST(node_status.applied_generation,
                                                      EXCLUDED.applied_generation),
                   reconcile_error         = EXCLUDED.reconcile_error,
                   cpu_millicores_capacity = EXCLUDED.cpu_millicores_capacity,
                   cpu_millicores_used     = EXCLUDED.cpu_millicores_used,
                   memory_bytes_capacity   = EXCLUDED.memory_bytes_capacity,
                   memory_bytes_used       = EXCLUDED.memory_bytes_used,
                   disk_bytes_capacity     = EXCLUDED.disk_bytes_capacity,
                   disk_bytes_used         = EXCLUDED.disk_bytes_used,
                   -- A heartbeat counts what is running, not what exists. The total comes
                   -- from a status batch, and the CHECK insists running <= total, so the
                   -- larger of the two is the only value that cannot violate it.
                   workload_count          = GREATEST(node_status.workload_count,
                                                      EXCLUDED.running_workload_count),
                   running_workload_count  = EXCLUDED.running_workload_count,
                   clock_skew_millis       = EXCLUDED.clock_skew_millis,
                   updated_at              = EXCLUDED.updated_at,
                   version                 = node_status.version + 1
            """;

    private final JdbcClient jdbc;
    private final DetectGenerationDrift drift;
    private final NodeRepository nodes;

    public RecordHeartbeat(JdbcClient jdbc, DetectGenerationDrift drift, NodeRepository nodes) {
        this.jdbc = jdbc;
        this.drift = drift;
        this.nodes = nodes;
    }

    /**
     * @param receivedAt when the panel read the frame, which is what the node's own
     *                   {@code sent_at} is compared against to measure clock skew
     */
    @Transactional
    public void accept(UUID nodeId, Heartbeat heartbeat, Instant receivedAt) {
        Capacity capacity = heartbeat.getCapacity();
        long skew = heartbeat.hasSentAt()
                ? Duration.between(Instant.ofEpochSecond(heartbeat.getSentAt().getSeconds(),
                        heartbeat.getSentAt().getNanos()), receivedAt).toMillis()
                : 0L;

        jdbc.sql(SQL)
                .param("nodeId", nodeId)
                .param("state", stateOf(heartbeat).name())
                .param("at", receivedAt)
                .param("appliedGeneration", heartbeat.getAppliedGeneration())
                .param("healthDetail", heartbeat.getHealthDetail().isBlank()
                        ? null : heartbeat.getHealthDetail())
                .param("cpuCapacity", capacity.getNanoCpusTotal() / 1_000_000L)
                .param("cpuUsed", capacity.getNanoCpusUsed() / 1_000_000L)
                .param("memoryCapacity", capacity.getMemoryBytesTotal())
                .param("memoryUsed", capacity.getMemoryBytesUsed())
                .param("diskCapacity", capacity.getDiskBytesTotal())
                .param("diskUsed", capacity.getDiskBytesUsed())
                .param("running", heartbeat.getRunningWorkloads())
                .param("skew", skew)
                .update();

        if (Math.abs(skew) > NOTEWORTHY_SKEW.toMillis()) {
            log.warn("Node {} is {} ms away from the panel's clock. TLS and ACME fail in ways "
                    + "that point everywhere except at the clock.", nodeId, skew);
        }
        if (heartbeat.getHealth() == NodeHealth.NODE_HEALTH_DEGRADED) {
            log.warn("Node {} reports degraded: {}", nodeId, heartbeat.getHealthDetail());
        }

        String name = nodes.findById(nodeId).map(Node::name).orElse(nodeId.toString());
        drift.check(nodeId, name, heartbeat.getAppliedGeneration(), "heartbeat drift");
    }

    /**
     * Turns the node's own view of itself into the three-valued column.
     *
     * <p>A heartbeat arrived, so the stream is up and {@code DISCONNECTED} is not one of
     * the answers this can give.
     */
    private static NodeConnectionState stateOf(Heartbeat heartbeat) {
        if (heartbeat.getHealth() == NodeHealth.NODE_HEALTH_DEGRADED) {
            return NodeConnectionState.DEGRADED;
        }
        // A node holding capacity open but refusing new work is protecting itself before
        // the panel's own headroom arithmetic would have got there (design §7.6).
        if (heartbeat.hasCapacity() && !heartbeat.getCapacity().getAcceptingWorkloads()) {
            return NodeConnectionState.DEGRADED;
        }
        return NodeConnectionState.CONNECTED;
    }
}
