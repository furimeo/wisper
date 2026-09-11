package lhqm.furimeo.wisper.backup;

import java.time.Instant;
import java.util.UUID;

/**
 * One restore as its own page shows it.
 *
 * <p>This page is what stops the restore button being a click that appears to do nothing.
 * The run takes minutes, the request that started it returned immediately, and everything
 * the customer learns after that comes from this record: which snapshot, into what, on
 * which node, how far it has got, and - if it went wrong - the safety snapshot they can go
 * back to.
 *
 * @param log             the node's progress, newest line last. Bounded by
 *                        {@code wisper.backup.restore-log-limit}
 * @param safetyRestorePointId the snapshot taken of the current data before it was
 *                        overwritten, so an in-place restore of the wrong snapshot is
 *                        itself undoable. Null for a verify, which overwrites nothing
 */
public record RestoreRunView(
        UUID id,
        UUID restorePointId,
        String targetLabel,
        Instant snapshotTakenAt,
        RestoreMode mode,
        RestoreState state,
        UUID nodeId,
        Instant startedAt,
        Instant finishedAt,
        Long bytesRestored,
        String errorMessage,
        String log,
        UUID safetyRestorePointId,
        String requestedBy) {

    /** Whether the page should keep polling. */
    public boolean isFinished() {
        return state.isTerminal();
    }

    /** How long it has taken so far, or in total once it is done. */
    public long durationSeconds(Instant now) {
        if (startedAt == null) {
            return 0;
        }
        Instant end = finishedAt == null ? now : finishedAt;
        return Math.max(0, end.getEpochSecond() - startedAt.getEpochSecond());
    }
}
