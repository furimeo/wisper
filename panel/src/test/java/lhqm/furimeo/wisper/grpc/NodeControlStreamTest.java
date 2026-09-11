package lhqm.furimeo.wisper.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lhqm.furimeo.wisper.node.AuthenticatedNode;
import lhqm.furimeo.wisper.node.HandshakeRefused;
import lhqm.furimeo.wisper.node.NodeOffline;
import lhqm.furimeo.wisper.node.NodeProtocol;
import lhqm.furimeo.wisper.node.NodeSettings;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.node.RecordDisconnect;
import lhqm.furimeo.wisper.node.RecordHandshake;
import lhqm.furimeo.wisper.node.RecordHeartbeat;
import lhqm.furimeo.wisper.node.RecordNodeEvent;
import lhqm.furimeo.wisper.proto.v1.CommandResult;
import lhqm.furimeo.wisper.proto.v1.Heartbeat;
import lhqm.furimeo.wisper.proto.v1.NodeEvent;
import lhqm.furimeo.wisper.proto.v1.NodeEventKind;
import lhqm.furimeo.wisper.proto.v1.NodeHello;
import lhqm.furimeo.wisper.proto.v1.NodeMessage;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;
import lhqm.furimeo.wisper.proto.v1.ReconcileNow;
import lhqm.furimeo.wisper.proto.v1.SpecApplied;

