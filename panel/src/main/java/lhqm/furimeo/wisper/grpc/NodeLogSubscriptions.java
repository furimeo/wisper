package lhqm.furimeo.wisper.grpc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.google.protobuf.Timestamp;

import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lhqm.furimeo.wisper.node.NodeConnections;
import lhqm.furimeo.wisper.node.NodeOffline;
import lhqm.furimeo.wisper.proto.v1.Ack;
import lhqm.furimeo.wisper.proto.v1.LogChunk;
import lhqm.furimeo.wisper.proto.v1.LogRequest;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;
import lhqm.furimeo.wisper.proto.v1.StopLogStream;
import lhqm.furimeo.wisper.stats.LogSubscription;
import lhqm.furimeo.wisper.stats.NodeLogs;

/**
 * Every live log feed, and the one stream per node that carries all of them.
 *
 * <p>Implements {@link NodeLogs}, declared in {@code stats} so a log page and a deployment's
 * build output can both subscribe without depending on this package (panel-ports.md §2.9).
 *
 * <h2>Three channels for one feature, and why</h2>
 *
 * <p>A subscription starts with {@code LogRequest} on the control stream, its output comes
 * back on the node's long-lived {@code LogStream} call, and it stops with
 * {@code StopLogStream} on the control stream again. The output has its own call because a
 * container that writes a megabyte a second must not be able to delay a heartbeat; the
 * commands travel on the control stream because that is the one stream guaranteed to
 * exist. Joining the two is this class's whole job, and the join is the {@code stream_id}
 * the caller minted.
 *
 * <p>A node's chunks are routed only to feeds registered for that node. A feed id observed
 * by one machine cannot be answered by another.
 */
@Component
public class NodeLogSubscriptions implements NodeLogs {

    private static final Logger log = LoggerFactory.getLogger(NodeLogSubscriptions.class);

    private final NodeConnections connections;
    private final Map<String, LogFeed> feeds = new ConcurrentHashMap<>();

    public NodeLogSubscriptions(NodeConnections connections) {
        this.connections = connections;
    }

    @Override
    public LogSubscription follow(UUID nodeId, LogRequest request, Consumer<LogChunk> chunks) {
        String streamId = request.getStreamId();
        if (streamId.isBlank()) {
            throw new IllegalArgumentException("A log stream id is minted by the caller so the "
                    + "browser's connection and the subscription share a name; this request "
                    + "carries none.");
        }
        LogFeed feed = new LogFeed(streamId, nodeId, chunks, this::stop);
        if (feeds.putIfAbsent(streamId, feed) != null) {
            throw new IllegalStateException("Log stream " + streamId + " is already open");
        }
        try {
            connections.send(nodeId, PanelMessage.newBuilder().setStartLogStream(request));
        } catch (RuntimeException notSent) {
            feeds.remove(streamId, feed);
            throw notSent;
        }
        log.debug("Following {} on node {} (source {}, subject {})", streamId, nodeId,
                request.getSource(), request.getSubjectId());
        return feed;
    }

    /**
     * Takes over the {@code LogStream} call a node has just dialled.
     *
     * <p>Client-streaming: chunks come up, one {@link Ack} goes back at the end. One
     * acknowledgement rather than one per chunk, because nothing on the node is waiting for
     * them and answering each would double the traffic this call exists to keep cheap.
     */
    StreamObserver<LogChunk> attach(UUID nodeId, StreamObserver<Ack> answer) {
        return new Attachment(nodeId, answer);
    }

    /** How many feeds are open. For the admin page and for tests. */
    public int openFeeds() {
        return feeds.size();
    }

    /**
     * Unregisters a feed and tells the node to stop producing it.
     *
     * <p>Never throws. It runs from an SSE completion callback, and the node being away is
     * the normal reason a feed is being released in the first place - there is nothing left
     * to stop.
     */
    private void stop(LogFeed feed) {
        if (feeds.remove(feed.streamId(), feed) && !feed.isFinished()) {
            try {
                connections.send(feed.nodeId(), PanelMessage.newBuilder()
                        .setStopLogStream(StopLogStream.newBuilder()
                                .setStreamId(feed.streamId())
                                .setReason("nobody is reading it")));
            } catch (NodeOffline gone) {
                log.debug("Node {} is away, so log stream {} needs no stop", feed.nodeId(),
                        feed.streamId());
            }
        }
    }

    /** Ends every feed on a node whose log stream has gone. */
    private void endFeedsOf(UUID nodeId, String reason) {
        List<LogFeed> orphaned = feeds.values().stream()
                .filter(feed -> feed.nodeId().equals(nodeId))
                .toList();
        orphaned.forEach(feed -> feed.interrupted(reason));
    }

    /** The node's half of one {@code LogStream} call. */
    private final class Attachment implements StreamObserver<LogChunk> {

        private final UUID nodeId;
        private final StreamObserver<Ack> answer;

        private long accepted;

        private Attachment(UUID nodeId, StreamObserver<Ack> answer) {
            this.nodeId = nodeId;
            this.answer = answer;
        }

        @Override
        public void onNext(LogChunk chunk) {
            accepted++;
            LogFeed feed = feeds.get(chunk.getStreamId());
            if (feed == null) {
                // A chunk for a feed the panel has released: the browser closed while the
                // stop was in flight. Routine, and the node stops on its own.
                log.debug("Node {} sent a chunk for log stream {}, which nobody is reading",
                        nodeId, chunk.getStreamId());
                return;
            }
            if (!feed.nodeId().equals(nodeId)) {
                log.error("Node {} sent a chunk for log stream {}, which belongs to node {}; "
                        + "dropped", nodeId, chunk.getStreamId(), feed.nodeId());
                return;
            }
            feed.deliver(chunk);
        }

        @Override
        public void onError(Throwable failure) {
            endFeedsOf(nodeId, failure instanceof StatusRuntimeException status
                    ? "The node's log stream ended (" + status.getStatus().getCode()
                            + "). Reload to start following again."
                    : "The node's log stream ended. Reload to start following again.");
        }

        @Override
        public void onCompleted() {
            endFeedsOf(nodeId, "The node closed its log stream. Reload to start following "
                    + "again.");
            Instant at = Instant.now();
            try {
                answer.onNext(Ack.newBuilder()
                        .setReceivedAt(Timestamp.newBuilder()
                                .setSeconds(at.getEpochSecond())
                                .setNanos(at.getNano()))
                        .setAccepted(accepted)
                        .build());
                answer.onCompleted();
            } catch (RuntimeException alreadyGone) {
                log.debug("Node {} closed its log stream before the panel could answer", nodeId);
            }
        }
    }
}
