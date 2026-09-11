package lhqm.furimeo.wisper.backup;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closes backups and restores that were handed to a node and never came back.
 *
 * <p>A stream drops, a node is rebuilt, the panel restarts between sending a command and
 * hearing its result. None of those is unusual over a tunnel, and each leaves a row saying
 * something is in progress when nothing is. For a snapshot that is merely misleading; for a
 * restore it is worse, because the two partial unique indexes on {@code restore_run} hold
 * the target against a second attempt while a run is in flight - so a dropped connection
 * would otherwise make a volume permanently un-restorable.
 *
 * <p>The cutoff is {@code wisper.backup.abandoned-after}, comfortably longer than the
 * timeouts the panel applies to the commands themselves. Being late here costs a stale row
 * for an hour; being early would fail a restore that is still copying a large database.
 */
@Component
public class FailAbandonedRuns {

    private static final Logger log = LoggerFactory.getLogger(FailAbandonedRuns.class);

    private final RestorePointRepository points;
    private final RestoreRunRepository runs;
    private final BackupSettings settings;

    public FailAbandonedRuns(RestorePointRepository points, RestoreRunRepository runs,
                             BackupSettings settings) {
        this.points = points;
        this.runs = runs;
        this.settings = settings;
    }

    /** @return how many rows were closed */
    @Transactional
    public int sweep() {
        Instant cutoff = Instant.now().minus(settings.abandonedAfter());
        return failSnapshots(cutoff) + failRestores(cutoff);
    }

    private int failSnapshots(Instant cutoff) {
        List<RestorePoint> stuck = points.findAbandoned(cutoff, settings.sweepBatch());
        for (RestorePoint point : stuck) {
            points.save(point.failed("The node never reported the result of this backup. It "
                    + "may or may not have finished; nothing here can be restored from.",
                    Instant.now()));
            log.warn("Snapshot {} of {} was abandoned and has been failed", point.id(),
                    point.targetLabel());
        }
        return stuck.size();
    }

    private int failRestores(Instant cutoff) {
        List<RestoreRun> stuck = runs.findStale(cutoff, settings.sweepBatch());
        for (RestoreRun run : stuck) {
            runs.save(run.failed("The node never reported the result of this restore. Check the "
                            + "target before trying again.", Instant.now())
                    .withLogLine("Abandoned: no result after "
                            + settings.abandonedAfter().toHours() + "h.",
                            settings.restoreLogLimit()));
            log.warn("Restore {} was abandoned and has been failed", run.id());
        }
        return stuck.size();
    }
}
