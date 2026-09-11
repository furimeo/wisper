package lhqm.furimeo.wisper.service;

/**
 * What the node does when a cron entry comes due and the previous run has not finished.
 * Mirrors the {@code cron_task_concurrency_policy_known} CHECK.
 *
 * <p>{@link #FORBID} is the default because the common case - a backup script, an
 * importer, a queue drainer - corrupts itself when two copies run at once, and because a
 * job that quietly overlaps is how a node ends up with two hundred copies of the same
 * stuck script.
 */
public enum ConcurrencyPolicy {

    /** Start it anyway. For a job that is genuinely independent of its own last run. */
    ALLOW,

    /** Skip this occurrence. The node reports the skip, so the schedule does not look broken. */
    FORBID,

    /** Kill the one still running and start a fresh one. For a poller whose latest result is the only one that matters. */
    REPLACE;

    /**
     * The wire's view of this. {@code CronEntry} carries a single {@code allow_overlap}
     * boolean, so {@link #REPLACE} and {@link #FORBID} both arrive at the node as "do not
     * overlap" until the contract grows a third state.
     */
    public boolean allowsOverlap() {
        return this == ALLOW;
    }

    /** The sentence in the dropdown. */
    public String label() {
        return switch (this) {
            case ALLOW -> "Run it anyway";
            case FORBID -> "Skip this run";
            case REPLACE -> "Stop the running one and start again";
        };
    }
}
