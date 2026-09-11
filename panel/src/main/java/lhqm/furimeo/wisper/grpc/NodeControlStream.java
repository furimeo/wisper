package lhqm.furimeo.wisper.grpc;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lhqm.furimeo.wisper.node.AuthenticatedNode;
import lhqm.furimeo.wisper.node.HandshakeRefused;
import lhqm.furimeo.wisper.node.NodeProtocol;
import lhqm.furimeo.wisper.node.NodeSettings;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.node.RecordDisconnect;
import lhqm.furimeo.wisper.node.RecordHandshake;
import lhqm.furimeo.wisper.node.RecordHeartbeat;
import lhqm.furimeo.wisper.node.RecordNodeEvent;
import lhqm.furimeo.wisper.proto.v1.NodeMessage;
import lhqm.furimeo.wisper.proto.v1.PanelHello;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;

/**
 * The {@code Connect()} stream: one per running daemon, open for its whole life.
 *
 * <p>Everything else in the gRPC surface is opened in response to something that arrives
 * here, so the rules this enforces are the rules for the whole relationship:
 *
 * <ol>
 * <li>{@code NodeHello} is the first frame, and anything else on an unnegotiated stream is
 *     refused. A control channel where the panel does not yet know what it is talking to
 *     is a control channel that must not carry a command.</li>
 * <li>The protocol version is settled in that frame, without negotiating down. A
 *     mismatch ends the stream with {@code FAILED_PRECONDITION} and the node's page says
 *     it needs upgrading - two versions quietly misunderstanding each other is the failure
 *     this handshake exists to prevent (design §7.5).</li>
 * <li>Only then is the stream registered, so nothing can publish to a node that has not
 *     been checked.</li>
 * <li>The end of the stream is normal. A tunnel drops these routinely; the node keeps
 *     running every container it has, and the reconnect resends the whole spec.</li>
 * </ol>
 *
 * <p>The bean is a factory: one Spring component holding the collaborators, and a small
 * per-call {@link Session} holding the state of one stream. The alternative - a prototype
 * bean per call - would put a Spring lookup on the hot path for no benefit.
 */
@Component
public class NodeControlStream {

    private static final Logger log = LoggerFactory.getLogger(NodeControlStream.class);

    private final ConnectedNodes connections;
    private final RecordHandshake recordHandshake;
    private final RecordHeartbeat recordHeartbeat;
    private final RecordNodeEvent recordNodeEvent;
    private final RecordDisconnect recordDisconnect;
    private final PublishNodeSpec publishNodeSpec;
    private final DispatchCommandResult dispatchCommandResult;
    private final NodeSettings settings;

    public NodeControlStream(ConnectedNodes connections, RecordHandshake recordHandshake,
                             RecordHeartbeat recordHeartbeat, RecordNodeEvent recordNodeEvent,
                             RecordDisconnect recordDisconnect, PublishNodeSpec publishNodeSpec,
                             DispatchCommandResult dispatchCommandResult, NodeSettings settings) {
        this.connections = connections;
        this.recordHandshake = recordHandshake;
        this.recordHeartbeat = recordHeartbeat;
        this.recordNodeEvent = recordNodeEvent;
        this.recordDisconnect = recordDisconnect;
        this.publishNodeSpec = publishNodeSpec;
        this.dispatchCommandResult = dispatchCommandResult;
        this.settings = settings;
    }

    /** Starts one stream for the node the interceptor authenticated. */
    public StreamObserver<NodeMessage> open(StreamObserver<PanelMessage> toNode) {
        return new Session(NodeCallMetadata.caller(), NodeCallMetadata.remoteAddress(), toNode);
    }

    /** One node's control stream, from the first frame to whichever way it ends. */
    private final class Session implements StreamObserver<NodeMessage> {

        private final AuthenticatedNode node;
        private final String remoteAddress;
        private final StreamObserver<PanelMessage> toNode;

        private NodeStream stream;
        private boolean finished;

        private Session(AuthenticatedNode node, String remoteAddress,
                        StreamObserver<PanelMessage> toNode) {
            this.node = node;
            this.remoteAddress = remoteAddress;
            this.toNode = toNode;
        }

        @Override
        public void onNext(NodeMessage message) {
            if (finished) {
                return;
            }
            try {
                if (stream == null) {
                    handshake(message);
                    return;
                }
                route(message);
            } catch (HandshakeRefused refused) {
                refuse(refused);
            } catch (RuntimeException failed) {
                // One bad frame must not cost a node its control channel: it would drop
                // every command queued behind it and the node would reconnect into the
                // same frame. Log it and carry on.
                log.error("Node {} sent a frame this panel could not handle ({})",
                        node.name(), message.getPayloadCase(), failed);
            }
        }

