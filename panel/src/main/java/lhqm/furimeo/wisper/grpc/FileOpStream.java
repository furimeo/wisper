package lhqm.furimeo.wisper.grpc;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.stub.StreamObserver;
import lhqm.furimeo.wisper.files.FileOperationFailed;
import lhqm.furimeo.wisper.node.NodeOffline;
import lhqm.furimeo.wisper.proto.v1.CancelRequest;
import lhqm.furimeo.wisper.proto.v1.FileError;
import lhqm.furimeo.wisper.proto.v1.FileErrorCode;
import lhqm.furimeo.wisper.proto.v1.FileEvent;
import lhqm.furimeo.wisper.proto.v1.FileRequest;

/**
 * One node's {@code FileOp} stream, and the operations in flight on it.
 *
 * <p>The call is inverted like everything else here: the node dialled, so the panel writes
 * {@link FileRequest}s down the response side and reads {@link FileEvent}s coming back up.
 * One stream serves the whole machine, so every operation for that node shares it and each
 * one is told apart by its {@code request_id}.
 *
 * <h2>Handing an event to the thread that asked for it</h2>
 *
 * <p>Events arrive on a gRPC thread and are consumed by whichever request thread started
 * the operation, so each in-flight operation owns a small bounded queue between the two.
 * Bounded on purpose: a download whose browser has stopped reading must stop the node
 * reading the file, and a queue that grows instead is a panel that buffers a customer's
 * eight-gigabyte archive into the heap. When it fills, the gRPC thread waits - which stalls
 * this node's file stream and nothing else, exactly as {@code files.NodeFiles} documents.
 *
 * <h2>Nothing waits for a stream that has gone</h2>
 *
 * <p>When the stream ends, every operation still waiting is finished with
 * {@link NodeOffline}. A request thread parked on a machine that went away is the failure
 * this project keeps finding in its predecessor, and it is always a leaked thread rather
 * than a message on a screen.
 */
final class FileOpStream {

    private static final Logger log = LoggerFactory.getLogger(FileOpStream.class);

    /**
     * Events buffered per operation before the node is made to wait.
     *
     * <p>Sixty-four 64 KiB chunks is about four megabytes in flight for a download, which
     * is enough that a browser on a phone with a variable connection does not stall the
     * node on every hiccup, and small enough that a hundred concurrent downloads is a
     * bounded amount of heap rather than an outage.
     */
    private static final int EVENT_BUFFER = 64;

    /** How often a blocked producer or consumer re-checks whether the operation is over. */
    private static final long POLL_MILLIS = 100L;

    private final UUID nodeId;
    private final StreamObserver<FileRequest> toNode;
    private final Map<String, InFlight> inFlight = new ConcurrentHashMap<>();
    private final AtomicBoolean open = new AtomicBoolean(true);

    FileOpStream(UUID nodeId, StreamObserver<FileRequest> toNode) {
        this.nodeId = nodeId;
        this.toNode = toNode;
    }

    UUID nodeId() {
        return nodeId;
    }

    boolean isOpen() {
        return open.get();
    }

    /**
     * Registers an operation and puts it on the wire.
     *
     * @throws NodeOffline if the stream has ended, in which case nothing is registered
     */
    InFlight begin(FileRequest request) {
        InFlight operation = new InFlight(nodeId, request.getRequestId(), request.getRootId());
        inFlight.put(request.getRequestId(), operation);
        try {
            write(request);
        } catch (NodeOffline offline) {
            inFlight.remove(request.getRequestId(), operation);
            throw offline;
        }
        return operation;
    }

    /** Forgets an operation whose caller has finished with it. Idempotent. */
    void finish(String requestId) {
        inFlight.remove(requestId);
    }

    /**
     * Routes one event to the operation it answers.
     *
     * <p>An event for an operation nobody is waiting on is dropped at debug. That is the
     * normal end of a cancelled read - the node was already sending chunks when the stop
     * arrived - and it must not be louder than that.
     */
    void deliver(FileEvent event) {
        InFlight operation = inFlight.get(event.getRequestId());
        if (operation == null) {
            log.debug("Node {} answered file request {}, which nobody is waiting on", nodeId,
                    event.getRequestId());
            return;
        }
        if (isTerminal(event)) {
            // Removed before the hand-off so a stream ending immediately afterwards cannot
            // fail an operation the node has in fact answered.
            inFlight.remove(event.getRequestId(), operation);
        }
        operation.publish(event);
    }

