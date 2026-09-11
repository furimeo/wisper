package lhqm.furimeo.wisper.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.google.protobuf.ByteString;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lhqm.furimeo.wisper.files.TerminalSession;
import lhqm.furimeo.wisper.files.TerminalUnavailable;
import lhqm.furimeo.wisper.node.NodeConnections;
import lhqm.furimeo.wisper.node.NodeOffline;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;
import lhqm.furimeo.wisper.proto.v1.StartTerminal;
import lhqm.furimeo.wisper.proto.v1.TerminalAttached;
import lhqm.furimeo.wisper.proto.v1.TerminalExit;
import lhqm.furimeo.wisper.proto.v1.TerminalFrame;

/**
 * The two-step open, and the two frame types the predecessor lost.
 *
 * <p>{@code Resize} and {@code Exit} are here because they are exactly what an unframed
 * copy destroys: a resize becomes keystrokes, and an exit becomes a terminal that simply
 * stops (design §11.5). Both are asserted on the wire rather than through a method that
 * could be doing anything.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NodeTerminalSessionsTest {

    private static final Duration ATTACH_TIMEOUT = Duration.ofMillis(200);

    private final UUID nodeId = UUID.randomUUID();
    private final RecordingFrames toPanelSide = new RecordingFrames();
    private final List<byte[]> toBrowser = new ArrayList<>();

    @Mock
    private NodeConnections connections;

    private NodeTerminalSessions sessions;

    /** The node's side of the terminal call, once it has dialled back. */
    private StreamObserver<TerminalFrame> fromNode;

    /** The session the node acknowledged, so a frame can be addressed to it. */
    private String attachedSessionId = "";

    @Test
    void openingPutsStartTerminalOnTheControlStreamAndWaitsForTheNodeToDialBack() {
        TerminalSession session = openWithNodeAttaching(nodeId, 100, 40);

        assertThat(session.containerId()).isEqualTo("container-9");
        // The size the PTY actually took, which is what the browser has to be told.
        assertThat(session.columns()).isEqualTo(100);
        assertThat(session.rows()).isEqualTo(40);
        assertThat(sessions.openSessions()).isEqualTo(1);
    }

    @Test
    void outputFromThePtyReachesTheBrowserAsBytesAndNotAsText() {
        openWithNodeAttaching(nodeId, 80, 24);

        byte[] notUtf8 = {(byte) 0xC3, (byte) 0x28, 0x1B, '['};
        fromNode.onNext(frame().setData(ByteString.copyFrom(notUtf8)).build());

        assertThat(toBrowser).hasSize(1);
        assertThat(toBrowser.get(0)).isEqualTo(notUtf8);
    }

    @Test
    void aResizeIsItsOwnFrameAndNotSomethingTypedIntoTheShell() {
        TerminalSession session = openWithNodeAttaching(nodeId, 80, 24);

        session.resize(132, 43);

        TerminalFrame sent = toPanelSide.last();
        assertThat(sent.getPayloadCase()).isEqualTo(TerminalFrame.PayloadCase.RESIZE);
        assertThat(sent.getResize().getCols()).isEqualTo(132);
        assertThat(sent.getResize().getRows()).isEqualTo(43);
        assertThat(sent.getSessionId()).isEqualTo(session.sessionId());
        assertThat(session.columns()).isEqualTo(132);
    }

    @Test
    void keystrokesAreDataFramesCarryingTheSessionId() {
        TerminalSession session = openWithNodeAttaching(nodeId, 80, 24);

        session.send("ls -la\n".getBytes(StandardCharsets.UTF_8));

        TerminalFrame sent = toPanelSide.last();
        assertThat(sent.getPayloadCase()).isEqualTo(TerminalFrame.PayloadCase.DATA);
        assertThat(sent.getData().toStringUtf8()).isEqualTo("ls -la\n");
        assertThat(sent.getSessionId()).isEqualTo(session.sessionId());
    }

    @Test
    void anExitCarriesTheCodeAndTheReasonInsteadOfTheStreamJustStopping() {
        TerminalSession session = openWithNodeAttaching(nodeId, 80, 24);

        fromNode.onNext(frame().setExit(TerminalExit.newBuilder()
                .setCode(130).setReason("")).build());

        assertThat(session.isFinished()).isTrue();
        // 130 is Ctrl-C to anybody who has used a shell, which is the whole point of
        // reporting the code rather than inferring "it stopped".
        assertThat(session.exitCode()).isEqualTo(130);
        assertThat(session.exitReason()).isEmpty();
        assertThat(sessions.openSessions()).isZero();
    }

    @Test
    void aSessionEndedByThePlatformSaysSoRatherThanLookingLikeACleanExit() {
        TerminalSession session = openWithNodeAttaching(nodeId, 80, 24);

        fromNode.onNext(frame().setExit(TerminalExit.newBuilder()
                .setCode(0).setReason("idle timeout")).build());

        assertThat(session.exitReason()).isEqualTo("idle timeout");
    }

    @Test
    void aNodeThatNeverAttachesIsAFailureWithAReasonAndLeavesNothingRegistered() {
        sessions = new NodeTerminalSessions(connections);

        assertThatExceptionOfType(TerminalUnavailable.class)
                .isThrownBy(() -> sessions.open(nodeId, request(UUID.randomUUID().toString()),
                        toBrowser::add, ATTACH_TIMEOUT))
                .withMessageContaining("did not attach");
        assertThat(sessions.openSessions()).isZero();
    }

    @Test
    void aNodeWithNoControlStreamIsOfflineRatherThanATerminalProblem() {
        sessions = new NodeTerminalSessions(connections);
        willThrow(new NodeOffline(nodeId)).given(connections)
                .send(eq(nodeId), any(PanelMessage.Builder.class));

        assertThatExceptionOfType(NodeOffline.class)
                .isThrownBy(() -> sessions.open(nodeId, request(UUID.randomUUID().toString()),
                        toBrowser::add, ATTACH_TIMEOUT));
        assertThat(sessions.openSessions()).isZero();
    }

    @Test
    void aNodeCannotAttachToAnotherNodesSession() {
        UUID otherNode = UUID.randomUUID();
        sessions = new NodeTerminalSessions(connections);
        String sessionId = UUID.randomUUID().toString();
        RecordingFrames hijacker = new RecordingFrames();
        willAnswer(invocation -> {
            sessions.attach(otherNode, hijacker).onNext(attached(sessionId, 80, 24));
            return null;
        }).given(connections).send(eq(nodeId), any(PanelMessage.Builder.class));

        assertThatExceptionOfType(TerminalUnavailable.class)
                .isThrownBy(() -> sessions.open(nodeId, request(sessionId), toBrowser::add,
                        ATTACH_TIMEOUT));
        assertThat(statusOf(hijacker.error()).getCode())
                .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    void attachingToASessionNobodyIsWaitingOnIsRefusedRatherThanRemembered() {
        sessions = new NodeTerminalSessions(connections);
        RecordingFrames late = new RecordingFrames();

        sessions.attach(nodeId, late).onNext(attached("a-session-that-timed-out", 80, 24));

        assertThat(statusOf(late.error()).getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(sessions.openSessions()).isZero();
    }

    @Test
    void theFirstFrameHasToBeTheAcknowledgement() {
        sessions = new NodeTerminalSessions(connections);
        RecordingFrames impatient = new RecordingFrames();

        sessions.attach(nodeId, impatient).onNext(TerminalFrame.newBuilder()
                .setSessionId("whatever").setData(ByteString.copyFromUtf8("hello")).build());

        assertThat(statusOf(impatient.error()).getCode())
                .isEqualTo(Status.Code.FAILED_PRECONDITION);
    }

    @Test
    void aFrameForAnotherSessionIsDroppedRatherThanTypedIntoThisShell() {
        openWithNodeAttaching(nodeId, 80, 24);

        fromNode.onNext(TerminalFrame.newBuilder()
                .setSessionId("somebody-elses-session")
                .setData(ByteString.copyFromUtf8("rm -rf /\n"))
                .build());

        assertThat(toBrowser).isEmpty();
    }

    @Test
    void closingEndsTheStreamOnceAndIsSafeToCallFromAFinally() {
        TerminalSession session = openWithNodeAttaching(nodeId, 80, 24);

        session.close();
        session.close();

        assertThat(toPanelSide.completions()).isEqualTo(1);
        assertThat(session.isFinished()).isTrue();
        assertThat(sessions.openSessions()).isZero();
    }

    @Test
    void theStreamDyingUnderneathASessionEndsItRatherThanLeavingItOpenForever() {
        TerminalSession session = openWithNodeAttaching(nodeId, 80, 24);

        fromNode.onError(Status.UNAVAILABLE.withDescription("tunnel closed")
                .asRuntimeException());

        assertThat(session.isFinished()).isTrue();
        assertThat(session.exitReason()).contains("UNAVAILABLE");
        assertThat(sessions.openSessions()).isZero();
    }

    /** Opens a session with the node dialling straight back, as a healthy node does. */
    private TerminalSession openWithNodeAttaching(UUID owner, int cols, int rows) {
        sessions = new NodeTerminalSessions(connections);
        String sessionId = UUID.randomUUID().toString();
        willAnswer(invocation -> {
            PanelMessage.Builder command = invocation.getArgument(1);
            assertThat(command.build().getStartTerminal().getSessionId()).isEqualTo(sessionId);
            fromNode = sessions.attach(owner, toPanelSide);
            fromNode.onNext(attached(sessionId, cols, rows));
            return null;
        }).given(connections).send(eq(owner), any(PanelMessage.Builder.class));

        return sessions.open(owner, request(sessionId), toBrowser::add, ATTACH_TIMEOUT);
    }

    /** A frame from the node for the session that is currently attached. */
    private TerminalFrame.Builder frame() {
        return TerminalFrame.newBuilder().setSessionId(attachedSessionId);
    }

    private TerminalFrame attached(String sessionId, int cols, int rows) {
        attachedSessionId = sessionId;
        return TerminalFrame.newBuilder()
                .setSessionId(sessionId)
                .setAttached(TerminalAttached.newBuilder()
                        .setWorkloadId("workload-1")
                        .setContainerId("container-9")
                        .setCols(cols)
                        .setRows(rows))
                .build();
    }

    private static StartTerminal request(String sessionId) {
        return StartTerminal.newBuilder()
                .setSessionId(sessionId)
                .setWorkloadId("workload-1")
                .addCommand("/bin/sh")
                .setInitialCols(80)
                .setInitialRows(24)
                .setIdleTimeoutSeconds(900)
                .setMaxDurationSeconds(14400)
                .build();
    }

    private static Status statusOf(Throwable failure) {
        assertThat(failure).isInstanceOf(StatusRuntimeException.class);
        return ((StatusRuntimeException) failure).getStatus();
    }

    /** What the node's side of a terminal call received. */
    private static final class RecordingFrames implements StreamObserver<TerminalFrame> {

        private final List<TerminalFrame> written = new ArrayList<>();
        private Throwable error;
        private int completions;

        List<TerminalFrame> written() {
            return List.copyOf(written);
        }

        TerminalFrame last() {
            return written.get(written.size() - 1);
        }

        Throwable error() {
            return error;
        }

        int completions() {
            return completions;
        }

        @Override
        public void onNext(TerminalFrame frame) {
            written.add(frame);
        }

        @Override
        public void onError(Throwable failure) {
            error = failure;
        }

        @Override
        public void onCompleted() {
            completions++;
        }
    }
}
