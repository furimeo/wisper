package lhqm.furimeo.wisper.files;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

/**
 * The browser's half of a shell: {@code /services/{serviceId}/terminal/socket}.
 *
 * <p>The panel's half of the terminal is unchanged - a gRPC bidirectional stream to the
 * node, which is the only thing that ever spoke to the PTY. What changed is this side.
 * Output used to be Server-Sent Events and input one {@code POST} per keystroke, each of
 * them re-running the security filter chain and re-reading the account row; a socket is
 * authorised once, at the handshake, and afterwards a keystroke is seven bytes on a
 * connection that is already open.
 *
 * <p>Everything crossing it is a {@link TerminalSocketFrame}: bytes, size, or end. There
 * is deliberately no unframed write - that absence is the fix for what broke the
 * predecessor's terminal, where a resize was indistinguishable from keystrokes and an exit
 * was a screen that simply stopped (design §11.5).
 *
 * <p>The socket does not open a shell. {@code POST /services/{id}/terminal} does, because
 * opening one is a state change that belongs behind CSRF and in the audit trail; this
 * attaches to the shell that post minted, and the handshake has already established that
 * it belongs to the caller.
 */
@Component
public class TerminalSocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TerminalSocketHandler.class);

    /** The sink this connection reads through, so its close can tell whether it still owns it. */
    private static final String SINK = TerminalSocketHandler.class.getName() + ".sink";

    private final CloseTerminal closeTerminal;

    public TerminalSocketHandler(CloseTerminal closeTerminal) {
        this.closeTerminal = closeTerminal;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) {
        // Set before the first frame can arrive. Past this the container refuses the
        // message and closes the connection, which is the ceiling a client cannot talk
        // its way around - the check in the codec is for a frame that fits and still lies.
        socket.setBinaryMessageSizeLimit(TerminalSocketFrame.MAX_MESSAGE_BYTES);
        // A shell speaks binary. A client sending text is a client this handler does not
        // understand, and a small limit means it cannot spend memory finding that out.
        socket.setTextMessageSizeLimit(TerminalSocketFrame.MAX_MESSAGE_BYTES);

        TerminalSocketSink sink = new TerminalSocketSink(socket);
        socket.getAttributes().put(SINK, sink);
        held(socket).attach(sink);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession socket, BinaryMessage message) {
        LiveTerminals.Held held = held(socket);
        TerminalSocketFrame frame;
        try {
            frame = TerminalSocketFrame.decode(message.getPayload());
        } catch (IllegalArgumentException malformed) {
            // A client sending frames this side does not understand is one whose next
            // frame cannot be trusted either, and a PTY is not where to find out.
            log.debug("Terminal socket {} sent a frame that is not one: {}", socket.getId(),
                    malformed.getMessage());
            close(socket, CloseStatus.BAD_DATA.withReason("that is not a terminal frame"));
            return;
        }
        try {
            switch (frame) {
                case TerminalSocketFrame.Keystrokes typed -> held.session().send(typed.data());
                case TerminalSocketFrame.Resize resized ->
                        held.session().resize(resized.columns(), resized.rows());
                case TerminalSocketFrame.Exit() -> end(socket, held);
            }
        } catch (TerminalUnavailable gone) {
            // The shell ended between this frame being sent and being read. The reaper is
            // already on its way with the exit code; closing here would race it.
            log.debug("Terminal socket {} wrote into a shell that has ended", socket.getId());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession socket, Throwable failure) {
        log.debug("Terminal socket {} failed: {}", socket.getId(), failure.toString());
        close(socket, CloseStatus.SERVER_ERROR);
    }

    /**
     * The connection went away, so the shell goes with it.
     *
     * <p>A PTY nobody is reading is a process running as the customer's own application,
     * and the node's idle timeout is fifteen minutes away. Ending it here is what makes a
     * closed tab - the common case, and one the browser reports by dropping the socket -
     * cost nothing.
     *
     * <p>Unless a newer connection has taken the shell over. A phone that moves from
     * wi-fi to mobile data leaves a socket the panel has not noticed is dead; the browser
     * reconnects, {@code attach} replaces the sink, and when the old connection's close
     * finally arrives it must not take the shell somebody is now typing into.
     */
    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        Object sink = socket.getAttributes().get(SINK);
        LiveTerminals.Held held = (LiveTerminals.Held) socket.getAttributes()
                .get(TerminalSocketHandshake.HELD);
        if (held == null || !(sink instanceof TerminalSink attached)
                || !held.isAttachedTo(attached)) {
            return;
        }
        log.debug("Terminal socket {} closed with {}; ending session {}", socket.getId(), status,
                held.sessionId());
        closeTerminal.close(access(socket), held.sessionId());
    }

    /** The customer pressed the button. Ends the PTY, then the connection carrying it. */
    private void end(WebSocketSession socket, LiveTerminals.Held held) {
        closeTerminal.close(access(socket), held.sessionId());
        close(socket, CloseStatus.NORMAL.withReason("the shell ended"));
    }

    private static LiveTerminals.Held held(WebSocketSession socket) {
        // Put there by the handshake, which refused the upgrade if it could not find one.
        return (LiveTerminals.Held) socket.getAttributes().get(TerminalSocketHandshake.HELD);
    }

    private static TerminalAccess access(WebSocketSession socket) {
        return (TerminalAccess) socket.getAttributes().get(TerminalSocketHandshake.ACCESS);
    }

    private static void close(WebSocketSession socket, CloseStatus status) {
        try {
            socket.close(status);
        } catch (IOException | RuntimeException alreadyGone) {
            log.trace("Terminal socket {} was already closed", socket.getId());
        }
    }
}
