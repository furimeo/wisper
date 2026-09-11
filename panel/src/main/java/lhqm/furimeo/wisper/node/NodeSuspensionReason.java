package lhqm.furimeo.wisper.node;

/**
 * Why a node was suspended. Mirrors the {@code node_suspension_reason_known} CHECK.
 *
 * <p>A fixed vocabulary rather than free text because two of these are alarms an operator
 * has to be able to filter for. A cloned virtual machine and a node somebody paused for
 * maintenance produce the same lifecycle and need completely different attention.
 */
public enum NodeSuspensionReason {

    /**
     * Two writers were found for one node, and the panel cannot tell which is legitimate.
     *
     * <p>It covers both shapes that takes, because the value is fixed by
     * {@code node_suspension_reason_known} and both are the same problem seen from
     * different ends:
     *
     * <ul>
     * <li>the same machine fingerprint arriving from a second address, which is a cloned
     *     virtual machine - two machines holding one credential (design §7.3);</li>
     * <li>an {@code applied_generation} higher than anything this panel published, which
     *     is two panels publishing to one machine (panel-ports.md §3).</li>
     * </ul>
     *
     * <p>Everything involved is suspended, never just one side: picking a winner would be
     * a coin toss whose losing side is a customer's data. Containers keep running.
     */
    DUPLICATE_FINGERPRINT,

    /**
     * The node speaks a protocol version this panel does not. The stream is refused and
     * the node's page says it needs upgrading rather than letting two versions
     * misunderstand each other (design §7.5).
     */
    PROTOCOL_MISMATCH,

    /** Somebody decided. The reason a person typed is in {@code suspension_reason}. */
    OPERATOR,

    /**
     * The credential was withdrawn - a leak, a decommissioning that was not a drain. The
     * node keeps running its containers and cannot be talked to.
     */
    CREDENTIAL_REVOKED;

    /** One sentence for the node's page, so a suspended node is never a mystery. */
    public String explanation() {
        return switch (this) {
            case DUPLICATE_FINGERPRINT -> "Two writers were found for this node - either two "
                    + "machines holding one credential, or two panels publishing to it. "
                    + "Everything involved was suspended; workloads are still running.";
            case PROTOCOL_MISMATCH -> "This node speaks a protocol this panel does not. "
                    + "Upgrade sasayaki on the machine.";
            case OPERATOR -> "Suspended by an operator.";
            case CREDENTIAL_REVOKED -> "The node credential was revoked. Re-enrol the machine "
                    + "with a fresh bootstrap token.";
        };
    }
}
