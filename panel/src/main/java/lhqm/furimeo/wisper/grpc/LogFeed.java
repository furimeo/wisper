package lhqm.furimeo.wisper.grpc;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;

import lhqm.furimeo.wisper.proto.v1.LogChunk;
import lhqm.furimeo.wisper.stats.LogSubscription;

/**
 * One live log feed, from the panel's side.
 *
 * <p>Implements {@link LogSubscription}: the handle a browser's SSE connection holds and
 * closes when the customer navigates away. Chunks arrive on the node's shared
 * {@code LogStream} call and are routed here by {@code stream_id}.
 *
 * <h2>A gap the customer is told about</h2>
 *
 * <p>{@code dropped_bytes} is accumulated rather than passed through and forgotten,
 * because the total is what the page shows. A customer told that lines are missing can act
 * on it; a customer shown a silent gap cannot, and will spend the afternoon looking for a
 * log line that was never delivered (design §7.6).
 */
final class LogFeed implements LogSubscription {

    private static final Logger log = LoggerFactory.getLogger(LogFeed.class);

    private final String streamId;
    private final UUID nodeId;
    private final Consumer<LogChunk> chunks;
    private final Consumer<LogFeed> release;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile boolean finished;

    /**
     * @param release unregisters this feed and tells the node to stop; run once, whichever
     *                way the feed ends
     */
    LogFeed(String streamId, UUID nodeId, Consumer<LogChunk> chunks, Consumer<LogFeed> release) {
        this.streamId = streamId;
        this.nodeId = nodeId;
        this.chunks = chunks;
        this.release = release;
    }

    UUID nodeId() {
        return nodeId;
    }

    /**
     * Hands one chunk to whoever is watching.
     *
     * <p>Called on a gRPC thread, in order. A sink that throws is a browser that has gone,
     * which ends the feed rather than being retried: there is nobody left to deliver to.
     */
    void deliver(LogChunk chunk) {
        dropped.addAndGet(chunk.getDroppedBytes());
        if (chunk.getEnd()) {
            finished = true;
        }
        try {
            chunks.accept(chunk);
        } catch (RuntimeException readerGone) {
            log.debug("Log feed {} lost its reader: {}", streamId, readerGone.toString());
            finished = true;
        }
        if (finished) {
            // The source ended on its own, so there is nothing to stop: drop the
            // registration without sending the node a command it does not need.
            closed.set(true);
            release.accept(this);
        }
    }

    /**
     * Ends the feed because the node's log stream went away rather than because the source
     * did.
     *
     * <p>The watcher is told, with a final chunk marked {@code end} whose text says what
     * happened. That is not a fabricated log line: it is the panel reporting the end of
     * the subscription, which really did end, and the alternative is a page that shows
     * nothing further and never says why. {@code dropped_bytes} is deliberately left at
     * zero here - the panel does not know how much output it missed and inventing a figure
     * would be worse than the gap.
     */
    void interrupted(String reason) {
        if (finished) {
            return;
        }
        finished = true;
        closed.set(true);
        try {
            chunks.accept(LogChunk.newBuilder()
                    .setStreamId(streamId)
                    .setData(ByteString.copyFromUtf8(reason))
                    .setEnd(true)
                    .build());
        } catch (RuntimeException readerGone) {
            log.debug("Log feed {} had already lost its reader", streamId);
        }
        release.accept(this);
    }

    @Override
    public String streamId() {
        return streamId;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public long droppedBytes() {
        return dropped.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        release.accept(this);
    }
}
