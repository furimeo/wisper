package lhqm.furimeo.wisper.backup;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.crypto.SecretCipher;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Changes a destination: a moved endpoint, a rotated key, a bucket in a new region, or the
 * switch that takes it out of the picker.
 *
 * <p>Editing has to exist. {@code backup} and {@code restore_point} both reference this row
 * with {@code ON DELETE RESTRICT}, so a destination holding snapshots cannot be deleted and
 * recreated - which means rotating a key would otherwise be impossible without abandoning
 * every snapshot behind it. That is the situation this method prevents.
 *
 * <p>{@link #kind} is not editable. Turning an S3 destination into a local directory would
 * leave every snapshot pointing at an object key that no longer means anything, and the
 * customer's list would say those snapshots exist.
 *
 * <p>A blank secret means "keep the stored one". Changing a bucket name should not require
 * re-typing a forty-character key, because a form that demands it is a form people paste
 * the wrong thing into.
 */
@Component
public class UpdateBackupDestination {

    private final BackupDestinationRepository destinations;
    private final ValidateDestinationDraft validation;
    private final SecretCipher cipher;
    private final AuditTrail audit;

    public UpdateBackupDestination(BackupDestinationRepository destinations,
                                   ValidateDestinationDraft validation, SecretCipher cipher,
                                   AuditTrail audit) {
        this.destinations = destinations;
        this.validation = validation;
        this.cipher = cipher;
        this.audit = audit;
    }

    /**
     * @param organizationId the scope the caller is allowed to touch - null for the operator
     *                       screen, which owns the platform-wide rows
     * @throws NotFoundException if there is no such destination in that scope
     * @throws RequestRejected   naming the field at fault
     */
    @Transactional
    public BackupDestination update(AuditActor actor, UUID organizationId, UUID destinationId,
                                    DestinationDraft draft, boolean enabled) {
        BackupDestination destination = destinations.findById(destinationId)
                .filter(candidate -> sameScope(candidate.organizationId(), organizationId))
                .orElseThrow(() -> NotFoundException.of("backup_destination", destinationId));

        if (draft.kind() != destination.kind()) {
            throw new RequestRejected("kind", "A destination cannot change between S3 and a "
                    + "directory on the node. Its snapshots are already addressed one way.");
        }
        validation.check(draft, false);
        if (!draft.name().equals(destination.name())
                && destinations.existsInScope(organizationId, draft.name())) {
            throw new RequestRejected("name", "There is already a destination called \""
                    + draft.name() + "\".");
        }

        BackupDestination updated = destination.renamedTo(draft.name()).enabled(enabled);
        if (destination.kind() == DestinationKind.LOCAL) {
            updated = updated.withLocalPath(validation.requireLocalPath(draft.localPath()));
        } else {
            updated = updated.withS3(draft.endpoint(), draft.region(), draft.bucket(),
                    draft.pathPrefix(),
                    draft.storageClass().isEmpty() ? null : draft.storageClass());
            if (draft.hasNewSecret() || !draft.accessKeyId().equals(destination.accessKeyId())) {
                if (!draft.hasNewSecret()) {
                    throw new RequestRejected("secretAccessKey",
                            "A new access key id needs its own secret key.");
                }
                updated = updated.withCredentials(draft.accessKeyId(),
                        cipher.encrypt(draft.secretAccessKey()));
            }
        }

        BackupDestination stored = destinations.save(updated);
        audit.record(AuditEntry.succeeded(actor, "backup_destination.update",
                AuditTarget.of("backup_destination", stored.id(), stored.name()), organizationId,
                (enabled ? "enabled, " : "disabled, ") + CreateBackupDestination.describe(stored)
                        + (draft.hasNewSecret() ? ", secret key replaced" : "")));
        return stored;
    }

    /** Null matches null: the operator screen owns exactly the platform-wide rows. */
    private static boolean sameScope(UUID owner, UUID caller) {
        return owner == null ? caller == null : owner.equals(caller);
    }
}
