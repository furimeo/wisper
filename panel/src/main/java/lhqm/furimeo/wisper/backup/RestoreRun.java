package lhqm.furimeo.wisper.backup;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * One attempt to restore from a {@link RestorePoint}.
 *
 * <p>This table exists because restoring is a button (design §8.3) and an asynchronous
 * button needs somewhere to report progress, or the screen behind it is the blank frame
 * this project was rebuilt to avoid. Everything the restore page shows comes from here.
 *
 * <p>At most one run may be in flight per target volume or database, enforced by two
 * partial unique indexes. Two concurrent restores into one target interleave their writes
 * and produce something that was never a valid snapshot of anything, so the second attempt
 * is refused at the database rather than merged optimistically.
 *
 * @param nodeId          where it ran. Resolved when the run starts, from where the target
 *                        lives <em>now</em> - not from the snapshot's own node, which may
 *                        have been drained since
 * @param safetyRestorePointId the {@code PRE_RESTORE} snapshot taken before overwriting,
 *                        so restoring the wrong snapshot is itself undoable. Null for a
 *                        {@code VERIFY} run, which touches nothing
 * @param log             progress the node reported, appended a line at a time. One column
 *                        rather than a child table because a restore emits tens of lines,
 *                        not the thousands a build does
 */
public record RestoreRun(
        @Id UUID id,
        UUID restorePointId,
        UUID requestedByAccountId,
        UUID nodeId,

        UUID targetVolumeId,
        UUID targetManagedDatabaseId,
        UUID safetyRestorePointId,

        RestoreMode mode,
        RestoreState state,
        Instant startedAt,
        Instant finishedAt,
        Long bytesRestored,
        String errorMessage,
        String log,

        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    public RestoreRun {
        log = log == null ? "" : log;
    }

    /** A run written in the same transaction as its job, before any node is involved. */
    public static RestoreRun queued(UUID id, UUID restorePointId, UUID requestedByAccountId,
                                    UUID targetVolumeId, UUID targetManagedDatabaseId,
                                    RestoreMode mode, String firstLine) {
        return new RestoreRun(id, restorePointId, requestedByAccountId, null,
                targetVolumeId, targetManagedDatabaseId, null, mode, RestoreState.QUEUED,
                null, null, null, null, firstLine, null, null, null);
    }

    /** Picked up by the worker and handed to a node. */
    public RestoreRun startedOn(UUID node, Instant at) {
        return new RestoreRun(id, restorePointId, requestedByAccountId, node,
                targetVolumeId, targetManagedDatabaseId, safetyRestorePointId, mode,
                RestoreState.RUNNING, at, null, bytesRestored, null, log,
                createdAt, updatedAt, version);
    }

    /** The safety snapshot exists; the overwrite may proceed. */
    public RestoreRun protectedBy(UUID snapshotId) {
        return new RestoreRun(id, restorePointId, requestedByAccountId, nodeId,
                targetVolumeId, targetManagedDatabaseId, snapshotId, mode, state,
                startedAt, finishedAt, bytesRestored, errorMessage, log,
                createdAt, updatedAt, version);
    }

    /** It worked. */
    public RestoreRun succeeded(long bytes, Instant at) {
        return new RestoreRun(id, restorePointId, requestedByAccountId, nodeId,
                targetVolumeId, targetManagedDatabaseId, safetyRestorePointId, mode,
                RestoreState.SUCCEEDED, startedOrNow(at), at, bytes, null, log,
                createdAt, updatedAt, version);
    }

    /** It did not. */
    public RestoreRun failed(String reason, Instant at) {
        return new RestoreRun(id, restorePointId, requestedByAccountId, nodeId,
                targetVolumeId, targetManagedDatabaseId, safetyRestorePointId, mode,
                RestoreState.FAILED, startedOrNow(at), at, bytesRestored, reason, log,
                createdAt, updatedAt, version);
    }

    /** Abandoned before a node was asked: the target vanished, or nothing would take it. */
    public RestoreRun cancelled(String reason, Instant at) {
        return new RestoreRun(id, restorePointId, requestedByAccountId, nodeId,
                targetVolumeId, targetManagedDatabaseId, safetyRestorePointId, mode,
                RestoreState.CANCELLED, startedAt, at, bytesRestored, reason, log,
                createdAt, updatedAt, version);
    }

    /** One more line of progress, kept in order and bounded so a chatty node cannot grow it
     * without limit. */
    public RestoreRun withLogLine(String line, int maxCharacters) {
        String appended = log.isEmpty() ? line : log + "\n" + line;
        if (appended.length() > maxCharacters) {
            appended = appended.substring(appended.length() - maxCharacters);
        }
        return new RestoreRun(id, restorePointId, requestedByAccountId, nodeId,
                targetVolumeId, targetManagedDatabaseId, safetyRestorePointId, mode, state,
                startedAt, finishedAt, bytesRestored, errorMessage, appended,
                createdAt, updatedAt, version);
    }

    /** Which target this run writes into, whichever kind it is. */
    public UUID targetId() {
        return targetVolumeId != null ? targetVolumeId : targetManagedDatabaseId;
    }

    /**
     * {@code restore_run_finished_has_start} refuses a finish time with no start, which a
     * run that failed before it reached a node would otherwise produce.
     */
    private Instant startedOrNow(Instant at) {
        return startedAt != null ? startedAt : at;
    }
}
