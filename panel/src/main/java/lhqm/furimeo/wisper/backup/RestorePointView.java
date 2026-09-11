package lhqm.furimeo.wisper.backup;

import java.time.Instant;
import java.util.UUID;

/**
 * One snapshot as the list shows it.
 *
 * <p>{@link #lastVerifiedAt} is the field this whole feature turns on. Design §8.3 is blunt
 * about it - a backup nobody has restored is not a backup - so the list says, per snapshot,
 * whether anybody has ever put it back successfully. A green tick that means "the upload
 * reported success" and a green tick that means "this was restored and worked" are different
 * claims, and only the second one is worth anything at three in the morning.
 *
 * @param restorable    whether the restore button does anything, so the page does not offer
 *                      one that always fails
 * @param activeRestores how many restores are reading it right now, which is why the delete
 *                      button is greyed out
 */
public record RestorePointView(
        UUID id,
        BackupTargetKind targetKind,
        String targetLabel,
        RestorePointState state,
        RestorePointTrigger trigger,
        Long sizeBytes,
        boolean encrypted,
        String objectKey,
        Instant startedAt,
        Instant finishedAt,
        Instant expiresAt,
        String errorMessage,
        String destinationName,
        String backupName,
        Instant lastVerifiedAt,
        long activeRestores,
        boolean restorable) {

    /** Whether anybody has ever proved this snapshot restores. */
    public boolean isProven() {
        return lastVerifiedAt != null;
    }

    /** How long the snapshot took, in seconds, or -1 while it is still running. */
    public long durationSeconds() {
        if (finishedAt == null) {
            return -1;
        }
        return finishedAt.getEpochSecond() - startedAt.getEpochSecond();
    }
}