/**
 * The order of the first two frames, and the three ways a stream ends.
 *
 * <p>Everything the panel can do to a node is dispatched onto this stream, so what it
 * refuses is more interesting than what it accepts. Two rules are load-bearing and both
 * are checked here: nothing is dispatched onto a stream whose protocol has not been
 * agreed, and nothing is left waiting on a stream that has gone.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NodeControlStreamTest {

    private static final String ADDRESS = "198.51.100.7";

    private final UUID nodeId = UUID.randomUUID();
    private final AuthenticatedNode caller =
            new AuthenticatedNode(nodeId, "node-a", "ENROLLED", 47L);
    private final ConnectedNodes connections = new ConnectedNodes();
    private final RecordingStream toNode = new RecordingStream();

    @Mock
    private RecordHandshake recordHandshake;

    @Mock
    private RecordHeartbeat recordHeartbeat;

    @Mock
    private RecordNodeEvent recordNodeEvent;

    @Mock
    private RecordDisconnect recordDisconnect;

    @Mock
    private PublishNodeSpec publishNodeSpec;

    @Mock
    private DispatchCommandResult dispatchCommandResult;

    private NodeControlStream controlStream;

    @BeforeEach
    void aPanelWillingToTalk() {
        controlStream = new NodeControlStream(connections, recordHandshake, recordHeartbeat,
                recordNodeEvent, recordDisconnect, publishNodeSpec, dispatchCommandResult,
                settings());
        given(recordHandshake.currentGeneration(nodeId)).willReturn(47L);
        given(recordHandshake.catchUp(eq(nodeId), any(NodeHello.class))).willReturn(false);
    }

    @Test
    void theHandshakeIsAnsweredWithPanelHelloAndOnlyThenIsTheNodeReachable() throws Exception {
        StreamObserver<NodeMessage> session = open();
        session.onNext(NodeMessage.newBuilder().setHello(hello(NodeProtocol.SUPPORTED)).build());

        PanelMessage greeting = toNode.written().get(0);
        assertThat(greeting.hasHello()).isTrue();
        assertThat(greeting.getHello().getProtocolVersion()).isEqualTo(NodeProtocol.SUPPORTED);
        assertThat(greeting.getHello().getNodeId()).isEqualTo(nodeId.toString());
        assertThat(greeting.getHello().getCurrentGeneration()).isEqualTo(47L);
        assertThat(connections.isConnected(nodeId)).isTrue();
        // Registered before the catch-up, or the spec it publishes is written to a stream
        // that does not exist yet and is swallowed as undeliverable.
        verify(recordHandshake).catchUp(eq(nodeId), any(NodeHello.class));
    }

    @Test
    void anythingButHelloOnAnUnnegotiatedStreamEndsIt() throws Exception {
        StreamObserver<NodeMessage> session = open();
        session.onNext(NodeMessage.newBuilder().setHeartbeat(Heartbeat.newBuilder()).build());

        assertThat(status(toNode.error()).getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(connections.isConnected(nodeId)).isFalse();
        verifyNoInteractions(recordHeartbeat);
        verify(recordDisconnect).accept(eq(nodeId), anyString());
    }

    @Test
    void aProtocolThePanelCannotSpeakEndsTheStreamBeforeAnyFrameIsInterpreted()
            throws Exception {
        willThrow(new HandshakeRefused(HandshakeRefused.Reason.PROTOCOL_UNSUPPORTED,
                "This node speaks protocol 2 and the panel speaks 1. The panel is older."))
                .given(recordHandshake).accept(eq(nodeId), any(NodeHello.class), eq(ADDRESS));

        StreamObserver<NodeMessage> session = open();
        session.onNext(NodeMessage.newBuilder()
                .setHello(hello(NodeProtocol.SUPPORTED + 1)).build());

        assertThat(status(toNode.error()).getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(status(toNode.error()).getDescription()).contains("protocol 2");
        assertThat(toNode.written()).isEmpty();
        assertThat(connections.isConnected(nodeId)).isFalse();
    }

    @Test
    void aSuspendedNodeIsRefusedWithPermissionDeniedRatherThanAVersionComplaint()
            throws Exception {
        willThrow(new HandshakeRefused(HandshakeRefused.Reason.NOT_ACCEPTING_CONTROL,
                "node-a is SUSPENDED; its containers are unaffected."))
                .given(recordHandshake).accept(eq(nodeId), any(NodeHello.class), eq(ADDRESS));

        StreamObserver<NodeMessage> session = open();
        session.onNext(NodeMessage.newBuilder().setHello(hello(NodeProtocol.SUPPORTED)).build());

        assertThat(status(toNode.error()).getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
        assertThat(status(toNode.error()).getDescription()).contains("containers are unaffected");
    }

    @Test
    void aGenerationThisPanelNeverPublishedEndsTheStreamAfterItWasAlreadyRegistered()
            throws Exception {
        given(recordHandshake.catchUp(eq(nodeId), any(NodeHello.class))).willReturn(true);

        StreamObserver<NodeMessage> session = open();
        session.onNext(NodeMessage.newBuilder().setHello(hello(NodeProtocol.SUPPORTED)).build());

        assertThat(status(toNode.error()).getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
        // And the registration is undone, so nothing publishes to a node two panels drive.
        assertThat(connections.isConnected(nodeId)).isFalse();
    }

    @Test
    void everyFrameAfterTheHandshakeReachesThePackageThatOwnsIt() throws Exception {
        StreamObserver<NodeMessage> session = handshaken();

        Heartbeat heartbeat = Heartbeat.newBuilder().setAppliedGeneration(47L).build();
        session.onNext(NodeMessage.newBuilder().setHeartbeat(heartbeat).build());
        SpecApplied applied = SpecApplied.newBuilder().setGeneration(47L).setAccepted(true).build();
        session.onNext(NodeMessage.newBuilder().setSpecApplied(applied).build());
        NodeEvent event = NodeEvent.newBuilder()
                .setKind(NodeEventKind.NODE_EVENT_KIND_DOCKER_UNREACHABLE).build();
        session.onNext(NodeMessage.newBuilder().setEvent(event).build());

        verify(recordHeartbeat).accept(eq(nodeId), eq(heartbeat), any());
        verify(publishNodeSpec).markApplied(nodeId, applied);
        verify(recordNodeEvent).accept(nodeId, event);
    }

    @Test
    void aCommandResultBothCompletesTheWaiterAndIsPersistedByThePackageThatOwnsIt()
            throws Exception {
        StreamObserver<NodeMessage> session = handshaken();

        CompletableFuture<CommandResult> waiting = connections.call(nodeId,
                PanelMessage.newBuilder().setReconcileNow(ReconcileNow.newBuilder()));
        String commandId = toNode.last().getCommandId();
        CommandResult result = CommandResult.newBuilder()
                .setCommandId(commandId).setOk(true).setDetail("reconciled").build();
        session.onNext(NodeMessage.newBuilder().setCommandResult(result).build());

        assertThat(waiting).isCompletedWithValue(result);
        // The future is what a page is waiting on; the dispatch is what makes the outcome
        // survive the operator closing the tab.
        verify(dispatchCommandResult).accept(nodeId, result);
    }

    @Test
    void oneFrameThePanelCannotHandleDoesNotCostTheNodeItsControlChannel() throws Exception {
        StreamObserver<NodeMessage> session = handshaken();
        willThrow(new IllegalStateException("the heartbeat table is on fire"))
                .given(recordHeartbeat).accept(eq(nodeId), any(Heartbeat.class), any());

        session.onNext(NodeMessage.newBuilder().setHeartbeat(Heartbeat.newBuilder()).build());

        assertThat(toNode.error()).isNull();
        assertThat(connections.isConnected(nodeId)).isTrue();
    }

    @Test
    void theStreamEndingReleasesEverythingWaitingOnItAndIsNotAnIncident() throws Exception {
        StreamObserver<NodeMessage> session = handshaken();
        CompletableFuture<CommandResult> waiting = connections.call(nodeId,
                PanelMessage.newBuilder().setReconcileNow(ReconcileNow.newBuilder()));

        session.onCompleted();

        assertThat(connections.isConnected(nodeId)).isFalse();
        assertThat(waiting).isCompletedExceptionally();
        assertThat(waiting.handle((value, failure) -> failure).join())
                .isInstanceOf(NodeOffline.class);
        verify(recordDisconnect).accept(eq(nodeId), anyString());
    }

    @Test
    void aTransportErrorEndsItTheSameWayAndDoesNotWriteAnotherFrame() throws Exception {
        StreamObserver<NodeMessage> session = handshaken();
        int written = toNode.written().size();

        session.onError(Status.UNAVAILABLE.withDescription("tunnel closed")
                .asRuntimeException());

        assertThat(connections.isConnected(nodeId)).isFalse();
        assertThat(toNode.written()).hasSize(written);
        verify(recordDisconnect).accept(eq(nodeId), anyString());
    }

    @Test
    void aSecondHelloOnAnEstablishedStreamIsIgnoredRatherThanRenegotiated() throws Exception {
        StreamObserver<NodeMessage> session = handshaken();
        int written = toNode.written().size();

        session.onNext(NodeMessage.newBuilder().setHello(hello(NodeProtocol.SUPPORTED)).build());

        assertThat(toNode.written()).hasSize(written);
        assertThat(connections.isConnected(nodeId)).isTrue();
        // Once, for the first hello. A stream does not renegotiate halfway through.
        verify(recordHandshake, times(1)).accept(eq(nodeId), any(NodeHello.class), eq(ADDRESS));
    }

    /** A stream that has completed its handshake, with the greeting already written. */
    private StreamObserver<NodeMessage> handshaken() throws Exception {
        StreamObserver<NodeMessage> session = open();
        session.onNext(NodeMessage.newBuilder().setHello(hello(NodeProtocol.SUPPORTED)).build());
        return session;
    }

    /** Opens the stream inside the context the interceptor would have built. */
    private StreamObserver<NodeMessage> open() throws Exception {
        return Context.current()
                .withValue(NodeCallMetadata.CALLER, caller)
                .withValue(NodeCallMetadata.REMOTE_ADDRESS, ADDRESS)
                .call(() -> controlStream.open(toNode));
    }

    private static NodeHello hello(int protocolVersion) {
        return NodeHello.newBuilder()
                .setProtocolVersion(protocolVersion)
                .setAgentVersion("0.1.0")
                .setAppliedGeneration(47L)
                .build();
    }

    private static Status status(Throwable failure) {
        assertThat(failure).isInstanceOf(StatusRuntimeException.class);
        return ((StatusRuntimeException) failure).getStatus();
    }

    private static NodeSettings settings() {
        return new NodeSettings(Duration.ofSeconds(60), Duration.ofSeconds(20),
                Duration.ofMinutes(15), Duration.ofSeconds(15), 15, 15, 20, "",
                Path.of("./var/dist"), "");
    }
}
