package lhqm.furimeo.wisper.backup;

/**
 * The life of one snapshot. Mirrors {@code restore_point_state_known}.
 *
 * <p>{@link #EXPIRED} and {@link #DELETED} are separate on purpose. Expired is the
 * panel's decision that retention no longer keeps this snapshot; deleted is somebody
 * pressing a button. Both stop it being restorable and only one of them is an accident
 * worth being able to look up afterwards.
 */
public enum RestorePointState {

    /** The node has been asked and has not answered yet. */
    RUNNING,

    /** It exists at the destination and can be restored from. */
    AVAILABLE,

    /** The run did not produce anything. {@code error_message} says what happened. */
    FAILED,

    /** Retention no longer keeps it; the node removes the object on its next prune. */
    EXPIRED,

    /** Somebody deleted it deliberately. */
    DELETED;

    /** Whether a restore may be started from a snapshot in this state. */
    public boolean isRestorable() {
        return this == AVAILABLE;
    }

    /** Whether the snapshot still occupies space that counts against a quota. */
    public boolean occupiesSpace() {
        return this == AVAILABLE;
    }

    /** Whether nothing more will happen to it without somebody asking. */
    public boolean isTerminal() {
        return this != RUNNING;
    }
}
