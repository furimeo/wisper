package lhqm.furimeo.wisper.backup;

import java.util.Locale;
import java.util.Optional;
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
 * The restore button (design §8.3): one press, one row, one job.
 *
 * <p>Everything that can be refused is refused here, before anything is written, so the
 * customer gets a sentence instead of a run that fails on a node ninety seconds later. What
 * gets past this method is a restore that has a snapshot to read, a target to write and a
 * node holding that target.
 *
 * <h2>The target is resolved now, not read off the snapshot</h2>
 *
 * <p>{@code restore_point.node_id} records where the snapshot was <em>taken</em>. A service
 * can be migrated between the backup and the restore, and following the snapshot's own node
 * would put a customer's data on a machine that no longer runs their application - or on
 * one that has since been retired. {@link ResolveBackupTarget} answers the only question
 * that matters at this moment: where does this volume live today.
 *
 * <h2>Confirmation, on a phone</h2>
 *
 * <p>An in-place restore overwrites live data and most customers are holding a telephone.
 * It therefore needs {@code confirmed}, which the page sets from a dialog. That is still one
 * button; it is not a typed phrase, because the safety here comes from the
 * {@code PRE_RESTORE} snapshot {@link RunRestore} takes, not from making somebody spell
 * something.
 */
@Component
public class RestoreFromPoint {

    private final RestorePointRepository points;
    private final RestoreRunRepository runs;
    private final ResolveBackupTarget targets;
    private final JobQueue jobs;
    private final AuditTrail audit;

    public RestoreFromPoint(RestorePointRepository points, RestoreRunRepository runs,
                            ResolveBackupTarget targets, JobQueue jobs, AuditTrail audit) {
        this.points = points;
        this.runs = runs;
        this.targets = targets;
        this.jobs = jobs;
        this.audit = audit;
    }

    /**
     * @param mode      {@link RestoreMode#IN_PLACE} overwrites; {@link RestoreMode#VERIFY}
     *                  restores alongside and throws the result away, which is how a
     *                  snapshot is proven restorable without betting anything on it
     * @param confirmed required for an in-place restore and ignored for a verify
     * @throws NotFoundException if there is no such snapshot in this organization
     * @throws RequestRejected   with a sentence naming what is in the way
     */
    @Transactional
    public RestoreRun start(AuditActor actor, Membership membership, UUID restorePointId,
                            RestoreMode mode, boolean confirmed) {
        membership.requireWrite("backup.restore");
        UUID organizationId = membership.organizationId();
        RestorePoint point = points.findByIdAndOrganizationId(restorePointId, organizationId)
                .orElseThrow(() -> NotFoundException.of("restore_point", restorePointId));

        if (!point.isRestorable()) {
            throw new RequestRejected(null, "This snapshot is " + point.state()
                    + " and there is nothing to restore from it.");
        }
        if (mode.overwritesTheTarget() && !confirmed) {
            throw new RequestRejected(null, "Restoring in place replaces what is there now. "
                    + "Confirm to go ahead - a snapshot of the current data is taken first.");
        }

        BackupTargetRef target = targets.forSnapshot(point)
                .filter(candidate -> organizationId.equals(candidate.organizationId()))
                .orElseThrow(() -> new RequestRejected(null, "The " + point.targetKind().label()
                        .toLowerCase(Locale.ROOT) + " this snapshot was taken from no longer "
                        + "exists. Create it again and restore into the new one."));

        // A node is needed for either mode - even a verify runs on the machine that would
        // receive the data. Readiness is only insisted on for an overwrite: restoring over
        // a directory a node has reported as MISSING is how a customer loses the copy they
        // still had.
        if (!target.isPlaced()) {
            throw new RequestRejected(null, "Nothing is running " + target.label()
                    + " at the moment, so there is no node to restore on.");
        }
        if (mode.overwritesTheTarget() && !target.ready()) {
            throw new RequestRejected(null, target.label() + " is not in a state that can be "
                    + "restored over. Verify the snapshot instead, or wait for the node to "
                    + "report it healthy.");
        }

        Optional<RestoreRun> running = alreadyRunning(target);
        if (running.isPresent()) {
            throw new RequestRejected(null, "A restore into " + target.label() + " is already "
                    + running.get().state().name().toLowerCase(Locale.ROOT)
                    + ". Wait for it to finish.");
        }

        UUID runId = UUID.randomUUID();
        RestoreRun run = runs.save(RestoreRun.queued(runId, point.id(), membership.accountId(),
                target.volumeId(), target.managedDatabaseId(), mode,
                mode.label() + " of the snapshot taken at " + point.startedAt()
                        + " into " + target.label() + "."));
        try {
            jobs.enqueue(BackupTasks.RESTORE, runId.toString(), new RestoreJob(runId));
        } catch (JobAlreadyQueued impossible) {
            // The id is fresh, so this cannot happen without a UUID collision. Refusing is
            // still better than proceeding with a run nothing will pick up.
            throw new RequestRejected(null, "That restore could not be queued. Try again.");
        }

        audit.record(AuditEntry.succeeded(actor, "backup.restore",
                AuditTarget.of("restore_point", point.id(), point.targetLabel()),
                organizationId,
                mode.label() + " into " + target.label() + " on node " + target.nodeId()
                        + ", run " + runId));
        return run;
    }

    /**
     * The in-flight run holding this target, if any.
     *
     * <p>Asked before inserting so the answer is a sentence rather than the violation of
     * {@code restore_run_one_active_per_volume_idx}. The index is still what guarantees it:
     * two requests arriving together both pass this check and the second insert loses.
     */
    private Optional<RestoreRun> alreadyRunning(BackupTargetRef target) {
        return target.targetKind() == BackupTargetKind.VOLUME
                ? runs.findActiveForVolume(target.subjectId())
                : runs.findActiveForDatabase(target.subjectId());
    }
}