    /**
     * Tells the node to stop an operation, and finishes the caller waiting on it.
     *
     * <p>Never throws: cancelling something that has already finished is the normal outcome
     * of a browser closing a download, and the node being unreachable means there is
     * nothing left to cancel.
     */
    void cancel(String requestId) {
        InFlight operation = inFlight.remove(requestId);
        try {
            write(FileRequest.newBuilder()
                    .setRequestId(UUID.randomUUID().toString())
                    // The root the cancelled operation was addressed to, so the node can
                    // find it without a second index.
                    .setRootId(operation == null ? "" : operation.rootId())
                    .setCancel(CancelRequest.newBuilder().setCancelledRequestId(requestId))
                    .build());
        } catch (NodeOffline gone) {
            log.debug("Node {} went away before file request {} could be cancelled", nodeId,
                    requestId);
        }
        if (operation != null) {
            operation.end(new FileOperationFailed(FileError.newBuilder()
                    .setCode(FileErrorCode.FILE_ERROR_CODE_CANCELLED)
                    .setDetail("The panel cancelled this operation.")
                    .build()));
        }
    }

    /** Marks the stream gone and finishes everything still waiting on it. Idempotent. */
    void detach(String reason) {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        if (!inFlight.isEmpty()) {
            log.debug("Failing {} file operation(s) still waiting on node {}: {}",
                    inFlight.size(), nodeId, reason);
        }
        inFlight.values().forEach(operation -> operation.end(new NodeOffline(nodeId, reason)));
        inFlight.clear();
    }

    /**
     * Whether this event is the last one for its operation.
     *
     * <p>A chunk is only terminal when it says so; everything else answers once. An event
     * with nothing set counts as terminal so a node sending a frame this panel does not
     * understand ends the operation rather than parking its caller forever.
     */
    static boolean isTerminal(FileEvent event) {
        return event.getResultCase() != FileEvent.ResultCase.CHUNK || event.getChunk().getLast();
    }

    /**
     * The write, serialised.
     *
     * <p>A {@link StreamObserver} is not safe for concurrent use and several request
     * threads share this one. Writing to a closed stream throws, which is the same fact as
     * the stream having ended, so both become {@link NodeOffline}.
     */
    private synchronized void write(FileRequest request) {
        if (!open.get()) {
            throw new NodeOffline(nodeId, "the file stream has ended");
        }
        try {
            toNode.onNext(request);
        } catch (RuntimeException streamGone) {
            detach("write failed: " + streamGone.getMessage());
            throw new NodeOffline(nodeId, streamGone.getMessage());
        }
    }

    /** One operation waiting for its answer, and the queue that carries it across threads. */
    static final class InFlight {

        private final UUID nodeId;
        private final String requestId;
        private final String rootId;
        private final BlockingQueue<FileEvent> events = new ArrayBlockingQueue<>(EVENT_BUFFER);
        private volatile RuntimeException ended;

        private InFlight(UUID nodeId, String requestId, String rootId) {
            this.nodeId = nodeId;
            this.requestId = requestId;
            this.rootId = rootId;
        }

        String requestId() {
            return requestId;
        }

        String rootId() {
            return rootId;
        }

        /**
         * Hands one event to the waiting thread, blocking while it is behind.
         *
         * <p>Called on a gRPC thread. Blocking here is the backpressure: it stops the node
         * reading a file faster than the browser can take it.
         */
        void publish(FileEvent event) {
            try {
                while (ended == null) {
                    if (events.offer(event, POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                        return;
                    }
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * The next event, waiting for it.
         *
         * <p>Queued events are drained before a failure is raised, so a node that answered
         * and then closed its stream produces the answer rather than "that node is away".
         *
         * @throws NodeOffline           if the stream ended before the operation did
         * @throws FileOperationFailed   if the panel cancelled the operation
         */
        FileEvent take() {
            while (true) {
                FileEvent event;
                try {
                    event = events.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    throw new NodeOffline(nodeId, "the panel stopped waiting for file request "
                            + requestId);
                }
                if (event != null) {
                    return event;
                }
                RuntimeException over = ended;
                if (over != null) {
                    throw over;
                }
            }
        }

        /** Finishes the operation with the reason the caller should see. First one wins. */
        void end(RuntimeException why) {
            if (ended == null) {
                ended = why;
            }
        }
    }
}
