package lhqm.furimeo.wisper.files;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

import lhqm.furimeo.wisper.proto.v1.StartTerminal;

/**
 * Opens an interactive shell inside a customer's container.
 *
 * <p>Implemented by {@code lhqm.furimeo.wisper.grpc.NodeTerminalSessions}. Declared in
 * {@code files} because {@code files} owns {@code /services/{id}/terminal/**} - the same
 * agent, the same authorization surface, and the same volume the shell will be standing
 * in (see {@code docs/contracts/panel-http.md}).
 *
 * <h2>Why opening one takes two steps and this method hides it</h2>
 *
 * <p>The panel cannot dial a node. It puts a {@code StartTerminal} command on the
 * control stream, and the node answers by opening a separate bidirectional
 * {@code Terminal} call whose first frame is {@code TerminalAttached}. The
 * implementation does both and joins them by session id, so a caller sees one blocking
 * open that either produces a live session or fails.
 *
 * <p>One stream per session, never multiplexed. Two sessions sharing one HTTP/2 flow
 * control window means a customer running {@code yes} in one tab stalls the other, and
 * gRPC already gives a stream per call for nothing.
 */
public interface NodeTerminals {

    /**
     * Opens a PTY and waits until the node has attached to it.
     *
     * <p>{@code request.session_id} is minted by the caller before the browser connects,
     * so the SSE or WebSocket connection, this stream and the audit entry all carry the
     * same id. The caller is also responsible for filling in the timeouts: an idle
     * terminal is a shell running as the customer's application for as long as a tab
     * stays open, so {@code idle_timeout_seconds} and {@code max_duration_seconds} are
     * not optional.
     *
     * @param fromNode receives every byte the PTY produced, in order, on a thread the
     *                 implementation owns. Write it to the browser and return; a sink
     *                 that blocks stalls the PTY, which is what backpressure through
     *                 this stream is meant to do, but a sink that blocks forever is a
     *                 leaked session.
     * @param attachTimeout how long to wait for the node to dial back before giving up.
     *                 A node that is busy pulling an image can take seconds; a node
     *                 whose stream has just dropped will never answer at all.
     * @throws TerminalUnavailable if the node did not attach in time, or refused
     * @throws lhqm.furimeo.wisper.node.NodeOffline if the node has no control stream
     */
    TerminalSession open(UUID nodeId, StartTerminal request, Consumer<byte[]> fromNode,
                         Duration attachTimeout);
}
