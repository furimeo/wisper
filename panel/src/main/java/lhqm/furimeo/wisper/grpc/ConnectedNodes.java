package lhqm.furimeo.wisper.grpc;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.grpc.stub.StreamObserver;
import lhqm.furimeo.wisper.node.NodeConnections;
import lhqm.furimeo.wisper.node.NodeOffline;
import lhqm.furimeo.wisper.proto.v1.CommandResult;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;

/**
 * The live {@code Connect()} streams, and the panel's only way of reaching a node.
 *
 * <p>Implements {@link NodeConnections}, which is declared in {@code node} so that
 * {@code deploy}, {@code database}, {@code backup} and {@code placement} can all reach a
 * machine without any of them depending on this package (panel-ports.md §2.1).
 *
 * <p>In-memory and deliberately not persisted. A stream is a property of this process; a
 * panel that restarted has no streams, and a table saying otherwise would be a table
 * saying something false. The nodes reconnect within their backoff and the registry
 * refills itself, which is also why nothing here retries or queues.
 *
 * <h2>One stream per node</h2>
 *
 * <p>Attaching a second stream for a node evicts the first. That is the right answer for
 * the case it exists for - a tunnel dropped a stream the panel has not noticed dying, and
 * the node has already reconnected - and the case it is <em>not</em> for, two machines
 * holding one credential, has already been refused by the handshake before anything gets
 * here (design §7.3).
 */
@Component
public class ConnectedNodes implements NodeConnections {

    private static final Logger log = LoggerFactory.getLogger(ConnectedNodes.class);

    private final Map<UUID, NodeStream> streams = new ConcurrentHashMap<>();

    @Override
    public boolean isConnected(UUID nodeId) {
        NodeStream stream = streams.get(nodeId);
        return stream != null && stream.isOpen();
    }

    @Override
    public List<UUID> connectedNodeIds() {
        return streams.entrySet().stream()
                .filter(entry -> entry.getValue().isOpen())
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public void send(UUID nodeId, PanelMessage.Builder message) {
        require(nodeId).deliver(message);
    }

    @Override
    public CompletableFuture<CommandResult> call(UUID nodeId, PanelMessage.Builder command) {
        NodeStream stream = streams.get(nodeId);
        if (stream == null || !stream.isOpen()) {
            // Exceptionally rather than thrown, so a caller composing with thenApply gets
            // the failure the same way whether the node went away before the call or
            // during it.
            return CompletableFuture.failedFuture(new NodeOffline(nodeId));
        }
        return stream.dispatch(command);
    }

    /**
     * Registers a stream that has finished its handshake, evicting any earlier one.
     *
     * <p>Called by {@link NodeControlStream} and by nothing else: a stream that has not
     * negotiated its protocol version and been checked for cloning must not be reachable,
     * and the only way to guarantee that is for registration to happen after those checks
     * rather than when the call opens.
     */
    NodeStream attach(UUID nodeId, String nodeName, String remoteAddress,
                      StreamObserver<PanelMessage> toNode) {
        NodeStream stream = new NodeStream(nodeId, nodeName, remoteAddress, toNode);
        NodeStream previous = streams.put(nodeId, stream);
        if (previous != null) {
            log.info("Replacing an earlier control stream for {} (was {}, now {})", nodeName,
                    previous.remoteAddress(), remoteAddress);
            previous.detach("replaced by a newer stream from " + remoteAddress);
        }
        return stream;
    }

    /**
     * Removes a stream, if it is still the current one.
     *
     * <p>The identity check matters. A node that reconnects while the panel is still
     * tearing down its previous stream produces a late {@code onError} for the old one,
     * and removing by node id alone would drop the new stream that had just replaced it -
     * leaving a node connected and unreachable until its next reconnect.
     */
    void detach(NodeStream stream, String reason) {
        streams.remove(stream.nodeId(), stream);
        stream.detach(reason);
    }

    private NodeStream require(UUID nodeId) {
        NodeStream stream = streams.get(nodeId);
        if (stream == null || !stream.isOpen()) {
            throw new NodeOffline(nodeId);
        }
        return stream;
    }
}
