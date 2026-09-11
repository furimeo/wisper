package lhqm.furimeo.wisper.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.unit.DataSize;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.org.MemberRole;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.service.ServiceKind;
import lhqm.furimeo.wisper.service.ServiceLocation;

/**
 * A shell over a socket: what the browser types, what it is told, and what happens when it
 * goes away.
 *
 * <p>The three frame types are asserted where they land - on the {@link TerminalSession},
 * which is the panel's end of the gRPC stream to the node - rather than through a method
 * that could be doing anything. A resize that arrives as keystrokes is the predecessor's
 * bug, and it is only visible at this boundary.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TerminalSocketHandlerTest {

    private static final FilesSettings SETTINGS = new FilesSettings(
            DataSize.ofMegabytes(8), Duration.ofHours(24), 200, 1000, DataSize.ofMegabytes(2),
            Duration.ofMinutes(2), Duration.ofMinutes(30), Duration.ofMinutes(15),
            Duration.ofSeconds(20), Duration.ofMinutes(15), Duration.ofHours(4),
            Duration.ofSeconds(20));

    private final UUID serviceId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final String sessionId = UUID.randomUUID().toString();

    private final LiveTerminals live = new LiveTerminals(SETTINGS);
    private final RecordingPty pty = new RecordingPty(sessionId);

    @Mock
    private AuditTrail audit;

    private TerminalSocketHandler handler;
    private LiveTerminals.Held held;
    private FakeSocket socket;

    @BeforeEach
    void openAShell() throws Exception {
        handler = new TerminalSocketHandler(new CloseTerminal(live, audit));
        held = live.reserve(sessionId, serviceId, accountId);
        held.bind(pty);
        socket = connect("socket-1");
    }

    @AfterEach
    void releaseEverything() {
        live.close();
    }

    @Test
    @DisplayName("what the customer types reaches the node as keystrokes")
    void keystrokesReachTheNode() throws Exception {
        handler.handleMessage(socket.session, binary(TerminalSocketFrame.BYTES,
                "ls -la\n".getBytes(StandardCharsets.UTF_8)));

        assertThat(pty.typed).hasSize(1);
        assertThat(new String(pty.typed.get(0), StandardCharsets.UTF_8)).isEqualTo("ls -la\n");
        assertThat(pty.resizes).isEmpty();
    }

    @Test
    @DisplayName("a resize reaches the node as a resize, not as something typed")
    void aResizePropagates() throws Exception {
        handler.handleMessage(socket.session,
                binary(TerminalSocketFrame.SIZE, new byte[] {0, (byte) 132, 0, 43}));

        assertThat(pty.resizes).containsExactly("132x43");
        // The failure this guards against is silent: the PTY keeps working and every
        // full-screen program on it draws over itself.
        assertThat(pty.typed).isEmpty();
    }

    @Test
    @DisplayName("the size the PTY took is sent the moment a browser attaches")
    void attachingAnnouncesTheSize() throws Exception {
        ByteBuffer ready = socket.frame(0);

        assertThat(ready.get()).isEqualTo(TerminalSocketFrame.SIZE);
        assertThat(Short.toUnsignedInt(ready.getShort())).isEqualTo(120);
        assertThat(Short.toUnsignedInt(ready.getShort())).isEqualTo(34);
        assertThat(StandardCharsets.UTF_8.decode(ready).toString()).isEqualTo("container-9");
    }

    @Test
    @DisplayName("output printed before the browser connected is not lost")
    void outputFromBeforeTheSocketArrivesIsFlushed() throws Exception {
        LiveTerminals.Held second = live.reserve("second", serviceId, accountId);
        second.bind(new RecordingPty("second"));
        // A shell prints its prompt the instant the node attaches, which is before the
        // browser has been told the session id, let alone opened a socket.
        second.output("$ ".getBytes(StandardCharsets.UTF_8));

        FakeSocket late = connect("socket-late", second);

        assertThat(late.frame(0).get()).isEqualTo(TerminalSocketFrame.SIZE);
        ByteBuffer prompt = late.frame(1);
        assertThat(prompt.get()).isEqualTo(TerminalSocketFrame.BYTES);
        assertThat(StandardCharsets.UTF_8.decode(prompt).toString()).isEqualTo("$ ");
    }

    @Test
    @DisplayName("PTY output reaches the browser as bytes, not as text")
    void outputReachesTheBrowserUntouched() throws Exception {
        byte[] notUtf8 = {(byte) 0xC3, 0x28, 0x1B, '['};

        held.output(notUtf8);

        ByteBuffer sent = socket.frame(1);
        assertThat(sent.get()).isEqualTo(TerminalSocketFrame.BYTES);
        byte[] rest = new byte[sent.remaining()];
        sent.get(rest);
        assertThat(rest).isEqualTo(notUtf8);
    }

    @Test
    @DisplayName("the exit code reaches the browser instead of the screen just stopping")
    void theExitCodeIsDelivered() throws Exception {
        // What the reaper does a second after the node reports the shell ended.
        pty.finish(130, "");

        live.release(sessionId);

        ByteBuffer exit = socket.lastFrame();
        assertThat(exit.get()).isEqualTo(TerminalSocketFrame.END);
        assertThat(exit.getInt()).isEqualTo(130);
        assertThat(pty.closed).isTrue();
    }

    @Test
    @DisplayName("a session the panel ended says why, rather than reporting a clean zero")
    void aSessionEndedByThePanelCarriesItsReason() throws Exception {
        live.release(sessionId);

        ByteBuffer exit = socket.lastFrame();
        assertThat(exit.get()).isEqualTo(TerminalSocketFrame.END);
        assertThat(exit.getInt()).isZero();
        assertThat(StandardCharsets.UTF_8.decode(exit).toString()).isEqualTo("the panel closed the session");
    }

    @Test
    @DisplayName("the browser asking to exit ends the shell and audits it")
    void theBrowserCanEndTheShell() throws Exception {
        handler.handleMessage(socket.session, binary(TerminalSocketFrame.END, new byte[0]));

        assertThat(live.open()).isZero();
        assertThat(pty.closed).isTrue();
        assertThat(socket.closedWith).isEqualTo(CloseStatus.NORMAL.getCode());
        then(audit).should().record(any(AuditEntry.class));
    }

    @Test
    @DisplayName("a dropped socket releases the session rather than leaving a shell running")
    void aDroppedSocketReleasesTheSession() throws Exception {
        // 1006: no close frame. A closed tab, a phone that lost the network, a tunnel that
        // went away - the common case, and the one that used to leave a process running as
        // the customer's own application until the node's idle timeout.
        handler.afterConnectionClosed(socket.session, CloseStatus.NO_CLOSE_FRAME);

        assertThat(live.open()).isZero();
        assertThat(pty.closed).isTrue();
        then(audit).should().record(any(AuditEntry.class));
    }

    @Test
    @DisplayName("a reconnect keeps the shell when the socket it replaced finally closes")
    void aReconnectSurvivesTheOldSocketsClose() throws Exception {
        // A phone moving from wi-fi to mobile data: the browser is back before the panel
        // has noticed the first connection is dead.
        FakeSocket reconnected = connect("socket-2");

        handler.afterConnectionClosed(socket.session, CloseStatus.NO_CLOSE_FRAME);

        assertThat(live.open()).isOne();
        assertThat(pty.closed).isFalse();
        then(audit).should(never()).record(any(AuditEntry.class));
        // And the shell is the one the new socket is reading.
        held.output("still here".getBytes(StandardCharsets.UTF_8));
        assertThat(reconnected.frames).hasSize(2);
    }

    @Test
    @DisplayName("a frame that is not one closes the socket instead of reaching the PTY")
    void aMalformedFrameClosesTheSocket() throws Exception {
        handler.handleMessage(socket.session, binary((byte) 0x63, new byte[] {1, 2, 3}));

        assertThat(pty.typed).isEmpty();
        assertThat(socket.closedWith).isEqualTo(CloseStatus.BAD_DATA.getCode());
    }

    @Test
    @DisplayName("input larger than a frame may carry never reaches the PTY")
    void anOversizedFrameIsRefused() throws Exception {
        handler.handleMessage(socket.session, binary(TerminalSocketFrame.BYTES,
                new byte[TerminalSocketFrame.MAX_INPUT_BYTES + 1]));

        assertThat(pty.typed).isEmpty();
        assertThat(socket.closedWith).isEqualTo(CloseStatus.BAD_DATA.getCode());
    }

    @Test
    @DisplayName("a socket that beat the node to the shell is not an error the customer sees")
    void typingBeforeTheNodeAttachedIsQuiet() throws Exception {
        // Reserved but not bound: the node has been asked for a PTY and has not dialled
        // back yet. The customer cannot reach this by hand - they are told the session id
        // by the post that waits for the node - but a keystroke arriving here must not
        // take the connection down.
        LiveTerminals.Held racing = live.reserve("racing", serviceId, accountId);
        FakeSocket early = connect("socket-early", racing);

        handler.handleMessage(early.session, binary(TerminalSocketFrame.BYTES,
                "too soon\n".getBytes(StandardCharsets.UTF_8)));

        assertThat(early.closedWith).isZero();
    }

    private FakeSocket connect(String id) throws Exception {
        return connect(id, held);
    }

    private FakeSocket connect(String id, LiveTerminals.Held target) throws Exception {
        FakeSocket fake = new FakeSocket(id, target, access());
        handler.afterConnectionEstablished(fake.session);
        return fake;
    }

    private TerminalAccess access() {
        ServiceLocation service = new ServiceLocation(serviceId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "api", "API", ServiceKind.APP);
        return new TerminalAccess(service,
                new Membership(service.organizationId(), accountId, MemberRole.DEVELOPER),
                AuditActor.account(accountId, "dev@wisper.local", new MockHttpServletRequest()));
    }

    private static BinaryMessage binary(byte tag, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + payload.length);
        buffer.put(tag).put(payload).flip();
        return new BinaryMessage(buffer);
    }

    /** A browser on the other end, recording what it was sent. */
    private static final class FakeSocket {

        private final WebSocketSession session = mock(WebSocketSession.class);
        private final List<ByteBuffer> frames = new ArrayList<>();
        private final Map<String, Object> attributes = new HashMap<>();
        private int closedWith;

        private FakeSocket(String id, LiveTerminals.Held target, TerminalAccess access)
                throws Exception {
            attributes.put(TerminalSocketHandshake.HELD, target);
            attributes.put(TerminalSocketHandshake.ACCESS, access);
            given(session.getId()).willReturn(id);
            given(session.getAttributes()).willReturn(attributes);
            given(session.isOpen()).willAnswer(invocation -> closedWith == 0);
            willAnswer(invocation -> {
                WebSocketMessage<?> message = invocation.getArgument(0);
                if (message instanceof BinaryMessage binary) {
                    frames.add(binary.getPayload().duplicate());
                }
                return null;
            }).given(session).sendMessage(any());
            willAnswer(invocation -> {
                closedWith = ((CloseStatus) invocation.getArgument(0)).getCode();
                return null;
            }).given(session).close(any(CloseStatus.class));
        }

        private ByteBuffer frame(int index) {
            return frames.get(index).duplicate();
        }

        private ByteBuffer lastFrame() {
            return frame(frames.size() - 1);
        }
    }

    /** The panel's end of the gRPC stream to the node, remembering what it was asked to send. */
    private static final class RecordingPty implements TerminalSession {

        private final String sessionId;
        private final List<byte[]> typed = new ArrayList<>();
        private final List<String> resizes = new ArrayList<>();
        private int columns = 120;
        private int rows = 34;
        private boolean finished;
        private int exitCode;
        private String exitReason = "";
        private boolean closed;

        private RecordingPty(String sessionId) {
            this.sessionId = sessionId;
        }

        /** The node reporting that the shell is over, which is what the reaper notices. */
        private void finish(int code, String reason) {
            finished = true;
            exitCode = code;
            exitReason = reason;
        }

        @Override
        public String sessionId() {
            return sessionId;
        }

        @Override
        public String containerId() {
            return "container-9";
        }

        @Override
        public int columns() {
            return columns;
        }

        @Override
        public int rows() {
            return rows;
        }

        @Override
        public void send(byte[] data) {
            typed.add(data);
        }

        @Override
        public void resize(int newColumns, int newRows) {
            columns = newColumns;
            rows = newRows;
            resizes.add(newColumns + "x" + newRows);
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public int exitCode() {
            return exitCode;
        }

        @Override
        public String exitReason() {
            return exitReason;
        }

        @Override
        public void close() {
            closed = true;
            if (!finished) {
                // What the real stream does: a session nobody reported an exit for still
                // has to say why it ended.
                finished = true;
                exitReason = "the panel closed the session";
            }
        }
    }
}
