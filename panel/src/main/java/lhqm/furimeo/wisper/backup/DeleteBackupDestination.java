package lhqm.furimeo.wisper.backup;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Removes a destination, and refuses when anything still depends on it.
 *
 * <p>Both {@code backup.destination_id} and {@code restore_point.destination_id} are
 * {@code ON DELETE RESTRICT}. Asking first turns a foreign-key violation into a sentence
 * saying how many policies and how many snapshots are in the way - which is the information
 * somebody needs to decide what to do next, and exactly what the constraint's own message
 * does not contain.
 *
 * <p>Disabling is offered instead, and is what most people actually want: it takes the
 * destination out of the picker and stops its policies running, while leaving every
 * snapshot behind it restorable.
 */
@Component
public class DeleteBackupDestination {

    private final BackupDestinationRepository destinations;
    private final BackupRepository backups;
    private final RestorePointRepository points;
    private final AuditTrail audit;

    public DeleteBackupDestination(BackupDestinationRepository destinations,
                                   BackupRepository backups, RestorePointRepository points,
                                   AuditTrail audit) {
        this.destinations = destinations;
        this.backups = backups;
        this.points = points;
        this.audit = audit;
    }

    /**
     * @param organizationId the scope the caller may touch - null for the operator screen
     * @throws NotFoundException if there is no such destination in that scope
     * @throws RequestRejected   if a policy or a snapshot still points at it
     */
    @Transactional
    public void delete(AuditActor actor, UUID organizationId, UUID destinationId) {
        BackupDestination destination = destinations.findById(destinationId)
                .filter(candidate -> sameScope(candidate.organizationId(), organizationId))
                .orElseThrow(() -> NotFoundException.of("backup_destination", destinationId));

        long policies = backups.countByDestinationId(destinationId);
        long snapshots = points.countByDestinationId(destinationId);
        if (policies > 0 || snapshots > 0) {
            throw new RequestRejected(null, "\"" + destination.name() + "\" still holds "
                    + snapshots + " snapshot(s) and is used by " + policies + " backup(s). "
                    + "Switch it off instead, or move those first.");
        }

        destinations.delete(destination);
        audit.record(AuditEntry.succeeded(actor, "backup_destination.delete",
                AuditTarget.of("backup_destination", destinationId, destination.name()),
                organizationId, CreateBackupDestination.describe(destination)));
    }

    private static boolean sameScope(UUID owner, UUID caller) {
        return owner == null ? caller == null : owner.equals(caller);
    }
}
