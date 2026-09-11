package lhqm.furimeo.wisper.backup;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;

import lhqm.furimeo.wisper.jobs.JobKind;

/**
 * The four background jobs {@code backup} owns, and the names they answer to.
 *
 * <p>The {@link JobKind} constants sit next to the {@code Task} beans that implement them,
 * so each name is written exactly twice and both writings are in this file
 * (panel-ports.md §2.3). <strong>A task name is permanent.</strong> Renaming one orphans
 * every row already queued under the old name.
 *
 * <p>Two shapes, for two different reasons:
 *
 * <ul>
 * <li>{@code backup-run} and {@code restore-run} are one-time and keyed by the row they act
 *     on, which is what makes a customer pressing a button twice produce one run.</li>
 * <li>{@code backup-schedule} and {@code backup-prune} are recurring and have no subject
 *     row, so there is nothing for them to be idempotent against - exactly the distinction
 *     {@link lhqm.furimeo.wisper.jobs.JobQueue} exists to keep.</li>
 * </ul>
 *
 * <p>Neither handler throws for a backup that failed: the use-cases mark the row and
 * return. An exception escaping here means the panel could not reach its own database, and
 * db-scheduler's retry is the right answer to that and to nothing else.
 */
@Configuration(proxyBeanMethods = false)
public class BackupTasks {

    private static final Logger log = LoggerFactory.getLogger(BackupTasks.class);

    /** Take a snapshot under one policy. Enqueued by the button and by the sweep. */
    public static final JobKind<BackupJob> RUN = new JobKind<>("backup-run", BackupJob.class);

    /** Find the policies whose cron has come round. */
    public static final JobKind<Void> SCHEDULE = new JobKind<>("backup-schedule", Void.class);

    /** Apply retention, and clean up after runs nobody heard the end of. */
    public static final JobKind<Void> PRUNE = new JobKind<>("backup-prune", Void.class);

    /** Put one snapshot back. Enqueued by the restore button. */
    public static final JobKind<RestoreJob> RESTORE =
            new JobKind<>("restore-run", RestoreJob.class);

    /**
     * A minute. Cron resolution is one minute, so sweeping faster finds nothing new and
     * sweeping slower makes a 03:00 backup start at some point in the next quarter of an
     * hour, which is not what the customer typed.
     */
    private static final Duration EVERY_MINUTE = Duration.ofMinutes(1);

    /**
     * An hour. Retention decides what may be deleted, not when anything is deleted - the
     * node removes the objects on its next run - so there is nothing to gain from running
     * it more often than a snapshot can plausibly appear.
     */
    private static final Duration EVERY_HOUR = Duration.ofHours(1);

    @Bean
    Task<BackupJob> backupRunTask(StartBackupRun startBackupRun) {
        return Tasks.oneTime(RUN.taskName(), BackupJob.class)
                .execute((instance, context) -> startBackupRun.run(instance.getData()));
    }

    @Bean
    Task<RestoreJob> restoreRunTask(RunRestore runRestore) {
        return Tasks.oneTime(RESTORE.taskName(), RestoreJob.class)
                .execute((instance, context) -> runRestore.run(instance.getData()));
    }

    @Bean
    RecurringTask<Void> backupScheduleTask(RunScheduledBackups scheduledBackups) {
        return Tasks.recurring(SCHEDULE.taskName(), Schedules.fixedDelay(EVERY_MINUTE))
                .execute((instance, context) -> {
                    int started = scheduledBackups.sweep();
                    if (started > 0) {
                        log.info("Backup sweep: {} scheduled backup(s) queued", started);
                    }
                });
    }

    @Bean
    RecurringTask<Void> backupPruneTask(PruneByRetention pruning,
                                        FailAbandonedRuns abandoned) {
        return Tasks.recurring(PRUNE.taskName(), Schedules.fixedDelay(EVERY_HOUR))
                .execute((instance, context) -> {
                    int expired = pruning.sweep();
                    int lost = abandoned.sweep();
                    if (expired > 0 || lost > 0) {
                        log.info("Backup prune: {} snapshot(s) expired, {} abandoned run(s) "
                                + "closed", expired, lost);
                    }
                });
    }
}
