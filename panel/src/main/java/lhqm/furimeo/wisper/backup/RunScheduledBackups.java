package lhqm.furimeo.wisper.backup;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lhqm.furimeo.wisper.jobs.JobQueue;

/**
 * The body of the {@code backup-schedule} sweep: find the policies whose cron has come
 * round and queue a run for each.
 *
 * <p>Advancing {@code next_run_at} and enqueuing the job happen in <strong>one
 * transaction</strong>. That is what makes the sweep safe to run on two panels at once and
 * safe to interrupt: either a policy's clock moved and its job exists, or neither did.
 * Without it, a panel that died between the two would either run the same backup on every
 * sweep for ever, or skip the run entirely.
 *
 * <p>One transaction <em>per policy</em>, not one for the batch. A policy with a schedule
 * somebody edited by hand into nonsense must not stop the other forty from running, and a
 * batch-wide rollback would do exactly that.
 *
 * <p>{@code enqueueIfAbsent} rather than {@code enqueue}: a policy whose previous run is
 * still queued should keep the run it already has. Two backups of one volume racing each
 * other is how a node ends up doing nothing but backups, and the schedule catching up by
 * skipping is the correct behaviour after a panel has been down.
 */
@Component
public class RunScheduledBackups {

    private static final Logger log = LoggerFactory.getLogger(RunScheduledBackups.class);

    private final TransactionTemplate transactions;
    private final BackupRepository backups;
    private final ScheduleNextRun schedules;
    private final JobQueue jobs;
    private final BackupSettings settings;

    public RunScheduledBackups(PlatformTransactionManager transactionManager,
                               BackupRepository backups, ScheduleNextRun schedules,
                               JobQueue jobs, BackupSettings settings) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.backups = backups;
        this.schedules = schedules;
        this.jobs = jobs;
        this.settings = settings;
    }

    /** @return how many runs were queued */
    public int sweep() {
        Instant now = Instant.now();
        List<Backup> due = backups.findDue(now, settings.sweepBatch());
        int queued = 0;
        for (Backup policy : due) {
            if (Boolean.TRUE.equals(transactions.execute(status -> claim(policy, now)))) {
                queued++;
            }
        }
        return queued;
    }

    /**
     * Moves one policy's clock forward and queues its run.
     *
     * <p>Re-reads the row inside the transaction. The list was assembled outside it, and
     * between the two somebody may have disabled the policy, deleted it, or another panel
     * may have claimed it - all three of which are ordinary and none of which is worth a
     * log line.
     */
    private boolean claim(Backup listed, Instant now) {
        Backup policy = backups.findById(listed.id()).orElse(null);
        if (policy == null || !policy.isScheduled() || policy.nextRunAt() == null
                || policy.nextRunAt().isAfter(now)) {
            return false;
        }
        // From now, not from the due time. A panel that was down for a week would otherwise
        // walk the clock forward one nightly run at a time, one sweep each, and would take
        // a week to catch up with itself.
        Instant nextRunAt = schedules.after(policy, now).orElse(null);
        backups.save(policy.dueAt(nextRunAt));
        if (nextRunAt == null) {
            log.warn("Backup policy {} has a schedule that never comes round; it will not run "
                    + "again until it is edited", policy.id());
        }
        return jobs.enqueueIfAbsent(BackupTasks.RUN, policy.id().toString(),
                BackupJob.scheduled(policy.id()), now);
    }
}
