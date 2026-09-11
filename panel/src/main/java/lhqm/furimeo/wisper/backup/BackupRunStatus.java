package lhqm.furimeo.wisper.backup;

/**
 * How the last run of a policy went. Mirrors {@code backup_last_status_known}.
 *
 * <p>This is a summary on the policy row so the list screen can show a red or green pill
 * without a correlated subquery over {@code restore_point}. The snapshots themselves are
 * the record of what happened; this is the one-word version of the most recent one.
 */
public enum BackupRunStatus {

    /** A run is in flight. Set before the command leaves the panel. */
    RUNNING,

    SUCCEEDED,

    FAILED;

    /** Whether a new run should be refused because one is already going. */
    public boolean isInFlight() {
        return this == RUNNING;
    }
}
