package lhqm.furimeo.wisper.backup;

import java.time.Instant;
import java.util.UUID;

/**
 * One destination as a page shows it.
 *
 * <p>There is no secret here, and that is the reason this record exists rather than the
 * page being handed a {@link BackupDestination}. An Inertia prop is JSON in the HTML of the
 * response: a field on this record is a field in the page source, visible to anybody
 * looking over the customer's shoulder and to every browser extension they have installed.
 * Even the envelope stays behind - it is one offline attack from being a credential, and it
 * is of no use to a page.
 *
 * <p>{@link #accessKeyId} is shown, deliberately. It is not a secret, it is how an operator
 * tells two keys apart when one of them has been rotated, and hiding it makes the screen
 * unusable for the one job it has.
 *
 * @param editable false for a platform-wide destination on a customer's screen: they may
 *                 choose it, and they may not change something every other tenant uses
 */
public record DestinationView(
        UUID id,
        String name,
        DestinationKind kind,
        boolean platformWide,
        boolean editable,
        boolean enabled,
        String endpoint,
        String region,
        String bucket,
        String pathPrefix,
        String accessKeyId,
        String storageClass,
        String localPath,
        Instant lastCheckedAt,
        String lastCheckError,
        long policyCount,
        long snapshotCount,
        long storedBytes) {

    /** Whether the last check passed. Null {@code lastCheckedAt} means never checked. */
    public boolean isProvenReachable() {
        return lastCheckedAt != null && lastCheckError == null;
    }

    /** Whether anything would stop it being deleted. */
    public boolean isRemovable() {
        return editable && policyCount == 0 && snapshotCount == 0;
    }

    /** What the picker shows next to the name. */
    public String summary() {
        return kind == DestinationKind.LOCAL
                ? "on the node at " + localPath
                : bucket + " at " + endpoint;
    }
}
