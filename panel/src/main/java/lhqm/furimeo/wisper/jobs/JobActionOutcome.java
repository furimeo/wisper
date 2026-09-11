package lhqm.furimeo.wisper.jobs;

/**
 * What happened when an operator pressed a button on a failing job.
 *
 * <p>Three answers rather than a boolean, because the three need different sentences on
 * the screen. "It is running now, wait for it to finish" and "it is no longer there,
 * somebody else dealt with it" are both "not done", and telling an operator the wrong one
 * sends them to press the button again.
 */
public enum JobActionOutcome {

    /** The row was rescheduled or removed. */
    DONE,

    /**
     * A worker holds it. A running job is stopped by cancelling the work it is doing, not
     * by deleting its row - the row is bookkeeping, the work is a thread on a node
     * somewhere - so neither button applies until it finishes or its heartbeat expires.
     */
    RUNNING,

    /** No such row. It succeeded, or another operator discarded it, between page and click. */
    GONE
}
