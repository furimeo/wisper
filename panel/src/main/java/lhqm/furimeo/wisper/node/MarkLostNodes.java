package lhqm.furimeo.wisper.node;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Puts the fleet screen back in step with reality.
 *
 * <p>{@code node_status.connection_state} is written when a stream opens and when it
 * closes, and there is one way for that to go wrong: the panel stops without closing
 * anything. Every row then says {@code CONNECTED} and nothing is, which is the state that
 * makes an operator distrust the screen - and a screen an operator distrusts is worse than
 * no screen.
 *
 * <p>So the sweep compares the rows against the live registry, which is the only thing
 * that actually knows, and corrects the ones that disagree.
 *
 * <h2>What it does not do</h2>
 *
 * <p>Nothing at all to the machine. A node with no heartbeat keeps running every container
 * it was given; the panel is blind, and being blind is not a reason to touch a customer's
 * workload (AGENTS.md §4.5). No placement is released, no service is moved, nothing is
 * marked failed. The only output is that the screen stops lying.
 *
 * <p>A row that says connected while the registry agrees but the heartbeats have stopped
 * is left alone and logged. gRPC's own keepalive - a ping every twenty seconds with a
 * ten-second deadline - reaps a genuinely dead peer long before the sixty-second heartbeat
 * window, so this combination means the stream is alive and the daemon inside it is stuck,
 * which is a different problem and one an operator has to see rather than have tidied
 * away.
 */
@Component
public class MarkLostNodes {

    private static final Logger log = LoggerFactory.getLogger(MarkLostNodes.class);

    private final NodeStatusRepository statuses;
    private final NodeConnections connections;
    private final RecordDisconnect recordDisconnect;
    private final NodeSettings settings;

    public MarkLostNodes(NodeStatusRepository statuses, NodeConnections connections,
                         RecordDisconnect recordDisconnect, NodeSettings settings) {
        this.statuses = statuses;
        this.connections = connections;
        this.recordDisconnect = recordDisconnect;
        this.settings = settings;
    }

    /** @return how many rows were corrected, for the job's log line */
    public int sweep() {
        Instant cutoff = Instant.now().minus(settings.heartbeatTimeout());
        List<NodeStatus> silent = statuses.findSilentSince(cutoff);
        int corrected = 0;
        for (NodeStatus status : silent) {
            if (connections.isConnected(status.nodeId())) {
                log.warn("Node {} has an open control stream but has not sent a heartbeat since "
                        + "{}. The daemon is up and its reconcile loop may not be.",
                        status.nodeId(), status.lastHeartbeatAt());
                continue;
            }
            recordDisconnect.accept(status.nodeId(), status.lastHeartbeatAt() == null
                    ? "no heartbeat since it connected at " + status.lastConnectedAt()
                    : "no heartbeat since " + status.lastHeartbeatAt());
            corrected++;
        }
        return corrected;
    }
}
