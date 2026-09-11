package lhqm.furimeo.wisper.grpc;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lhqm.furimeo.wisper.files.FileOperationFailed;
import lhqm.furimeo.wisper.files.NodeFiles;
import lhqm.furimeo.wisper.node.NodeOffline;
import lhqm.furimeo.wisper.proto.v1.FileEvent;
import lhqm.furimeo.wisper.proto.v1.FileRequest;

/**
 * The file manager's back end: the {@code FileOp} streams, one per node.
 *
 * <p>Implements {@link NodeFiles}, declared in {@code files} so that the file manager and
 * {@code deploy}'s archive upload can reach a machine without depending on this package
 * (panel-ports.md §2.7). There is no SSH and no SFTP in wisper, so everything a customer
 * ever does to a file on a node passes through here.
 *
 * <p>What this class adds over {@link FileOpStream} is the translation between the wire
 * and the port: a {@code FileError} becomes {@link FileOperationFailed} with its code
 * intact, because {@code NOT_FOUND} and {@code PATH_ESCAPES_ROOT} need completely
 * different handling and a sentence cannot be branched on.
 *
 * <p>Paths are never absolute and never resolved here. Every request names a
 * {@code root_id} from the node's current spec, and the node refuses anything that leaves
 * it - which is the only place that check can be made honestly, because only the node
 * knows what the root actually is on disk.
 */
@Component
public class NodeFileTransfers implements NodeFiles {

    private static final Logger log = LoggerFactory.getLogger(NodeFileTransfers.class);

    private final Map<UUID, FileOpStream> streams = new ConcurrentHashMap<>();

    @Override
    public FileEvent call(UUID nodeId, FileRequest request) {
        return pump(nodeId, request, event -> { });
    }

    @Override
    public void stream(UUID nodeId, FileRequest request, Consumer<FileEvent> events) {
        pump(nodeId, request, events);
    }

    @Override
    public void cancel(UUID nodeId, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        FileOpStream stream = streams.get(nodeId);
        if (stream == null) {
            return;
        }
        stream.cancel(requestId);
    }

    /**
     * Takes over the {@code FileOp} stream a node has just dialled.
     *
     * <p>A second stream for one node replaces the first, for the same reason a second
     * control stream does: a tunnel dropped one the panel has not noticed dying. Whatever
     * was in flight on the old one is finished with {@link NodeOffline} rather than left
     * waiting for a socket that is gone.
     */
    StreamObserver<FileEvent> attach(UUID nodeId, StreamObserver<FileRequest> toNode) {
        FileOpStream stream = new FileOpStream(nodeId, toNode);
        FileOpStream previous = streams.put(nodeId, stream);
        if (previous != null) {
            previous.detach("replaced by a newer file stream");
        }
        log.debug("Node {} opened a file stream", nodeId);
        return new Attachment(stream, toNode);
    }

    /**
     * Runs one operation and feeds every event to a sink until the terminal one.
     *
     * <p>The sink runs on the calling thread, in order, which is what {@link NodeFiles}
     * promises: a controller writing into a servlet output stream is the backpressure for
     * a download, and moving that onto a pool thread would only move where it blocks.
     */
    private FileEvent pump(UUID nodeId, FileRequest request, Consumer<FileEvent> sink) {
        FileOpStream stream = require(nodeId);
        FileOpStream.InFlight operation = stream.begin(request);
        try {
            while (true) {
                FileEvent event = operation.take();
                if (event.getResultCase() == FileEvent.ResultCase.ERROR) {
                    throw new FileOperationFailed(event.getError());
                }
                sink.accept(event);
                if (FileOpStream.isTerminal(event)) {
                    return event;
                }
            }
        } finally {
            stream.finish(operation.requestId());
        }
    }

    private FileOpStream require(UUID nodeId) {
        FileOpStream stream = streams.get(nodeId);
        if (stream == null || !stream.isOpen()) {
            throw new NodeOffline(nodeId, "no file stream is open");
        }
        return stream;
    }

    private void release(FileOpStream stream, String reason) {
        streams.remove(stream.nodeId(), stream);
        stream.detach(reason);
    }

    /** The node's half of one {@code FileOp} call. */
    private final class Attachment implements StreamObserver<FileEvent> {

        private final FileOpStream stream;
        private final StreamObserver<FileRequest> toNode;

        private Attachment(FileOpStream stream, StreamObserver<FileRequest> toNode) {
            this.stream = stream;
            this.toNode = toNode;
        }

        @Override
        public void onNext(FileEvent event) {
            stream.deliver(event);
        }

        @Override
        public void onError(Throwable failure) {
            // A tunnel closed or the daemon restarted. Routine, and the node reopens the
            // stream on its next reconnect.
            release(stream, failure instanceof StatusRuntimeException status
                    ? "file stream error: " + status.getStatus().getCode()
                    : "file stream error: " + failure.getClass().getSimpleName());
        }

        @Override
        public void onCompleted() {
            release(stream, "the node closed its file stream");
            try {
                toNode.onCompleted();
            } catch (RuntimeException alreadyGone) {
                log.debug("Node {} closed its file stream before the panel could answer",
                        stream.nodeId());
            }
        }
    }
}
