package lhqm.furimeo.wisper.files;

/**
 * Where a held shell writes what it has to say to the browser.
 *
 * <p>{@link LiveTerminals} holds the sessions and knows nothing about how one is being
 * read. That separation is not decoration: the output arrives on a thread the gRPC
 * implementation owns, the keep-alive on the reaper's thread and the attach on a
 * container thread, and the rules about which of those may block belong to the transport
 * rather than to the registry that owns the map.
 *
 * <p>Every write answers whether the reader is still there instead of throwing. A browser
 * that has gone is the normal end of a terminal, not an exceptional one, and the caller's
 * response to it - buffer the bytes, detach, release the session - is the same for every
 * kind of failure the socket can produce.
 *
 * <p>Implemented by {@link TerminalSocketSink} over a WebSocket, which is the only
 * transport the panel offers for a shell. Logs and metrics are one-way and stay on
 * Server-Sent Events; a terminal is not one-way, and paying a round trip through the
 * whole security filter chain for every keystroke was what this replaced.
 */
public interface TerminalSink {

    /**
     * PTY output.
     *
     * <p>May block while the socket drains, which is the backpressure that stops a shell
     * printing faster than a phone on 4G can read. It may not block forever: the
     * implementation bounds both the wait and the bytes it will hold.
     *
     * @return false when the reader has gone, in which case the caller keeps the bytes
     */
    boolean output(byte[] data);

    /** The size the PTY took and the container it is in, sent as soon as a browser attaches. */
    boolean ready(int columns, int rows, String containerId);

    /** The shell is over. Sent before the connection is closed, so the reason survives. */
    boolean exit(int code, String reason);

    /**
     * Keeps an idle connection open.
     *
     * <p>A tunnel closes a connection that has said nothing for a minute, and a shell
     * somebody is reading rather than typing into says nothing for hours.
     *
     * @return false when the reader has gone
     */
    boolean keepAlive();

    /**
     * Closes the connection without ending the shell.
     *
     * @param reason a short sentence the client can show. WebSocket allows 123 bytes for
     *               one; the implementation truncates rather than failing the close
     */
    void close(String reason);
}
