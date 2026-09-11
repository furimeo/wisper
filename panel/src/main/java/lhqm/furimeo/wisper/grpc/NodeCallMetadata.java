package lhqm.furimeo.wisper.grpc;

import io.grpc.Context;
import io.grpc.Metadata;
import lhqm.furimeo.wisper.node.AuthenticatedNode;

/**
 * The three metadata headers every node call carries, and the two values the interceptor
 * puts on the call's {@link Context} once it has read them.
 *
 * <p>{@code node.proto} documents the header names as the only place authentication is
 * written down, and this is the Java half of that sentence. They are declared once, here,
 * so a typo in a header name is a compile error on one side rather than a node that
 * authenticates against a panel that never looks.
 *
 * <p>The remote address is on the context as well because half a dozen places need it -
 * the enrolment row, the clone detector, every audit entry a node produces - and gRPC
 * exposes it through an attribute lookup that is easy to get subtly wrong.
 */
public final class NodeCallMetadata {

    /** The node id from {@code EnrollResponse}, echoed on every call. */
    public static final Metadata.Key<String> NODE_ID =
            Metadata.Key.of("wisper-node-id", Metadata.ASCII_STRING_MARSHALLER);

    /** The long-lived credential. Never logged, on either side. */
    public static final Metadata.Key<String> NODE_TOKEN =
            Metadata.Key.of("wisper-node-token", Metadata.ASCII_STRING_MARSHALLER);

    /**
     * The protocol version in decimal, so the panel can refuse an incompatible node before
     * it has parsed a frame.
     */
    public static final Metadata.Key<String> PROTOCOL =
            Metadata.Key.of("wisper-protocol", Metadata.ASCII_STRING_MARSHALLER);

    /** Who the call belongs to, resolved once by the interceptor. */
    public static final Context.Key<AuthenticatedNode> CALLER = Context.key("wisper-node");

    /** Where the call came from, as gRPC saw it. */
    public static final Context.Key<String> REMOTE_ADDRESS = Context.key("wisper-remote-address");

    private NodeCallMetadata() {
    }

    /**
     * The authenticated node for the call being handled.
     *
     * @throws IllegalStateException if there is none, which can only happen if a method
     *                               was added to the service without the interceptor
     *                               knowing whether it needs a credential. Failing loudly
     *                               is the point: the alternative is a handler that
     *                               silently serves an unauthenticated caller.
     */
    public static AuthenticatedNode caller() {
        AuthenticatedNode node = CALLER.get();
        if (node == null) {
            throw new IllegalStateException("This call has no authenticated node on its "
                    + "context. AuthenticateNodeCall lists the methods that skip "
                    + "authentication; this one is not on the list and did not get any.");
        }
        return node;
    }

    /** Where the call came from, or {@code "unknown"} for a transport that does not say. */
    public static String remoteAddress() {
        String address = REMOTE_ADDRESS.get();
        return address == null ? "unknown" : address;
    }
}
