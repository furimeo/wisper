package lhqm.furimeo.wisper.backup;

/**
 * What a restore does to the thing it restores into. Mirrors
 * {@code restore_run_mode_known}.
 *
 * <p>Both exist because of design §8.3: a backup nobody has restored is not a backup.
 * {@link #VERIFY} is the way to find that out without betting a customer's live data on
 * the answer, and it is the mode the "test this snapshot" button uses.
 */
public enum RestoreMode {

    /** Overwrite the live target. Always takes a {@code PRE_RESTORE} snapshot first. */
    IN_PLACE,

    /**
     * Restore alongside and throw the result away.
     *
     * <p>On the wire this is {@code RestoreBackup.dry_run}: the node restores a volume
     * into a sibling directory and a database into a suffixed name, reports what it
     * found, and removes it. Nothing the customer is using is touched, so no safety
     * snapshot is needed and the workload keeps running.
     */
    VERIFY;

    /** Whether the live target is written to, which is what makes the rest necessary. */
    public boolean overwritesTheTarget() {
        return this == IN_PLACE;
    }

    /** What a screen calls it. */
    public String label() {
        return this == IN_PLACE ? "Restore in place" : "Verify only";
    }
}
