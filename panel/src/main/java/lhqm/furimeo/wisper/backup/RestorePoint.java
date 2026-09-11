package lhqm.furimeo.wisper.backup;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * One snapshot that exists somewhere and can be restored from.
 *
 * <p>Built to outlive everything that produced it. The policy may be deleted, the volume
 * may be deleted, the node may be retired, and this row stays - {@code backup_id},
 * {@code volume_id}, {@code managed_database_id} and {@code node_id} are all
 * {@code SET NULL}. {@link #targetLabel} is the human-readable copy of what was backed up,
 * taken at the time and never recomputed, because the moment somebody needs a restore is
 * usually the moment after something was deleted.
 *
 * <p>The row's id is also the id the node is given as {@code RunBackup.backup_id}: one
 * run produces one snapshot, so a second command carrying the same id is the same backup
 * rather than a second one, and the result comes back keyed to a row that already exists.
 *
 * <p>{@link #nodeId} is where the snapshot was <em>taken</em>. It is not where a restore
 * goes: a service can be migrated between the backup and the restore, and the restore
 * follows the target's current placement. See {@link ResolveBackupTarget}.
 *
 * @param objectKey the key inside the destination, relative to its path prefix. Required
 *                  once the state is {@code AVAILABLE}, along with a size and a finish
 *                  time - without all three nothing could restore from it, which is what
 *                  {@code restore_point_available_is_locatable} enforces
 * @param expiresAt when retention says this may go. Null means "kept until somebody says
 *                  otherwise", which is what a safety snapshot gets before it is given a
 *                  clock of its own
 */
public record RestorePoint(
        @Id UUID id,
        UUID organizationId,
        UUID backupId,
        UUID destinationId,
        UUID nodeId,

        BackupTargetKind targetKind,
        UUID volumeId,
        UUID managedDatabaseId,
        String targetLabel,

        RestorePointState state,
        RestorePointTrigger trigger,

        String objectKey,
        Long sizeBytes,
        boolean compressed,
        boolean encrypted,
        String checksumSha256,

        Instant startedAt,
        Instant finishedAt,
        Instant expiresAt,
        String errorMessage,

        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    /**
     * A snapshot the node has just been asked for.
     *
     * @param encrypted whether the archive is encrypted before it leaves the node, which
     *                  is decided by the destination having an archive passphrase
     */
    public static RestorePoint running(UUID id, UUID organizationId, UUID backupId,
                                       UUID destinationId, UUID nodeId,
                                       BackupTargetKind targetKind, UUID volumeId,
                                       UUID managedDatabaseId, String targetLabel,
                                       RestorePointTrigger trigger, boolean encrypted,
                                       Instant startedAt) {
        return new RestorePoint(id, organizationId, backupId, destinationId, nodeId,
                targetKind, volumeId, managedDatabaseId, targetLabel,
                RestorePointState.RUNNING, trigger,
                null, null, true, encrypted, null,
                startedAt, null, null, null, null, null, null);
    }

    /** The node reported an archive at the destination. */
    public RestorePoint available(String key, long size, String checksum, Instant finishedAt,
                                  Instant expiresAt) {
        return new RestorePoint(id, organizationId, backupId, destinationId, nodeId,
                targetKind, volumeId, managedDatabaseId, targetLabel,
                RestorePointState.AVAILABLE, trigger,
                key, size, compressed, encrypted, checksum,
                startedAt, finishedAt, expiresAt, null, createdAt, updatedAt, version);
    }

    /** The run produced nothing. */
    public RestorePoint failed(String reason, Instant finishedAt) {
        return new RestorePoint(id, organizationId, backupId, destinationId, nodeId,
                targetKind, volumeId, managedDatabaseId, targetLabel,
                RestorePointState.FAILED, trigger,
                objectKey, sizeBytes, compressed, encrypted, checksumSha256,
                startedAt, finishedAt, null, reason, createdAt, updatedAt, version);
    }

    /**
     * Retention no longer keeps it.
     *
     * <p>The row stays and the state changes, rather than the row being deleted: "this
     * snapshot used to exist and aged out on the third" is an answer, and a missing row is
     * not. The bytes at the destination are removed by the node, which is the side holding
     * the credentials, when it applies the same retention rule on its next run.
     */
    public RestorePoint expired() {
        return inState(RestorePointState.EXPIRED, errorMessage);
    }

    /** Somebody deleted it. */
    public RestorePoint deleted() {
        return inState(RestorePointState.DELETED, errorMessage);
    }

    /** A clock for a snapshot that was created without one, such as a safety snapshot. */
    public RestorePoint expiringAt(Instant when) {
        return new RestorePoint(id, organizationId, backupId, destinationId, nodeId,
                targetKind, volumeId, managedDatabaseId, targetLabel, state, trigger,
                objectKey, sizeBytes, compressed, encrypted, checksumSha256,
                startedAt, finishedAt, when, errorMessage, createdAt, updatedAt, version);
    }

    /** The id of whichever target {@link #targetKind} names, or null once it is deleted. */
    public UUID subjectId() {
        return targetKind == BackupTargetKind.VOLUME ? volumeId : managedDatabaseId;
    }

    /** Whether a restore may be started from it. */
    public boolean isRestorable() {
        return state.isRestorable() && objectKey != null && !objectKey.isBlank();
    }

    /** Bytes at the destination, or zero while it is still running or has failed. */
    public long occupiedBytes() {
        return state.occupiesSpace() && sizeBytes != null ? sizeBytes : 0L;
    }

    private RestorePoint inState(RestorePointState newState, String message) {
        return new RestorePoint(id, organizationId, backupId, destinationId, nodeId,
                targetKind, volumeId, managedDatabaseId, targetLabel, newState, trigger,
                objectKey, sizeBytes, compressed, encrypted, checksumSha256,
                startedAt, finishedAt, expiresAt, message, createdAt, updatedAt, version);
    }
}
