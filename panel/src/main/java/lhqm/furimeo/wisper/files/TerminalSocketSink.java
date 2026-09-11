package lhqm.furimeo.wisper.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;

/**
 * One browser reading one shell, over a WebSocket.
 *
 * <h2>Two writers, one socket</h2>
 *
 * <p>PTY output arrives on a thread the gRPC implementation owns and the keep-alive ping
 * on the reaper's thread. A {@code WebSocketSession} is not safe for two threads to write
 * to at once - a partial frame interleaved with another is a protocol error that kills the
 * connection - so every write goes through {@link ConcurrentWebSocketSessionDecorator},
 * which serialises them.
 *
 * <h2>A reader that stops reading is disconnected, not tolerated</h2>
 *
 * <p>That decorator is also the backpressure. A phone that has gone into a tunnel stops
 * acknowledging TCP while {@code yes} keeps printing, and without a bound the panel would
 * hold the difference in heap - per session, for as long as it takes somebody to notice.
 * Past {@link #BUFFER_LIMIT_BYTES} queued or {@link #SEND_TIME_LIMIT_MILLIS} spent on one
 * send, the decorator refuses and this closes the connection. The shell survives it: the
 * customer reconnects and gets the current screen, which is the part they wanted.
 */
final class TerminalSocketSink implements TerminalSink {

    private static final Logger log = LoggerFactory.getLogger(TerminalSocketSink.class);

    /**
     * How much unsent output may queue for one browser.
     *
     * <p>One screen's worth, generously. A terminal is not a log: what a customer wants
     * when their connection recovers is the last screen, and holding more of it than that
     * only delays the moment they see it.
     */
    private static final int BUFFER_LIMIT_BYTES = 256 * 1024;

    /** How long one write may take before the reader is treated as gone. */
    private static final int SEND_TIME_LIMIT_MILLIS = 10_000;

    /** A WebSocket close reason may carry 123 bytes, and a longer one fails the close. */
    private static final int MAX_CLOSE_REASON_BYTES = 123;

    private final ConcurrentWebSocketSessionDecorator socket;

    TerminalSocketSink(WebSocketSession session) {
        this.socket = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MILLIS,
                BUFFER_LIMIT_BYTES);
    }

    @Override
    public boolean output(byte[] data) {
        return write(TerminalSocketFrame.output(data));
    }

    @Override
    public boolean ready(int columns, int rows, String containerId) {
        return write(TerminalSocketFrame.ready(columns, rows, containerId));
    }

    @Override
    public boolean exit(int code, String reason) {
        return write(TerminalSocketFrame.exit(code, reason));
    }

    @Override
    public boolean keepAlive() {
        // A ping rather than an empty frame: the browser answers it in the stack, so an
        // idle terminal costs the page nothing and the tunnel sees traffic either way.
        return write(new PingMessage());
    }

    @Override
    public void close(String reason) {
        close(CloseStatus.NORMAL.withReason(truncate(reason)));
    }

    private boolean write(WebSocketMessage<?> message) {
        if (!socket.isOpen()) {
            return false;
        }
        try {
            socket.sendMessage(message);
            return true;
        } catch (SessionLimitExceededException stalled) {
            // The reader stopped reading. Disconnecting it is what keeps the panel's heap
            // bounded, and the status says which limit was hit rather than "1006".
            log.debug("Terminal socket {} outran its buffer: {}", socket.getId(),
                    stalled.getMessage());
            close(stalled.getStatus());
            return false;
        } catch (IOException | RuntimeException gone) {
            log.debug("Terminal socket {} has gone: {}", socket.getId(), gone.toString());
            return false;
        }
    }

    private void close(CloseStatus status) {
        try {
            socket.close(status);
        } catch (IOException | RuntimeException alreadyGone) {
            // Closing a connection that has already gone is the ordinary case here: this
            // runs from a completion path, and there is nowhere to report it to.
            log.trace("Terminal socket {} was already closed", socket.getId());
        }
    }

    /** A close reason the protocol will accept, cut on a byte boundary rather than a char. */
    private static String truncate(String reason) {
        if (reason == null) {
            return "";
        }
        byte[] utf8 = reason.getBytes(StandardCharsets.UTF_8);
        if (utf8.length <= MAX_CLOSE_REASON_BYTES) {
            return reason;
        }
        return new String(utf8, 0, MAX_CLOSE_REASON_BYTES, StandardCharsets.UTF_8).trim();
    }
}
