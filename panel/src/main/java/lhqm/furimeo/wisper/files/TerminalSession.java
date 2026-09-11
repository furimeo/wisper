package lhqm.furimeo.wisper.files;

/**
 * One open PTY inside a customer's container, held by the panel on behalf of a browser.
 *
 * <p>The predecessor pumped a PTY through a socket with an unframed copy, so a resize
 * was indistinguishable from keystrokes and the exit code was lost - the terminal simply
 * stopped. Every method here is one of the frame types in {@code terminal.proto}, and
 * there is deliberately no "write whatever" escape hatch, because that is the thing that
 * went wrong (design §11.5).
 *
 * <p>Obtained from {@link NodeTerminals#open}. Closing it sends the node the end of the
 * stream, which kills the PTY; the browser going away is what triggers that, so it is
 * safe to call from a servlet completion callback and safe to call twice.
 */
public interface TerminalSession extends AutoCloseable {

    /** The id the panel minted, carried on every frame and in the audit entry. */
    String sessionId();

    /** The container the PTY is in, as the node reported it when it attached. */
    String containerId();

    /** The size the PTY actually took, which is the requested size after clamping. */
    int columns();

    /** The size the PTY actually took, which is the requested size after clamping. */
    int rows();

    /**
     * Sends keystrokes to the PTY.
     *
     * <p>Bytes, never a string. What arrives from the browser is whatever the customer
     * typed, which is routinely an escape sequence and occasionally not valid UTF-8;
     * decoding it here and re-encoding it there is how a paste of binary content breaks.
     */
    void send(byte[] data);

    /**
     * Tells the PTY the window changed.
     *
     * <p>On a phone this fires every time the on-screen keyboard opens, so it has to be
     * cheap and it has to be its own frame - a curses application that never hears about
     * a resize draws over itself and the customer sees a broken screen.
     */
    void resize(int columns, int rows);

    /**
     * Whether the node has ended the session.
     *
     * <p>True after a {@code TerminalExit} frame: the shell exited, the idle timeout
     * fired, or the workload was stopped underneath it. The reason is in
     * {@link #exitReason()} and the code in {@link #exitCode()}.
     */
    boolean isFinished();

    /**
     * The process's exit status, or {@code 128 + signal} when it was killed - so 130
     * reads as Ctrl-C to anyone who has used a shell. Meaningless until
     * {@link #isFinished()}.
     */
    int exitCode();

    /**
     * Why the platform ended it, when it was not the process that ended: the idle
     * timeout, the maximum duration, the container going away. Empty when the process
     * simply exited.
     */
    String exitReason();

    /**
     * Ends the session and releases the stream.
     *
     * <p>No checked exception: this is called from a {@code finally} and from an SSE
     * completion callback, and neither has anywhere to put one. Idempotent.
     */
    @Override
    void close();
}
