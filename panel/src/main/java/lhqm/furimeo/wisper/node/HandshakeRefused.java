package lhqm.furimeo.wisper.node;

/**
 * The panel will not keep this control stream.
 *
 * <p>Thrown from {@link RecordHandshake} while the first frame is being read, so the
 * stream ends before a single command has been dispatched down it. {@code grpc} turns it
 * into a status the daemon can act on, and the daemon's reconnect loop keeps trying -
 * which is right for all three reasons, because all three are things an operator can fix
 * without touching the machine's backoff.
 */
public class HandshakeRefused extends RuntimeException {

    private final Reason reason;

    public HandshakeRefused(Reason reason, String detail) {
        super(detail);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /** Why the stream was refused. */
    public enum Reason {

        /**
         * The agent speaks a protocol version this panel does not. The version it
         * announced is recorded, so the node's page says "needs upgrading" with both
         * numbers on it rather than showing a machine that simply never connects (design
         * §7.5).
         */
        PROTOCOL_UNSUPPORTED,

        /**
         * The node is suspended or retired. Its containers keep running and it keeps
         * dialling; the panel has stopped being an author of its state.
         */
        NOT_ACCEPTING_CONTROL,

        /**
         * A second stream for this node arrived from a different address while the first
         * was still open, which one machine cannot do. Both this stream and the node are
         * stopped (design §7.3).
         */
        DUPLICATE_STREAM
    }
}
