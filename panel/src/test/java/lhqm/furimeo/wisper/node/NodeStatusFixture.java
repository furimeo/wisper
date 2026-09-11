package lhqm.furimeo.wisper.node;

import java.time.Instant;
import java.util.UUID;

/**
 * Ready-made {@link NodeStatus} rows.
 *
 * <p>The record has thirty-two components because {@code node_status} has thirty-two
 * columns. Writing that constructor out in four test classes would mean four places to fix
 * when a column is added, and three of them would be fixed by counting commas.
 */
final class NodeStatusFixture {

    private NodeStatusFixture() {
    }

    /** A node with an open stream, converged, healthy. */
    static NodeStatus connected(UUID nodeId, String remoteAddress, long appliedGeneration) {
        return of(nodeId, NodeConnectionState.CONNECTED, remoteAddress, appliedGeneration,
                Instant.now());
    }

    /** A node the panel has lost sight of. Its containers are still running. */
    static NodeStatus disconnected(UUID nodeId, Instant lastHeartbeatAt) {
        return of(nodeId, NodeConnectionState.DISCONNECTED, null, 7L, lastHeartbeatAt);
    }

    static NodeStatus of(UUID nodeId, NodeConnectionState state, String remoteAddress,
                         long appliedGeneration, Instant lastHeartbeatAt) {
        return new NodeStatus(nodeId, state, lastHeartbeatAt, lastHeartbeatAt, null,
                remoteAddress, "0.1.0", NodeProtocol.SUPPORTED, appliedGeneration,
                lastHeartbeatAt, null, 4, 4000L, 500L, 8_589_934_592L, 2_147_483_648L,
                214_748_364_800L, 21_474_836_480L, 3, 3, true, "27.1.1", true, "6.10.0",
                "debian 13", 12L, "xfs", true, null, null, Instant.now(), 1L);
    }
}
