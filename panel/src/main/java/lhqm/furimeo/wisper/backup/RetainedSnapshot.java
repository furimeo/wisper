package lhqm.furimeo.wisper.backup;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The two facts retention needs about a snapshot: which one it is and when it was taken.
 *
 * <p>Narrower than {@link RestorePoint} on purpose. {@link RetentionPolicy} is the one
 * piece of this package that is pure arithmetic, and keeping its input to two fields is
 * what lets a test build thirty snapshots across four months in three lines instead of
 * thirty twenty-argument constructor calls.
 *
 * @param takenAt when the snapshot was <em>started</em>, not when it finished. A backup
 *                that takes two hours belongs to the day it began: bucketing by the finish
 *                time would move a nightly snapshot into the next day whenever it ran long,
 *                and the daily tier would then keep two for one day and none for another
 */
public record RetainedSnapshot(UUID id, Instant takenAt) {

    public RetainedSnapshot {
        if (id == null || takenAt == null) {
            throw new IllegalArgumentException("A snapshot needs an id and a time");
        }
    }

    /** The retention view of a stored row. */
    public static RetainedSnapshot of(RestorePoint point) {
        return new RetainedSnapshot(point.id(), point.startedAt());
    }

    /** The retention view of a list of stored rows, in the order given. */
    public static List<RetainedSnapshot> of(List<RestorePoint> points) {
        return points.stream().map(RetainedSnapshot::of).toList();
    }
}
