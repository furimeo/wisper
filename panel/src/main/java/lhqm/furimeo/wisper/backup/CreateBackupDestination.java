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

/**
 * Writes a new place for snapshots to go.
 *
 * <p>The secret access key is encrypted here and nowhere else on the way in. It goes
 * through {@link SecretCipher} into the envelope format the
 * {@code backup_destination_secret_is_envelope} CHECK insists on, so a plaintext key
 * reaching that column fails at this boundary - which has a stack trace - rather than at the
 * constraint, which has a name and no context.
 *
 * <p>Authorization is the caller's. A controller under {@code /backups} has resolved a
 * membership and called {@code requireWrite}; the one under {@code /admin} was gated by
 * {@code ROLE_ADMIN} in the security chain and passes a null organization, which is what
 * makes the destination platform-wide.
 */
@Component
public class CreateBackupDestination {

    private final BackupDestinationRepository destinations;
    private final ValidateDestinationDraft validation;
    private final SecretCipher cipher;
    private final AuditTrail audit;

    public CreateBackupDestination(BackupDestinationRepository destinations,
                                   ValidateDestinationDraft validation, SecretCipher cipher,
                                   AuditTrail audit) {
        this.destinations = destinations;
        this.validation = validation;
        this.cipher = cipher;
        this.audit = audit;
    }

    /**
     * @param organizationId the owner, or null for a destination every organization may use
     * @throws RequestRejected naming the field at fault
     */
    @Transactional
    public BackupDestination create(AuditActor actor, UUID organizationId,
                                    DestinationDraft draft) {
        validation.check(draft, true);
        if (destinations.existsInScope(organizationId, draft.name())) {
            throw new RequestRejected("name", "There is already a destination called \""
                    + draft.name() + "\".");
        }

        UUID id = UUID.randomUUID();
        BackupDestination created = destinations.save(draft.kind() == DestinationKind.LOCAL
                ? BackupDestination.local(id, organizationId, draft.name(),
                        validation.requireLocalPath(draft.localPath()), null)
                : BackupDestination.s3(id, organizationId, draft.name(), draft.endpoint(),
                        draft.region(), draft.bucket(), draft.pathPrefix(), draft.accessKeyId(),
                        cipher.encrypt(draft.secretAccessKey()),
                        draft.storageClass().isEmpty() ? null : draft.storageClass(), null));

        audit.record(AuditEntry.succeeded(actor, "backup_destination.create",
                AuditTarget.of("backup_destination", created.id(), created.name()),
                organizationId, describe(created)));
        return created;
    }

    /** The audit detail: where it points, never what opens it. */
    static String describe(BackupDestination destination) {
        return destination.kind() == DestinationKind.LOCAL
                ? "a directory on the node at " + destination.localPath()
                : "bucket \"" + destination.bucket() + "\" at " + destination.endpoint()
                        + " with key id " + destination.accessKeyId();
    }
}
