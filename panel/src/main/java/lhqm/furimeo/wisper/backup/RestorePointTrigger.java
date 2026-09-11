package lhqm.furimeo.wisper.backup;

/**
 * Why a snapshot was taken. Mirrors {@code restore_point_trigger_known}.
 *
 * <p>All three age out under the same rule, and that is deliberate: they are archives of
 * the same subject at the same destination, so the node counts them together and the panel
 * has to as well or the two lists diverge. What the value is for is telling a customer
 * <em>why</em> a snapshot exists when they are choosing which one to trust - and
 * {@link #PRE_RESTORE} is the one they want, because it is the state of the data
 * immediately before somebody restored over it.
 */
public enum RestorePointTrigger {

    /** The policy's cron fired. */
    SCHEDULED,

    /** Somebody pressed "back up now". */
    MANUAL,

    /** Taken automatically before an in-place restore overwrote the target. */
    PRE_RESTORE;

    /** What a screen calls it. */
    public String label() {
        return switch (this) {
            case SCHEDULED -> "Scheduled";
            case MANUAL -> "Manual";
            case PRE_RESTORE -> "Before restore";
        };
    }
}
