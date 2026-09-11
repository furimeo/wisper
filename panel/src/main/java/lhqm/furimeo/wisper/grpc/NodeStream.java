package lhqm.furimeo.wisper.grpc;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.stub.StreamObserver;
import lhqm.furimeo.wisper.node.NodeOffline;
import lhqm.furimeo.wisper.proto.v1.CommandResult;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;

/**
 * One node's open control stream, seen from the write side.
 *
 * <p>Everything the panel sends a node goes through an instance of this, and it exists
 * because a gRPC {@link StreamObserver} has two properties that are easy to forget until
 * production finds them: it is not safe for concurrent use, and writing to a closed one
 * throws. Several request threads, the reconcile publisher and the sweep all reach a node
 * at once, so the write is serialised here and the two failure modes are turned into one
 * exception the rest of the panel already understands, {@link NodeOffline}.
 *
 * <h2>Correlation has one owner</h2>
 *
 * <p>Callers hand over a builder with the payload set and no {@code command_id}. This
 * stamps one. That is the whole reason {@link lhqm.furimeo.wisper.node.NodeConnections}
 * takes a builder rather than a message: with two places minting ids there is eventually
 * a command whose result nobody is waiting on and a future that never completes.
 *
 * <h2>Nothing waits forever</h2>
 *
 * <p>When the stream ends, every pending future is completed exceptionally. A deployment
 * blocked on a node that went away gets an answer - "that node is not reachable" - instead
 * of a request thread parked until the process restarts.
 */
final class NodeStream {

    private static final Logger log = LoggerFactory.getLogger(NodeStream.class);

    private final UUID nodeId;
    private final String nodeName;
    private final String remoteAddress;
    private final StreamObserver<PanelMessage> toNode;
    private final Map<String, CompletableFuture<CommandResult>> pending =
            new ConcurrentHashMap<>();
    private final AtomicBoolean open = new AtomicBoolean(true);

    NodeStream(UUID nodeId, String nodeName, String remoteAddress,
               StreamObserver<PanelMessage> toNode) {
        this.nodeId = nodeId;
        this.nodeName = nodeName;
        this.remoteAddress = remoteAddress;
        this.toNode = toNode;
    }

    UUID nodeId() {
        return nodeId;
    }

    String nodeName() {
        return nodeName;
    }

    String remoteAddress() {
        return remoteAddress;
    }

    boolean isOpen() {
        return open.get();
    }

    /**
     * Puts one message on the stream and returns.
     *
     * @throws NodeOffline if the stream has ended, or the write fails - which is the same
     *                     thing a moment earlier
     */
    void deliver(PanelMessage.Builder message) {
        write(message.setCommandId(UUID.randomUUID().toString()).build());
    }

    /**
     * Puts one command on the stream and returns a future for its result.
     *
     * <p>No timeout is applied. The sensible one differs by two orders of magnitude
     * between rotating a database password and restoring a snapshot, so the caller sets
     * it with {@code orTimeout} - and the pending entry is dropped whenever the future
     * completes, including by that timeout, so a caller giving up does not leak an entry.
     */
    CompletableFuture<CommandResult> dispatch(PanelMessage.Builder command) {
        String commandId = UUID.randomUUID().toString();
        CompletableFuture<CommandResult> answer = new CompletableFuture<>();
        pending.put(commandId, answer);
        answer.whenComplete((result, failure) -> pending.remove(commandId));
        try {
            write(command.setCommandId(commandId).build());
        } catch (NodeOffline offline) {
            answer.completeExceptionally(offline);
        }
        return answer;
    }

    /**
     * Hands a result to whoever is waiting for it.
     *
     * @return false when nothing was waiting, which happens for a command whose caller
     *         timed out and for a node replaying a result after a reconnect. Neither is a
     *         problem and neither should be treated as one.
     */
    boolean complete(CommandResult result) {
        CompletableFuture<CommandResult> waiting = pending.remove(result.getCommandId());
        if (waiting == null) {
            return false;
        }
        waiting.complete(result);
        return true;
    }

    /**
     * Marks the stream gone and fails everything still waiting on it.
     *
     * <p>Idempotent: {@code onError} and {@code onCompleted} can both arrive, and so can a
     * cancellation the transport noticed first.
     */
    void detach(String reason) {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        if (!pending.isEmpty()) {
            log.debug("Failing {} command(s) still waiting on {}: {}", pending.size(), nodeName,
                    reason);
        }
        pending.values().forEach(waiting ->
                waiting.completeExceptionally(new NodeOffline(nodeId, reason)));
        pending.clear();
    }

    /**
     * The write itself, serialised.
     *
     * <p>{@code synchronized} on this instance and not on the observer, so the lock is
     * visibly owned by the object that owns the stream. Contention is a handful of frames
     * a minute per node.
     */
    private synchronized void write(PanelMessage message) {
        if (!open.get()) {
            throw new NodeOffline(nodeId, "the control stream has ended");
        }
        try {
            toNode.onNext(message);
        } catch (RuntimeException streamGone) {
            // The transport noticed before the handler did. Treating it as anything other
            // than "offline" would make every caller learn a second failure mode.
            detach("write failed: " + streamGone.getMessage());
            throw new NodeOffline(nodeId, streamGone.getMessage());
        }
    }
}
