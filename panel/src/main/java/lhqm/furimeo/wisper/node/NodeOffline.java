package lhqm.furimeo.wisper.node;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The node has no open control stream, so nothing could be handed to it.
 *
 * <p>Unchecked, because almost nowhere is this recoverable in the caller's next line:
 * either the caller is publishing a spec, in which case losing it is harmless (the
 * reconnect path sends the whole spec again), or it is running a command a person is
 * waiting on, in which case the honest answer is "that node is not reachable right now".
 *
 * <p>It is deliberately not a failure of the node. A daemon behind a tunnel that has
 * been cut is still running every container it was given; a screen that reports this as
 * an outage teaches operators to ignore outages.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class NodeOffline extends RuntimeException {

    private final UUID nodeId;

    public NodeOffline(UUID nodeId) {
        super("Node " + nodeId + " has no open control stream");
        this.nodeId = nodeId;
    }

    public NodeOffline(UUID nodeId, String detail) {
        super("Node " + nodeId + " has no open control stream: " + detail);
        this.nodeId = nodeId;
    }

    /** Which node, so a caller can name it in the message it shows or the row it marks. */
    public UUID nodeId() {
        return nodeId;
    }
}
