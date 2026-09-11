package lhqm.furimeo.wisper.backup;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Finds out whether a destination actually works, before a customer schedules a month of
 * backups into it.
 *
 * <p>For an S3 destination this is a real signed request to the real endpoint
 * ({@link CheckS3Bucket}). It exercises the URL, the bucket, the region, the addressing
 * style and both halves of the credential, which between them account for essentially every
 * way an object store is misconfigured. A check that only looked at whether the fields were
 * filled in would be a button that verifies the form.
 *
 * <p>For a local destination there is nothing on the network to ask: the directory is on a
 * node, and the panel has no command that would make one look. So the check is the path
 * rule - absolute, no traversal, not a system directory, inside the node's backup root -
 * and the answer says plainly that the first snapshot is what proves the rest. Claiming
 * more would be worse than claiming nothing.
 *
 * <p>Deliberately not {@code @Transactional}: it makes a network call that can take the
 * whole of {@code wisper.backup.destination-check-timeout}, and holding a PostgreSQL
 * connection open across that would tie up the pool behind a remote server nobody controls.
 * The one row it writes is written in the repository's own transaction afterwards.
 */
@Component
public class VerifyDestination {

    private static final Logger log = LoggerFactory.getLogger(VerifyDestination.class);

    private final BackupDestinationRepository destinations;
    private final ReadDestinationCredentials credentials;
    private final ValidateDestinationDraft validation;
    private final CheckS3Bucket s3;
    private final AuditTrail audit;

    public VerifyDestination(BackupDestinationRepository destinations,
                             ReadDestinationCredentials credentials,
                             ValidateDestinationDraft validation, CheckS3Bucket s3,
                             AuditTrail audit) {
        this.destinations = destinations;
        this.credentials = credentials;
        this.validation = validation;
        this.s3 = s3;
        this.audit = audit;
    }

    /**
     * Checks one destination and records the result on the row.
     *
     * @param organizationId the scope the caller may touch - null for the operator screen
     * @return the sentence to show the customer, whether it passed or not
     * @throws NotFoundException if there is no such destination in that scope
     */
    public String verify(AuditActor actor, UUID organizationId, UUID destinationId) {
        BackupDestination destination = destinations.findById(destinationId)
                .filter(candidate -> sameScope(candidate.organizationId(), organizationId))
                .orElseThrow(() -> NotFoundException.of("backup_destination", destinationId));

        Optional<String> failure = check(destination);
        Instant at = Instant.now();
        destinations.save(failure
                .map(reason -> destination.checkFailed(at, reason))
                .orElseGet(() -> destination.checkedOk(at)));

        if (failure.isPresent()) {
            log.warn("Destination \"{}\" failed its check: {}", destination.name(),
                    failure.get());
            audit.record(AuditEntry.failed(actor, "backup_destination.verify",
                    AuditTarget.of("backup_destination", destination.id(), destination.name()),
                    organizationId, failure.get()));
            return failure.get();
        }
        String message = passedMessage(destination);
        audit.record(AuditEntry.succeeded(actor, "backup_destination.verify",
                AuditTarget.of("backup_destination", destination.id(), destination.name()),
                organizationId, message));
        return message;
    }

    /** Empty when the destination is usable, or one sentence saying why it is not. */
    private Optional<String> check(BackupDestination destination) {
        if (destination.kind() == DestinationKind.LOCAL) {
            try {
                validation.requireLocalPath(destination.localPath());
                return Optional.empty();
            } catch (RequestRejected rejected) {
                return Optional.of(rejected.getMessage());
            }
        }
        try {
            return s3.probe(credentials.of(destination));
        } catch (RuntimeException unreadable) {
            // The stored secret is not an envelope, or the key that made it is not
            // configured. Either way the destination cannot be used, and the message names
            // the problem without quoting the value.
            return Optional.of("The stored credentials could not be read: "
                    + unreadable.getMessage());
        }
    }

    private static String passedMessage(BackupDestination destination) {
        if (destination.kind() == DestinationKind.LOCAL) {
            return "The path is accepted. A local destination lives on the node itself, so the "
                    + "first snapshot is what proves the directory is writable - and losing the "
                    + "node loses these backups with it.";
        }
        return "Reached " + destination.bucket() + " at " + destination.endpoint()
                + " and the credentials were accepted.";
    }

    private static boolean sameScope(UUID owner, UUID caller) {
        return owner == null ? caller == null : owner.equals(caller);
    }
}
