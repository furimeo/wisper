package lhqm.furimeo.wisper.backup;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Takes one snapshot out of the list a customer can restore from.
 *
 * <p>The row is marked {@code DELETED} rather than removed. Two reasons: the audit trail
 * has to be able to say that this snapshot existed and who took it away, and a snapshot in
 * flight for a restore must not have its record vanish underneath the run.
 *
 * <p><strong>The archive itself is not deleted by this.</strong> Deleting an object needs
 * the destination's credentials, and those only ever reach a node - there is no
 * delete-object command in {@code proto/wisper/v1/backup.proto}, only the retention rule a
 * node applies at the end of its own runs. So the bytes go when the node next prunes to
 * that rule. The message says so, because a "delete" that quietly leaves the data behind is
 * the kind of half-truth that ends up in a compliance conversation.
 */
@Component
public class DeleteRestorePoint {

    private final RestorePointRepository points;
    private final RestoreRunRepository runs;
    private final AuditTrail audit;

    public DeleteRestorePoint(RestorePointRepository points, RestoreRunRepository runs,
                              AuditTrail audit) {
        this.points = points;
        this.runs = runs;
        this.audit = audit;
    }

    /**
     * @throws NotFoundException if there is no such snapshot in this organization
     * @throws RequestRejected   if a restore is currently reading it
     */
    @Transactional
    public RestorePoint delete(AuditActor actor, Membership membership, UUID restorePointId) {
        membership.requireWrite("restore_point.delete");
        RestorePoint point = points
                .findByIdAndOrganizationId(restorePointId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("restore_point", restorePointId));

        if (point.state() == RestorePointState.RUNNING) {
            throw new RequestRejected(null, "This snapshot is still being taken. Wait for it to "
                    + "finish, then delete it.");
        }
        boolean inUse = runs.findByRestorePointIdOrderByCreatedAtDesc(restorePointId).stream()
                .anyMatch(run -> run.state().isInFlight());
        if (inUse) {
            throw new RequestRejected(null, "A restore is reading this snapshot right now.");
        }

        RestorePoint deleted = points.save(point.deleted());
        audit.record(AuditEntry.succeeded(actor, "restore_point.delete",
                AuditTarget.of("restore_point", point.id(), point.targetLabel()),
                membership.organizationId(),
                "Taken " + point.startedAt() + ", " + point.occupiedBytes() + " bytes"));
        return deleted;
    }
}
