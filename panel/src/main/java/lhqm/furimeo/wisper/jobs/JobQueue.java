package lhqm.furimeo.wisper.jobs;

import java.time.Instant;
import java.util.UUID;

/**
 * Enqueues background work on the same PostgreSQL that holds the row the work is about.
 *
 * <p>This is the whole reason there is no Redis in this stack. A deployment row and the
 * job that builds it are written in one transaction: either both exist or neither does.
 * A queue in another system cannot make that promise, and the failure it produces - a
 * job for a row that was rolled back, or a row with no job - is unrecoverable without a
 * person reading two databases side by side.
 *
 * <p>Every method here therefore <strong>requires an active transaction</strong> and
 * throws if there is none. That is the one behaviour worth an interface rather than a
 * direct call to db-scheduler's {@code SchedulerClient}: "enqueue inside the transaction
 * that justifies the job" is a rule, and a rule nothing checks is a rule that decays.
 * The second reason is containment: {@code com.github.kagkarlsson} is imported in this
 * package and nowhere else, so the queue technology is named once.
 *
 * <p>Implemented by {@code lhqm.furimeo.wisper.jobs.DbSchedulerJobQueue}.
 *
 * <h2>Instance ids</h2>
 *
 * <p>{@code scheduled_tasks} is keyed by {@code (task_name, task_instance)}, so the
 * instance id is what makes an enqueue idempotent. Use the id of the row the job acts
 * on - the deployment id, the backup id - and a retry of the same HTTP request cannot
 * produce two builds. Use {@link #uniqueInstanceId()} only for work that genuinely has
 * no subject, such as a sweep.
 */
public interface JobQueue {

    /**
     * Runs the job as soon as a worker is free.
     *
     * <p>With {@code db-scheduler.immediate-execution-enabled} that is normally within
     * milliseconds of the transaction committing, and never before it commits.
     *
     * @throws JobAlreadyQueued  if {@code (kind, instanceId)} is already in the table
     * @throws IllegalStateException if there is no transaction in progress
     */
    <T> void enqueue(JobKind<T> kind, String instanceId, T payload);

    /**
     * Runs the job at a chosen time.
     *
     * <p>For a retry with backoff, a snapshot due at 03:00, a certificate renewal a
     * fortnight before expiry. A time in the past is a time now, not an error.
     *
     * @throws JobAlreadyQueued  if {@code (kind, instanceId)} is already in the table
     * @throws IllegalStateException if there is no transaction in progress
     */
    <T> void enqueueAt(JobKind<T> kind, String instanceId, T payload, Instant when);

    /**
     * Enqueues only if nothing is queued under {@code (kind, instanceId)} already.
     *
     * <p>The idempotent form, for the caller that would rather keep the existing
     * schedule than fail: a poll that finds a backup due, a webhook a Git provider
     * delivered twice. Returns false when a job was already there - which is a normal
     * outcome and not worth logging as a problem.
     *
     * @throws IllegalStateException if there is no transaction in progress
     */
    <T> boolean enqueueIfAbsent(JobKind<T> kind, String instanceId, T payload, Instant when);

    /**
     * Removes a job that has not been picked up yet.
     *
     * <p>Returns false if it was not there, or if a worker already holds it - a running
     * job is stopped by cancelling the work it is doing, not by deleting its row, and
     * pretending otherwise leaves a half-finished deployment nobody is watching.
     *
     * @throws IllegalStateException if there is no transaction in progress
     */
    boolean cancel(JobKind<?> kind, String instanceId);

    /**
     * An instance id for work with no subject row.
     *
     * <p>A sweep or a rollup has nothing to be idempotent against, and reusing a fixed
     * id would mean the second one of the day is refused as a duplicate. Random, so two
     * panels running the same sweep enqueue two rows and db-scheduler's
     * {@code SKIP LOCKED} picks one of them.
     */
    default String uniqueInstanceId() {
        return UUID.randomUUID().toString();
    }
}
