package lhqm.furimeo.wisper.jobs;

/**
 * The state of the whole queue in four numbers.
 *
 * <p>The admin screen needs this, not only the failure list. A page that shows an empty
 * table when everything is working is indistinguishable from a page that is broken, and
 * the operator's first question on arriving is "is anything queued at all" - which a list
 * filtered to failures cannot answer.
 *
 * @param queued  every row in {@code scheduled_tasks}, including the recurring tasks,
 *                which always have one row each waiting for their next tick
 * @param due     rows whose execution time has passed and which no worker holds. A number
 *                that stays high means the workers cannot keep up or are not running
 * @param running rows a worker has picked
 * @param failing rows with a current failure streak
 */
public record JobQueueSummary(long queued, long due, long running, long failing) {

    /** Whether anything needs an operator's attention. */
    public boolean isHealthy() {
        return failing == 0;
    }
}
