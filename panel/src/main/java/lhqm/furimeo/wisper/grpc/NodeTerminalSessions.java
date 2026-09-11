package lhqm.furimeo.wisper.grpc;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lhqm.furimeo.wisper.files.NodeTerminals;
import lhqm.furimeo.wisper.files.TerminalSession;
import lhqm.furimeo.wisper.files.TerminalUnavailable;
import lhqm.furimeo.wisper.node.NodeConnections;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;
import lhqm.furimeo.wisper.proto.v1.StartTerminal;
import lhqm.furimeo.wisper.proto.v1.TerminalFrame;

/**
 * The web shell: every open PTY, and the two-step dance that opens one.
 *
 * <p>Implements {@link NodeTerminals}, declared in {@code files} so the terminal
 * controller can open a shell without depending on this package (panel-ports.md §2.8).
 *
 * <h2>Two steps, joined by the session id</h2>
 *
 * <p>The panel cannot dial a node, so it cannot simply open a terminal stream. It puts a
 * {@code StartTerminal} command on the control stream and waits; the node answers by
 * opening a {@code Terminal} call whose first frame is {@code TerminalAttached}. This
 * class holds the half-built session between those two moments and joins them by the
 * session id the caller minted, so a caller sees one blocking open that either produces a
 * live shell or fails with a reason.
 *
 * <h2>A session belongs to one node</h2>
 *
 * <p>The node id is recorded when the session is reserved and checked when a node attaches
 * to it. Without that check a compromised node could attach to a session id it observed
 * and be handed a customer's keystrokes for a container on a different machine.
 */
@Component
public class NodeTerminalSessions implements NodeTerminals {

    private static final Logger log = LoggerFactory.getLogger(NodeTerminalSessions.class);

    private final NodeConnections connections;
    private final Map<String, TerminalStream> sessions = new ConcurrentHashMap<>();

    public NodeTerminalSessions(NodeConnections connections) {
        this.connections = connections;
    }

    @Override
    public TerminalSession open(UUID nodeId, StartTerminal request, Consumer<byte[]> fromNode,
                                Duration attachTimeout) {
        String sessionId = request.getSessionId();
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("A terminal session id is minted by the caller "
                    + "before the browser connects; this request carries none.");
        }
        TerminalStream session = new TerminalStream(sessionId, nodeId, request.getWorkloadId(),
                fromNode, () -> sessions.remove(sessionId));
        if (sessions.putIfAbsent(sessionId, session) != null) {
            throw new IllegalStateException("Terminal session " + sessionId + " is already open");
        }

        try {
            connections.send(nodeId, PanelMessage.newBuilder().setStartTerminal(request));
        } catch (RuntimeException notSent) {
            sessions.remove(sessionId, session);
            throw notSent;
        }

        if (!session.awaitAttach(attachTimeout)) {
            sessions.remove(sessionId, session);
            throw new TerminalUnavailable(nodeId, request.getWorkloadId(),
                    "the node did not attach within " + attachTimeout.toSeconds()
                            + "s. The container may not be running.");
        }
        log.debug("Terminal {} attached to container {} on node {}", sessionId,
                session.containerId(), nodeId);
        return session;
    }

    /**
     * Takes over the {@code Terminal} call a node has just dialled.
     *
     * <p>One call carries one session, never several: two sessions sharing an HTTP/2 flow
     * control window means a customer running {@code yes} in one tab stalls the other, and
     * gRPC already gives a stream per call for nothing.
     */
    StreamObserver<TerminalFrame> attach(UUID nodeId, StreamObserver<TerminalFrame> toNode) {
        return new Attachment(nodeId, toNode);
    }

    /** How many shells are open. For the admin page and for shutdown. */
    public int openSessions() {
        return sessions.size();
    }

    /** The node's half of one {@code Terminal} call. */
    private final class Attachment implements StreamObserver<TerminalFrame> {

        private final UUID nodeId;
        private final StreamObserver<TerminalFrame> toNode;

        private TerminalStream session;

        private Attachment(UUID nodeId, StreamObserver<TerminalFrame> toNode) {
            this.nodeId = nodeId;
            this.toNode = toNode;
        }

        @Override
        public void onNext(TerminalFrame frame) {
            if (session == null) {
                join(frame);
                return;
            }
            if (!session.sessionId().equals(frame.getSessionId())) {
                // A stray frame from an abandoned session. Dropped rather than typed into
                // somebody else's shell, which is why every frame carries the id.
                log.warn("Node {} sent a frame for session {} on the stream for {}; dropped",
                        nodeId, frame.getSessionId(), session.sessionId());
                return;
            }
            switch (frame.getPayloadCase()) {
                case DATA -> session.output(frame.getData());
                case EXIT -> session.exited(frame.getExit());
                case ATTACHED -> log.warn("Node {} attached terminal {} twice; ignored", nodeId,
                        session.sessionId());
                // Panel to node only. A node sending one is a bug on the node, not a
                // reason to kill a customer's shell.
                case RESIZE -> log.warn("Node {} sent a resize for terminal {}; ignored", nodeId,
                        session.sessionId());
                case PAYLOAD_NOT_SET -> log.debug("Node {} sent an empty terminal frame", nodeId);
            }
        }

        @Override
        public void onError(Throwable failure) {
            end(failure instanceof StatusRuntimeException status
                    ? "terminal stream error: " + status.getStatus().getCode()
                    : "terminal stream error: " + failure.getClass().getSimpleName());
        }

        @Override
        public void onCompleted() {
            end("the node closed the terminal stream");
            try {
                toNode.onCompleted();
            } catch (RuntimeException alreadyGone) {
                log.debug("A terminal stream was already closed from the other end");
            }
        }

        /**
         * The first frame, which must be the acknowledgement, for a session this panel is
         * waiting on and this node owns.
         */
        private void join(TerminalFrame frame) {
            if (frame.getPayloadCase() != TerminalFrame.PayloadCase.ATTACHED) {
                refuse(Status.FAILED_PRECONDITION, "The first frame on a terminal stream is "
                        + "TerminalAttached, not " + frame.getPayloadCase() + ".");
                return;
            }
            TerminalStream waiting = sessions.get(frame.getSessionId());
            if (waiting == null) {
                refuse(Status.NOT_FOUND, "No terminal session " + frame.getSessionId()
                        + " is open. It timed out, or the browser went away.");
                return;
            }
            if (!waiting.nodeId().equals(nodeId)) {
                log.error("Node {} tried to attach to terminal session {}, which belongs to "
                        + "node {}", nodeId, frame.getSessionId(), waiting.nodeId());
                refuse(Status.PERMISSION_DENIED, "That terminal session belongs to another "
                        + "node.");
                return;
            }
            session = waiting;
            session.bind(toNode, frame.getAttached());
        }

        private void refuse(Status status, String detail) {
            log.warn("Refusing a terminal stream from node {}: {}", nodeId, detail);
            try {
                toNode.onError(status.withDescription(detail).asRuntimeException());
            } catch (RuntimeException alreadyGone) {
                log.debug("A refused terminal stream was already closed");
            }
        }

        private void end(String reason) {
            if (session != null) {
                session.ended(reason);
                session = null;
            }
        }
    }
}
