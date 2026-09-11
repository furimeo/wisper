package lhqm.furimeo.wisper.jobs;

import java.time.Instant;

/**
 * One row of {@code scheduled_tasks} that has failed at least once, as the admin screen
 * shows it.
 *
 * <p>Deliberately does not carry {@code task_data}. The payload is a serialised Java
 * object whose class lives in another package, it routinely contains an id and nothing
 * else, and deserialising every row to render a list is both slow and a way to make the
 * admin screen fail because one payload class was renamed. The task name and instance id
 * are what an operator needs, and the instance id <em>is</em> the id of the row the job
 * acts on (panel-ports.md §2.3).
 *
 * @param taskName            the db-scheduler task, {@code <domain>-<verb>}
 * @param instanceId          the id of the row the job acts on
 * @param executionTime       when it will next be attempted. db-scheduler's failure
 *                            handler pushes this forward, so a job that keeps failing has
 *                            a time further and further out - which is why "retry now" is
 *                            a button and not a suggestion to wait
 * @param consecutiveFailures how many attempts in a row have failed. Reset to zero by a
 *                            success, so this is the length of the current streak and not
 *                            a lifetime count
 * @param lastFailure         when the most recent attempt failed
 * @param lastSuccess         when it last worked, or null if it never has. The difference
 *                            between "broken since the last deploy" and "never worked"
 * @param picked              whether a worker is holding it right now. A picked job cannot
 *                            be retried or discarded: the row is not the work
 * @param pickedBy            which scheduler instance holds it
 * @param lastHeartbeat       when that worker last said it was alive. A picked job with a
 *                            stale heartbeat is one whose panel died mid-execution, and
 *                            db-scheduler's own dead-execution detection will free it
 */
public record FailedJob(
        String taskName,
        String instanceId,
        Instant executionTime,
        int consecutiveFailures,
        Instant lastFailure,
        Instant lastSuccess,
        boolean picked,
        String pickedBy,
        Instant lastHeartbeat) {

    /** The package that owns the work, from the task name's prefix. */
    public String domain() {
        int dash = taskName.indexOf('-');
        return dash < 0 ? taskName : taskName.substring(0, dash);
    }

    /** Whether either button on the row should be offered. */
    public boolean isActionable() {
        return !picked;
    }

    /** Whether this has ever worked, which changes what an operator should look at first. */
    public boolean hasEverSucceeded() {
        return lastSuccess != null;
    }
}
