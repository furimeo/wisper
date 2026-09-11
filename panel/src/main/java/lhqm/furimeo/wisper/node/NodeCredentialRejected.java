package lhqm.furimeo.wisper.node;

/**
 * A gRPC call arrived with a credential the panel will not accept.
 *
 * <p>One exception for all four ways that happens - absent, malformed, unknown, or
 * belonging to a node that is suspended - because the caller must not be able to tell them
 * apart. A daemon that learns "that credential exists but the node is suspended" has been
 * handed a probe for which stolen credentials are worth keeping. {@link #reason()} is for
 * the panel's own log and never reaches the wire.
 */
public class NodeCredentialRejected extends RuntimeException {

    private final Reason reason;

    public NodeCredentialRejected(Reason reason) {
        super(reason.detail());
        this.reason = reason;
    }

    /** Why the panel refused, for the log line on this side of the connection. */
    public Reason reason() {
        return reason;
    }

    /** What the node is told, which is the same sentence in all four cases. */
    public String wireMessage() {
        return "Node credential rejected";
    }

    /** The four refusals, kept apart here and nowhere the caller can see. */
    public enum Reason {

        /** No {@code wisper-node-token} in the metadata. */
        MISSING("No node credential in the call metadata"),

        /** Present but not shaped like anything this panel ever issued. */
        MALFORMED("The presented credential is not shaped like a node credential"),

        /** Well-formed and matching no row, which includes a credential that was rotated. */
        UNKNOWN("No node holds that credential"),

        /**
         * The node exists and is suspended or retired. Its containers keep running; the
         * panel simply stops talking to it (design §7.3).
         */
        NOT_ACCEPTING_CONTROL("That node is suspended or retired");

        private final String detail;

        Reason(String detail) {
            this.detail = detail;
        }

        public String detail() {
            return detail;
        }
    }
}
