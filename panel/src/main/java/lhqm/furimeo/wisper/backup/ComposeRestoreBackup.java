package lhqm.furimeo.wisper.backup;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.proto.v1.RestoreBackup;

/**
 * Turns a snapshot, a target and a mode into the {@code RestoreBackup} command a node is
 * given. Pure: values in, protobuf out.
 *
 * <p>Three fields carry the whole of design §8.3's "restoring is one button":
 *
 * <ul>
 * <li>{@code location} comes from the snapshot rather than being recomputed, so a restore
 *     still works after the destination's layout has been reorganised.</li>
 * <li>{@code source} carries the credentials again rather than relying on the node
 *     remembering them, because keys rotate and a restore is when a stale one is least
 *     welcome.</li>
 * <li>{@code stop_workload} is on for a volume being overwritten in place. Restoring
 *     underneath a running process hands it a filesystem that changed while it was not
 *     looking. It is off for a database, whose engine is shared with other customers and
 *     must not be stopped, and off for a verify, which writes somewhere else entirely.</li>
 * </ul>
 */
@Component
public class ComposeRestoreBackup {

    private final BackupSettings settings;

    public ComposeRestoreBackup(BackupSettings settings) {
        this.settings = settings;
    }

    /**
     * @param restoreRunId the panel's id for this attempt, distinct from the snapshot's,
     *                     and what the {@code RestoreCompleted} comes back keyed to
     * @param target       where the data is going, resolved now rather than read off the
     *                     snapshot: the service may have moved nodes since it was taken
     */
    public RestoreBackup forRun(String restoreRunId, RestorePoint point, BackupTargetRef target,
                                DestinationCredentials source, RestoreMode mode) {
        RestoreBackup.Builder command = RestoreBackup.newBuilder()
                .setRestoreId(restoreRunId)
                .setRestorePointId(point.id().toString())
                .setLocation(point.objectKey() == null ? "" : point.objectKey())
                .setKind(ComposeRunBackup.kindOf(target.targetKind()))
                .setSubjectId(target.subjectId().toString())
                .setWorkloadId(target.workloadId())
                .setSource(ComposeRunBackup.destinationOf(source))
                .setStopWorkload(stopsTheWorkload(target, mode))
                .setDryRun(mode == RestoreMode.VERIFY)
                .setTimeoutSeconds(settings.restoreTimeoutSeconds());
        if (target.targetKind() == BackupTargetKind.DATABASE) {
            command.setEngine(ComposeRunBackup.engineOf(target.engine()))
                    .setDatabaseName(target.databaseName());
        }
        return command.build();
    }

    private static boolean stopsTheWorkload(BackupTargetRef target, RestoreMode mode) {
        return mode.overwritesTheTarget()
                && target.targetKind() == BackupTargetKind.VOLUME
                && !target.workloadId().isEmpty();
    }
}
