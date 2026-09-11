package lhqm.furimeo.wisper.backup;

import java.time.Instant;
import java.util.UUID;

/**
 * One backup policy as a page shows it: the row, plus the three things about it that live
 * in other tables and that a customer looks at before anything else - what it copies, where
 * it puts it, and how much is actually there.
 *
 * <p>A projection rather than the aggregate, because the aggregate carries ids and the page
 * needs names, and because sending {@link Backup} straight to Inertia would serialise a
 * {@code destinationId} the client can do nothing with while still not answering "did it
 * work last night".
 *
 * @param storedBytes  the sum of the snapshots this policy still has, which is what counts
 *                     against {@code BACKUP_BYTES}
 * @param snapshotCount how many are available, not how many were ever taken
 */
public record BackupView(
        UUID id,
        String name,
        BackupTargetKind targetKind,
        String targetLabel,
        UUID destinationId,
        String destinationName,
        DestinationKind destinationKind,
        String schedule,
        String timezone,
        boolean enabled,
        int retentionCount,
        int retentionDays,
        Instant lastRunAt,
        BackupRunStatus lastStatus,
        String lastError,
        Instant nextRunAt,
        long snapshotCount,
        Instant latestSnapshotAt,
        long storedBytes) {

    /** Whether a cron fires it, as opposed to only the button. */
    public boolean isScheduled() {
        return enabled && schedule != null && !schedule.isBlank();
    }

    /** Whether the last thing that happened was a failure the customer should look at. */
    public boolean needsAttention() {
        return lastStatus == BackupRunStatus.FAILED;
    }

    /** A policy that has never produced anything is the one worth a warning on the list. */
    public boolean hasNeverSucceeded() {
        return snapshotCount == 0;
    }
}
