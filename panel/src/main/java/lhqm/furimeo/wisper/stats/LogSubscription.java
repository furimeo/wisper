package lhqm.furimeo.wisper.stats;

/**
 * One live log feed from a node, held open on behalf of whoever is watching it.
 *
 * <p>Obtained from {@link NodeLogs#follow}. Closing it sends {@code StopLogStream} to
 * the node, which stops it tailing a container nobody is reading - bandwidth spent on
 * nothing, on a link that is a tunnel.
 *
 * <p>Closing is what the browser going away has to trigger. Register it on the
 * {@code SseEmitter}'s completion, timeout and error callbacks: all three fire for a
 * customer who closed a tab on a train, and only one of them looks like success.
 */
public interface LogSubscription extends AutoCloseable {

    /** The id the panel minted; every chunk from the node carries it back. */
    String streamId();

    /**
     * Whether the source has ended on its own.
     *
     * <p>True after a chunk marked {@code end}: the container exited, the build
     * finished, the cron run returned. The subscription is over and the panel can
     * release it without sending a stop.
     */
    boolean isFinished();

    /**
     * How many bytes the node dropped rather than block the process writing them.
     *
     * <p>Non-zero means the customer is missing output, and they have to be told: a
     * customer told that lines are missing can act, a customer shown a silent gap
     * cannot (design §7.6).
     */
    long droppedBytes();

    /** Stops the feed. Idempotent, and never throws - it is called from a callback. */
    @Override
    void close();
}
