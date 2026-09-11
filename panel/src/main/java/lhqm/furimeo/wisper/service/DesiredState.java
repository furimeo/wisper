package lhqm.furimeo.wisper.service;

/**
 * What the customer has asked for. Mirrors the {@code service_desired_state_known} CHECK
 * and {@code DesiredState} in {@code workload.proto}.
 *
 * <p>Intent, never fact. This column is written by a customer pressing a button and by
 * nothing else - no status report touches it (schema.md §2), which is what keeps
 * "stopped, because I stopped it" and "stopped, because it crashed" two different things.
 * The second lives on {@code placement.reported_state}, where the node writes it.
 *
 * <p>There is no {@code DELETED}. Removing a workload is done by leaving it out of the
 * spec, and removing a service is done by deleting the row.
 */
public enum DesiredState {

    /** Keep it up. For a site, keep serving it. */
    RUNNING,

    /**
     * Stop it, and keep everything it had.
     *
     * <p>The container, its volumes and its logs stay; this is a pause button, not a
     * delete button, and a customer who stops a service to save money expects their disk
     * to still be there next month.
     */
    STOPPED;

    public boolean isRunning() {
        return this == RUNNING;
    }

    /** The word a status pill shows. */
    public String label() {
        return this == RUNNING ? "Running" : "Stopped";
    }
}
