package lhqm.furimeo.wisper.stats;

import java.util.UUID;
import java.util.function.Consumer;

import lhqm.furimeo.wisper.proto.v1.LogChunk;
import lhqm.furimeo.wisper.proto.v1.LogRequest;

/**
 * Subscribes to log output on a node.
 *
 * <p>Implemented by {@code lhqm.furimeo.wisper.grpc.NodeLogSubscriptions}. Declared in
 * {@code stats} because {@code stats} owns {@code /services/{id}/logs/**} and the
 * {@code LogStream} plumbing; {@code deploy} is the other consumer, for build output.
 *
 * <h2>One path for four sources</h2>
 *
 * <p>{@code LOG_SOURCE_CONTAINER}, {@code LOG_SOURCE_BUILD}, {@code LOG_SOURCE_CRON} and
 * {@code LOG_SOURCE_SYSTEM} all arrive here. A second path would need its own
 * backpressure, its own retention and its own bug.
 *
 * <p>Build output has one extra obligation: {@code deploy} persists each line into
 * {@code deployment_log} as well as forwarding it, keyed by
 * {@code (deployment_id, sequence)}. That unique index is what makes a redelivered chunk
 * after a reconnect a rejected duplicate instead of a doubled line, and it is the
 * cursor an SSE client resumes from.
 *
 * <h2>Chunks, not lines</h2>
 *
 * <p>A container writes half a line and then thinks for ten seconds. Buffering until a
 * newline arrives makes a build look hung, so what comes back is a run of bytes that may
 * end anywhere - including inside a multi-byte character. Reassemble for storage;
 * forward as-is to the browser, which renders whatever has arrived.
 */
public interface NodeLogs {

    /**
     * Starts a subscription and pumps its chunks to a sink.
     *
     * <p>{@code request.stream_id} is minted by the caller, so the browser's SSE
     * connection and this subscription share an id and either can be found from the
     * other. {@code tail_lines} gives the customer the last screen immediately rather
     * than making them wait for the next line, and {@code since} is what a reconnecting
     * browser sets so it does not get that screen twice.
     *
     * <p>Returns as soon as the node has accepted the subscription. Chunks arrive later,
     * on a thread the implementation owns, in order.
     *
     * @param chunks receives each chunk. Called in order; must not block for long, since
     *               one {@code LogStream} call serves every subscription on that node.
     * @throws lhqm.furimeo.wisper.node.NodeOffline if the node has no control stream
     */
    LogSubscription follow(UUID nodeId, LogRequest request, Consumer<LogChunk> chunks);
}
