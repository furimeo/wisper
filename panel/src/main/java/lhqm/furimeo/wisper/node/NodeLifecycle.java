package lhqm.furimeo.wisper.node;

/**
 * What an operator has decided about a node. Mirrors the {@code node_lifecycle_known}
 * CHECK exactly.
 *
 * <p>This is intent, not observation. Whether the node is answering right now is
 * {@link NodeConnectionState} on {@code node_status}, and the two are deliberately
 * different columns on different tables: a node that is {@code ENROLLED} and unreachable
 * is a tunnel problem, while a node that is {@code SUSPENDED} and connected is a decision
 * somebody made. Collapsing them into one status is how a panel ends up unable to say
 * which of the two it is looking at.
 */
public enum NodeLifecycle {

    /** The record exists and no machine has enrolled against it yet. */
    CREATED,

    /** Enrolled, credentialed, and eligible to be given workloads. */
    ENROLLED,

    /** Accepting no new placements and evacuating what can be moved (design §7.7). */
    DRAINING,

    /** Nothing evacuable is left. Safe to delete. */
    DRAINED,

    /**
     * Refused. Usually a duplicate fingerprint, which means two machines are holding one
     * credential (design §7.3). The node's containers keep running - suspension stops the
     * panel talking to it, it does not stop a customer's application.
     */
    SUSPENDED,

    /** Kept for the audit trail and never scheduled again. */
    RETIRED;

    /** Whether the scheduler may put new work here. Also needs {@code schedulable}. */
    public boolean acceptsPlacements() {
        return this == ENROLLED;
    }

    /** Whether the panel will still publish a spec and honour a stream. */
    public boolean acceptsControl() {
        return this == ENROLLED || this == DRAINING || this == DRAINED;
    }

    /** Whether a machine may still enrol against this record. */
    public boolean acceptsEnrolment() {
        return this == CREATED;
    }
}
