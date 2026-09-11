package lhqm.furimeo.wisper.node;

import java.util.UUID;

/**
 * Who is at the other end of a gRPC call, resolved once per call from the credential in
 * the metadata.
 *
 * <p>Four fields and no more, because this crosses into {@code grpc} on every frame and
 * anything else on it would be a copy of the node row going stale in a stream that stays
 * open for weeks. When a handler needs the row it reads the row.
 *
 * <p>{@code lifecycle} is a string rather than the enum for the same reason it is here at
 * all: {@code grpc} decides whether to keep the stream and needs the value in the audit
 * line it writes, and it should not be able to reason about a lifecycle transition. Use
 * {@link #acceptsControl()} for the one question it may ask.
 *
 * @param nodeId            the authenticated node
 * @param name              its name, for log lines and audit labels
 * @param lifecycle         {@link NodeLifecycle}'s name
 * @param desiredGeneration what the panel had published when the call arrived
 */
public record AuthenticatedNode(UUID nodeId, String name, String lifecycle,
                                long desiredGeneration) {

    /**
     * Whether the panel will still talk to this node.
     *
     * <p>False for a suspended or retired record. Its containers keep running and it keeps
     * reconnecting; what stops is the panel publishing to it, which is the whole content
     * of a suspension (design §7.3).
     */
    public boolean acceptsControl() {
        return NodeLifecycle.valueOf(lifecycle).acceptsControl();
    }
}
