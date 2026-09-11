package lhqm.furimeo.wisper.node;

/**
 * The control-plane protocol version this panel speaks, and the one question anybody asks
 * about it.
 *
 * <p>It is the counterpart of {@code internal/version.Protocol} on the node side. The two
 * are bumped together, in the same commit, and only for a change an older peer cannot
 * interpret - adding a field to a message is not one, because protobuf already handles
 * that (codegen.md).
 *
 * <p>There is no negotiating down. A partly-understood control channel is worse than none:
 * the panel would be publishing specs a node reads differently, and both sides would look
 * healthy while diverging. So a mismatch is refused twice over - at {@code Enroll}, before
 * the machine holds a credential at all, and at every {@code Connect} - and the node's
 * page says it needs upgrading (design §7.5).
 *
 * <p><strong>Where this constant lives.</strong> codegen.md says the {@code grpc} package
 * holds it. It is here instead because {@code EnrolNode} has to make the same decision and
 * {@code node} must not depend on {@code grpc} (panel-ports.md §6); {@code grpc} depends on
 * {@code node} and reads it from here, so it is still written exactly once.
 */
public final class NodeProtocol {

    /**
     * Version 1: everything in {@code proto/wisper/v1}.
     *
     * <p>Bump this and {@code internal/version.Protocol} in the same commit, or half the
     * fleet stops being able to connect and the other half has not been upgraded yet.
     */
    public static final int SUPPORTED = 1;

    private NodeProtocol() {
    }

    /** Whether the panel will talk to an agent announcing this version. */
    public static boolean canSpeak(int agentProtocolVersion) {
        return agentProtocolVersion == SUPPORTED;
    }

    /**
     * The sentence shown on the node's page and returned to the agent.
     *
     * <p>It names both numbers and says which way to move, because "protocol mismatch" on
     * its own has sent more than one operator to upgrade the wrong end.
     */
    public static String mismatchMessage(int agentProtocolVersion) {
        if (agentProtocolVersion < SUPPORTED) {
            return "This node speaks protocol " + agentProtocolVersion + " and the panel speaks "
                    + SUPPORTED + ". The node needs upgrading.";
        }
        return "This node speaks protocol " + agentProtocolVersion + " and the panel speaks "
                + SUPPORTED + ". The panel is older than the node; upgrade the panel.";
    }
}