        @Override
        public void onError(Throwable failure) {
            // Practically always the transport: a tunnel closed, a machine rebooted. Not
            // an incident, and logging it as one teaches operators to ignore the log.
            end(failure instanceof StatusRuntimeException status
                    ? "stream error: " + status.getStatus().getCode()
                    : "stream error: " + failure.getClass().getSimpleName());
        }

        @Override
        public void onCompleted() {
            end("the node closed the stream");
            if (!finished) {
                finished = true;
                toNode.onCompleted();
            }
        }

        /**
         * The first frame. Refuses anything that is not a hello, because a command
         * dispatched onto a stream whose protocol has not been agreed is exactly what the
         * handshake exists to prevent.
         */
        private void handshake(NodeMessage message) {
            if (message.getPayloadCase() != NodeMessage.PayloadCase.HELLO) {
                refuse(new HandshakeRefused(HandshakeRefused.Reason.PROTOCOL_UNSUPPORTED,
                        "The first frame on a control stream is NodeHello, not "
                                + message.getPayloadCase() + "."));
                return;
            }
            recordHandshake.accept(node.nodeId(), message.getHello(), remoteAddress);

            // Register, greet, then catch up - in that order, and none of the three can
            // move. The stream has to exist before a spec is published to it or the
            // catch-up frame is swallowed as undeliverable; PanelHello has to be the
            // panel's first frame, because that is what node.proto says accepts the
            // handshake.
            stream = connections.attach(node.nodeId(), node.name(), remoteAddress, toNode);
            stream.deliver(PanelMessage.newBuilder().setHello(PanelHello.newBuilder()
                    .setProtocolVersion(NodeProtocol.SUPPORTED)
                    .setNodeId(node.nodeId().toString())
                    .setPanelVersion(panelVersion())
                    .setReconcileIntervalSeconds(settings.reconcileInterval().toSeconds())
                    .setHeartbeatIntervalSeconds(settings.heartbeatInterval().toSeconds())
                    .setCurrentGeneration(recordHandshake.currentGeneration(node.nodeId()))));

            if (recordHandshake.catchUp(node.nodeId(), message.getHello())) {
                refuse(new HandshakeRefused(HandshakeRefused.Reason.NOT_ACCEPTING_CONTROL,
                        node.name() + " reports a generation this panel never published. Two "
                                + "panels are driving it; it has been suspended and no "
                                + "container was touched."));
            }
        }

        /** Every frame after the handshake. */
        private void route(NodeMessage message) {
            switch (message.getPayloadCase()) {
                case HEARTBEAT -> recordHeartbeat.accept(node.nodeId(), message.getHeartbeat(),
                        Instant.now());
                case SPEC_APPLIED -> publishNodeSpec.markApplied(node.nodeId(),
                        message.getSpecApplied());
                case COMMAND_RESULT -> {
                    // Both, always. The future is what the operator's page is waiting on;
                    // the dispatch is what makes the outcome survive them closing the tab.
                    stream.complete(message.getCommandResult());
                    dispatchCommandResult.accept(node.nodeId(), message.getCommandResult());
                }
                case EVENT -> recordNodeEvent.accept(node.nodeId(), message.getEvent());
                case HELLO -> log.warn("Node {} sent a second NodeHello on an established "
                        + "stream; ignored", node.name());
                case PAYLOAD_NOT_SET -> log.debug("Node {} sent an empty frame", node.name());
            }
        }

        /** Ends the stream with a status the daemon can act on, and says why on the page. */
        private void refuse(HandshakeRefused refused) {
            if (finished) {
                return;
            }
            finished = true;
            log.warn("Refusing the control stream from {} at {}: {}", node.name(), remoteAddress,
                    refused.getMessage());
            Status status = switch (refused.reason()) {
                case PROTOCOL_UNSUPPORTED -> Status.FAILED_PRECONDITION;
                case NOT_ACCEPTING_CONTROL, DUPLICATE_STREAM -> Status.PERMISSION_DENIED;
            };
            toNode.onError(status.withDescription(refused.getMessage()).asRuntimeException());
            // Unregisters the stream when the refusal came after it was attached, which is
            // the case for a node the catch-up decided has two panels driving it.
            end(refused.reason().name());
        }

        /** Tears the stream down once, whichever of the three ways it ended. */
        private void end(String reason) {
            if (stream != null) {
                connections.detach(stream, reason);
                stream = null;
            }
            recordDisconnect.accept(node.nodeId(), reason);
        }

        /**
         * What the panel calls itself in the handshake. Read from the jar's manifest, and
         * {@code "dev"} when there is no manifest - which is every run from an IDE.
         */
        private String panelVersion() {
            String version = NodeControlStream.class.getPackage().getImplementationVersion();
            return version == null ? "dev" : version;
        }
    }
}
