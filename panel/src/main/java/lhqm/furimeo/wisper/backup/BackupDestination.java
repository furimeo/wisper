package lhqm.furimeo.wisper.backup;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * Somewhere snapshots can be pushed: an S3-compatible bucket, or a directory on the node.
 *
 * <p>A null {@link #organizationId} is a platform-wide destination an operator configured
 * and every tenant may use. A non-null one belongs to that tenant and nobody else sees it.
 * The {@code backup_destination_scope_name_key} index is {@code NULLS NOT DISTINCT}, so
 * two platform-wide destinations still cannot share a name.
 *
 * <p>{@link #secretAccessKey} and {@link #archivePassphrase} are AES-GCM envelopes, not
 * plaintext - the {@code *_is_envelope} CHECKs refuse anything else. Nothing in this
 * package decrypts them except {@link ReadDestinationCredentials}, and the value it
 * produces never reaches a log line, an audit detail or a page prop. That is why
 * {@link #toString()} is overridden: a record's generated one prints every component, and
 * one {@code log.debug("saving {}", destination)} would put an envelope in a log file
 * where it will be copied into a support ticket.
 *
 * @param pathPrefix    key prefix inside the bucket, never null - the column is
 *                      {@code NOT NULL DEFAULT ''} and Spring Data JDBC writes every
 *                      column on insert, nulls included
 * @param lastCheckedAt when {@link VerifyDestination} last proved this reachable
 */
public record BackupDestination(
        @Id UUID id,
        UUID organizationId,
        String name,
        DestinationKind kind,

        String endpoint,
        String region,
        String bucket,
        String pathPrefix,
        String accessKeyId,
        String secretAccessKey,
        String storageClass,

        String localPath,

        String archivePassphrase,

        boolean enabled,
        Instant lastCheckedAt,
        String lastCheckError,

        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    public BackupDestination {
        pathPrefix = pathPrefix == null ? "" : pathPrefix;
    }

    /**
     * A new S3-compatible destination.
     *
     * @param secretAccessKey    already encrypted; the caller holds the cipher
     * @param archivePassphrase  already encrypted, or null for an archive the destination
     *                           itself is trusted with
     */
    public static BackupDestination s3(UUID id, UUID organizationId, String name,
                                       String endpoint, String region, String bucket,
                                       String pathPrefix, String accessKeyId,
                                       String secretAccessKey, String storageClass,
                                       String archivePassphrase) {
        return new BackupDestination(id, organizationId, name, DestinationKind.S3,
                endpoint, region, bucket, pathPrefix, accessKeyId, secretAccessKey, storageClass,
                null, archivePassphrase, true, null, null, null, null, null);
    }

    /** A new directory on the node, named by an absolute path. */
    public static BackupDestination local(UUID id, UUID organizationId, String name,
                                          String localPath, String archivePassphrase) {
        return new BackupDestination(id, organizationId, name, DestinationKind.LOCAL,
                null, null, null, "", null, null, null,
                localPath, archivePassphrase, true, null, null, null, null, null);
    }

    /** Whether this destination is one every organization may choose. */
    public boolean isPlatformWide() {
        return organizationId == null;
    }

    /** Whether {@code organizationId} may use it: its own, or a platform-wide one. */
    public boolean isUsableBy(UUID candidate) {
        return enabled && (isPlatformWide() || organizationId.equals(candidate));
    }

    /** Whether a check has ever run, and whether the last one passed. */
    public boolean isProvenReachable() {
        return lastCheckedAt != null && lastCheckError == null;
    }

    /** The S3 settings, changed. Credentials are separate so a rename cannot lose them. */
    public BackupDestination withS3(String newEndpoint, String newRegion, String newBucket,
                                    String newPathPrefix, String newStorageClass) {
        return new BackupDestination(id, organizationId, name, kind,
                newEndpoint, newRegion, newBucket, newPathPrefix, accessKeyId, secretAccessKey,
                newStorageClass, localPath, archivePassphrase, enabled, lastCheckedAt,
                lastCheckError, createdAt, updatedAt, version);
    }

    /** New credentials, both already encrypted. Clears the last check: it proved nothing. */
    public BackupDestination withCredentials(String newAccessKeyId, String newSecretAccessKey) {
        return new BackupDestination(id, organizationId, name, kind,
                endpoint, region, bucket, pathPrefix, newAccessKeyId, newSecretAccessKey,
                storageClass, localPath, archivePassphrase, enabled, null, null,
                createdAt, updatedAt, version);
    }

    /** A new archive passphrase, already encrypted, or null to stop encrypting archives. */
    public BackupDestination withArchivePassphrase(String encrypted) {
        return new BackupDestination(id, organizationId, name, kind,
                endpoint, region, bucket, pathPrefix, accessKeyId, secretAccessKey, storageClass,
                localPath, encrypted, enabled, lastCheckedAt, lastCheckError,
                createdAt, updatedAt, version);
    }

    /** A new path for a local destination. */
    public BackupDestination withLocalPath(String newLocalPath) {
        return new BackupDestination(id, organizationId, name, kind,
                endpoint, region, bucket, pathPrefix, accessKeyId, secretAccessKey, storageClass,
                newLocalPath, archivePassphrase, enabled, null, null,
                createdAt, updatedAt, version);
    }

    public BackupDestination renamedTo(String newName) {
        return new BackupDestination(id, organizationId, newName, kind,
                endpoint, region, bucket, pathPrefix, accessKeyId, secretAccessKey, storageClass,
                localPath, archivePassphrase, enabled, lastCheckedAt, lastCheckError,
                createdAt, updatedAt, version);
    }

    /**
     * Turned on or off. A disabled destination disappears from the picker and its policies
     * stop running, but its snapshots stay restorable - the bytes are still there.
     */
    public BackupDestination enabled(boolean nowEnabled) {
        return new BackupDestination(id, organizationId, name, kind,
                endpoint, region, bucket, pathPrefix, accessKeyId, secretAccessKey, storageClass,
                localPath, archivePassphrase, nowEnabled, lastCheckedAt, lastCheckError,
                createdAt, updatedAt, version);
    }

    /** The check passed. */
    public BackupDestination checkedOk(Instant at) {
        return withCheck(at, null);
    }

    /** The check failed, with one sentence saying how. */
    public BackupDestination checkFailed(Instant at, String error) {
        return withCheck(at, error == null || error.isBlank() ? "The check failed." : error);
    }

    private BackupDestination withCheck(Instant at, String error) {
        return new BackupDestination(id, organizationId, name, kind,
                endpoint, region, bucket, pathPrefix, accessKeyId, secretAccessKey, storageClass,
                localPath, archivePassphrase, enabled, at, error, createdAt, updatedAt, version);
    }

    /**
     * Everything except the two encrypted columns.
     *
     * <p>The envelopes are useless without a key, but "useless without a key" is an
     * argument nobody makes when a log file leaks, and an envelope in a log is one offline
     * attack away from being a credential. So they are not printed at all.
     */
    @Override
    public String toString() {
        return "BackupDestination[id=" + id + ", organizationId=" + organizationId
                + ", name=" + name + ", kind=" + kind + ", endpoint=" + endpoint
                + ", region=" + region + ", bucket=" + bucket + ", pathPrefix=" + pathPrefix
                + ", accessKeyId=" + accessKeyId + ", secretAccessKey=<redacted>"
                + ", storageClass=" + storageClass + ", localPath=" + localPath
                + ", archivePassphrase=<redacted>, enabled=" + enabled
                + ", lastCheckedAt=" + lastCheckedAt + ", lastCheckError=" + lastCheckError + "]";
    }
}
