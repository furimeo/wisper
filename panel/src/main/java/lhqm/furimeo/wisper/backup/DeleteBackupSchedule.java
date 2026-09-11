package lhqm.furimeo.wisper.backup;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.jobs.JobQueue;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Removes a backup policy and leaves every snapshot it produced exactly where it is.
 *
 * <p>{@code restore_point.backup_id} is {@code ON DELETE SET NULL} precisely so this is
 * true: the moment somebody tidies up a policy is not the moment their snapshots should
 * disappear, and the snapshots carry {@code target_label} so they still read correctly with
 * nothing to join to. They keep their {@code expires_at}, so the retention sweep goes on
 * ageing them out on the clock they already had.
 *
 * <p>A run already handed to a node is not stopped. There is no cancel-backup command in
 * the contract, the node's own timeout reclaims the slot, and its result lands on a
 * {@code restore_point} row that outlives this policy anyway.
 */
@Component
public class DeleteBackupSchedule {

    private final BackupRepository backups;
    private final JobQueue jobs;
    private final AuditTrail audit;

    public DeleteBackupSchedule(BackupRepository backups, JobQueue jobs, AuditTrail audit) {
        this.backups = backups;
        this.jobs = jobs;
        this.audit = audit;
    }

    /**
     * @param confirmation the policy's name, typed by the customer. Deleting a backup rule
     *                     is the kind of quiet mistake nobody notices until they need it
     * @throws NotFoundException if there is no such policy in this organization
     * @throws RequestRejected   if the confirmation does not match
     */
    @Transactional
    public void delete(AuditActor actor, Membership membership, UUID backupId,
                       String confirmation) {
        membership.requireWrite("backup.delete");
        Backup policy = backups.findByIdAndOrganizationId(backupId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("backup", backupId));

        if (!policy.name().equals(confirmation == null ? null : confirmation.strip())) {
            throw new RequestRejected("confirmation",
                    "Type \"" + policy.name() + "\" to confirm. The snapshots it has already "
                            + "taken are kept either way.");
        }

        // False when a worker already holds it, which is fine: StartBackupRun re-reads the
        // policy and stops when it finds nothing there.
        jobs.cancel(BackupTasks.RUN, backupId.toString());
        backups.delete(policy);

        audit.record(AuditEntry.succeeded(actor, "backup.delete",
                AuditTarget.of("backup", backupId, policy.name()), membership.organizationId(),
                "Policy removed; its snapshots were kept"));
    }
}
