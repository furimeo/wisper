package lhqm.furimeo.wisper.backup;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.jobs.JobAlreadyQueued;
import lhqm.furimeo.wisper.jobs.JobQueue;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The "back up now" button.
 *
 * <p>All it does is enqueue, and that is the point: the work happens in
 * {@link StartBackupRun} on a worker thread, so a customer on a phone gets an answer in
 * milliseconds rather than holding a request open for the minutes a snapshot takes.
 *
 * <p>The enqueue is in the same transaction as the audit entry, which is the whole reason
 * the queue is PostgreSQL: a job for an action nobody recorded, or a recorded action with
 * no job, cannot happen.
 *
 * <p>Pressing it twice is refused rather than merged. {@code scheduled_tasks} is keyed by
 * {@code (task_name, task_instance)} and the instance is the policy id, so the second press
 * finds a row already there - and a customer who did that deliberately deserves to be told
 * a backup is already going, not to have their click quietly discarded.
 */
@Component
public class RunBackupNow {

    private final BackupRepository backups;
    private final JobQueue jobs;
    private final AuditTrail audit;

    public RunBackupNow(BackupRepository backups, JobQueue jobs, AuditTrail audit) {
        this.backups = backups;
        this.jobs = jobs;
        this.audit = audit;
    }

    /**
     * @throws NotFoundException if there is no such policy in this organization
     * @throws RequestRejected   if a run under this policy is already queued
     */
    @Transactional
    public Backup run(AuditActor actor, Membership membership, UUID backupId) {
        membership.requireWrite("backup.run");
        Backup policy = backups.findByIdAndOrganizationId(backupId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("backup", backupId));

        if (!policy.enabled()) {
            throw new RequestRejected(null, "\"" + policy.name() + "\" is switched off. Turn it "
                    + "back on before running it.");
        }

        try {
            jobs.enqueue(BackupTasks.RUN, backupId.toString(), BackupJob.manual(backupId));
        } catch (JobAlreadyQueued going) {
            throw new RequestRejected(null, "A backup under \"" + policy.name()
                    + "\" is already waiting to run.");
        }

        audit.record(AuditEntry.succeeded(actor, "backup.run",
                AuditTarget.of("backup", policy.id(), policy.name()),
                membership.organizationId(), "Requested by hand"));
        return policy;
    }
}
