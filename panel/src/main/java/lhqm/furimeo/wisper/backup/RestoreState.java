package lhqm.furimeo.wisper.backup;

/**
 * How far one restore attempt has got. Mirrors {@code restore_run_state_known}.
 *
 * <p>{@code QUEUED} and {@code RUNNING} are both "in flight" as far as the two partial
 * unique indexes are concerned, and that is what stops two restores interleaving their
 * writes into one volume and producing something that was never a valid snapshot of
 * anything.
 */
public enum RestoreState {

    /** The row and its job were written together; no node has been asked yet. */
    QUEUED,

    /** A node is doing it. */
    RUNNING,

    SUCCEEDED,

    FAILED,

    /** Given up on before a node started, or abandoned because its node went away. */
    CANCELLED;

    /** Whether this run still holds the target against a second attempt. */
    public boolean isInFlight() {
        return this == QUEUED || this == RUNNING;
    }

    /** Whether nothing further will happen to it. */
    public boolean isTerminal() {
        return !isInFlight();
    }
}
