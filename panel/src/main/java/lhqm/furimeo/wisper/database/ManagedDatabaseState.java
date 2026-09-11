package lhqm.furimeo.wisper.database;

/**
 * Where one customer database is in its life. Mirrors
 * {@code managed_database_state_known}, value for value.
 *
 * <p>This is the panel's own state machine, not a copy of anything a node says. The node
 * reports size and existence; what those observations mean - suspended, failed, gone - is
 * a decision the panel makes and records here, which is why a status report can never
 * write this column directly (schema.md §2).
 */
public enum ManagedDatabaseState {

    /** The row exists and the node has not confirmed the database and role yet. */
    PENDING,

    /** Created, reachable, and the connection string works. */
    READY,

    /**
     * Over its storage quota.
     *
     * <p>Neither engine has a hard per-database limit, so nothing is physically blocked
     * by this: it is the panel saying so on the screen, loudly, with the two ways out -
     * delete data, or raise the quota (see {@link SetDatabaseQuota}). Dropping a
     * customer's data because it grew is not one of the options.
     */
    SUSPENDED,

    /** A provision or a rotation did not work; {@code last_error} says what happened. */
    FAILED,

    /**
     * On its way out.
     *
     * <p>A grant in this state is left out of the node's spec, which is how the node
     * comes to remove it: omission is deletion. The row survives until the node has
     * answered, so a drop that fails is visible rather than silent.
     */
    DELETING;

    /** Whether a customer may connect to it right now, as far as the panel knows. */
    public boolean isUsable() {
        return this == READY || this == SUSPENDED;
    }

    /** Whether the platform still owes this row work. */
    public boolean isInFlight() {
        return this == PENDING || this == DELETING;
    }
}
