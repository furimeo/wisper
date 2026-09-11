package lhqm.furimeo.wisper.node;

/**
 * Whether a control stream is open right now. Mirrors the
 * {@code node_status_connection_state_known} CHECK.
 *
 * <p>Observation, written only from a gRPC handler. {@link NodeLifecycle} is the intent
 * half and lives on the other table.
 */
public enum NodeConnectionState {

    /**
     * No stream. Not an incident: the node keeps running everything it was given, and a
     * tunnel dropping a long-lived stream is a daily event (design §5.2).
     */
    DISCONNECTED,

    /** The stream is open and the handshake matched. */
    CONNECTED,

    /**
     * The stream is open and the node says it cannot do its job - Docker is not
     * answering, the disk is nearly full. Nothing has been destroyed because of it and
     * nothing should be (AGENTS.md §4.5).
     */
    DEGRADED
}
