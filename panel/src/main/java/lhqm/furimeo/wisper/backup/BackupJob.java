package lhqm.furimeo.wisper.backup;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

/**
 * The payload of the {@code backup-run} job: take a snapshot under this policy.
 *
 * <p>The policy id and why the run was asked for - nothing else. The row holds the target,
 * the destination and the retention, and a payload carrying copies of them would age in
 * {@code scheduled_tasks} while somebody edits the policy, so a run queued on Monday would
 * push Monday's credentials at a bucket that moved on Tuesday.
 *
 * <p>The trigger has to travel with the job because it cannot be recovered from the row.
 * By the time a worker picks this up the sweep has already advanced {@code next_run_at}, so
 * a scheduled run and a run somebody asked for by hand look identical on the policy -
 * and {@code restore_point.trigger} is a column a customer reads when deciding which
 * snapshot to trust.
 *
 * <p>{@link Serializable} because db-scheduler writes {@code task_data} as bytes and, with
 * no {@code Serializer} bean configured, that is Java serialization. A record serialises
 * through its canonical constructor, so the fields are rebuilt by the same validation that
 * created them.
 *
 * @param trigger never {@link RestorePointTrigger#PRE_RESTORE}: a safety snapshot is taken
 *                inline by {@link RunRestore}, because the restore must not start until it
 *                exists, and a queued job cannot promise that ordering
 */
public record BackupJob(UUID backupId, RestorePointTrigger trigger) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public BackupJob {
        if (backupId == null) {
            throw new IllegalArgumentException("A backup job needs a policy id");
        }
        if (trigger == null || trigger == RestorePointTrigger.PRE_RESTORE) {
            throw new IllegalArgumentException(
                    "A queued backup is SCHEDULED or MANUAL, not " + trigger);
        }
    }

    /** The sweep found this policy due. */
    public static BackupJob scheduled(UUID backupId) {
        return new BackupJob(backupId, RestorePointTrigger.SCHEDULED);
    }

    /** Somebody pressed the button. */
    public static BackupJob manual(UUID backupId) {
        return new BackupJob(backupId, RestorePointTrigger.MANUAL);
    }

    /**
     * The db-scheduler instance id.
     *
     * <p>The policy id, which is what makes both callers idempotent: a poll that finds the
     * same policy due twice - two panels, or one that restarted mid-sweep - enqueues one
     * row, and a customer pressing the button while a run is already queued is told so
     * rather than getting two.
     */
    public String instanceId() {
        return backupId.toString();
    }
}
