package lhqm.furimeo.wisper.backup;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.proto.v1.DatabaseEngine;
import lhqm.furimeo.wisper.proto.v1.RetentionRule;
import lhqm.furimeo.wisper.proto.v1.RunBackup;
import lhqm.furimeo.wisper.proto.v1.S3Destination;

/**
 * Turns a resolved target, a destination and a retention rule into the {@code RunBackup}
 * command a node is given.
 *
 * <p>Pure: values in, protobuf out, no database and no clock. That is what lets a test
 * assert the two properties that matter - that the id on the wire is the restore point's,
 * and that the retention numbers are the same ones the panel will apply itself - without a
 * PostgreSQL or a node behind it.
 *
 * <p>The three names this file cannot import are the three the wire and this package both
 * define: {@code BackupDestination}, {@code BackupTargetKind} and {@code DestinationKind}
 * exist in {@code proto.v1} as messages and here as rows. The proto ones are written out in
 * full; the local ones resolve from the package.
 */
@Component
public class ComposeRunBackup {

    private final BackupSettings settings;

    public ComposeRunBackup(BackupSettings settings) {
        this.settings = settings;
    }

    /**
     * @param restorePointId the id the snapshot is recorded under, which is also
     *                       {@code RunBackup.backup_id}: one run, one snapshot, and a
     *                       repeated command with the same id is the same backup rather
     *                       than a second one
     */
    public RunBackup forSnapshot(String restorePointId, BackupTargetRef target,
                                 DestinationCredentials destination, RetentionPolicy retention) {
        RunBackup.Builder command = RunBackup.newBuilder()
                .setBackupId(restorePointId)
                .setKind(kindOf(target.targetKind()))
                .setSubjectId(target.subjectId().toString())
                .setWorkloadId(target.workloadId())
                .setDestination(destinationOf(destination))
                .setRetention(ruleOf(retention))
                .setVerify(settings.verifyUploads())
                .setTimeoutSeconds(settings.backupTimeoutSeconds());
        if (target.targetKind() == BackupTargetKind.DATABASE) {
            command.setEngine(engineOf(target.engine()))
                    .setDatabaseName(target.databaseName());
        }
        return command.build();
    }

    /**
     * The same four numbers {@link RetentionPolicy} applies panel-side.
     *
     * <p>{@code max_total_bytes} stays zero. It is a per-subject ceiling at the
     * destination, and the limit this platform actually has is {@code BACKUP_BYTES} across
     * a whole organization - enforcing an org-wide figure per subject would refuse a
     * customer with one large volume and let through one with twenty small ones. The
     * organization's ceiling is checked by {@code QuotaGuard} before a run is started.
     */
    static RetentionRule ruleOf(RetentionPolicy retention) {
        return RetentionRule.newBuilder()
                .setKeepLast(retention.keepLast())
                .setKeepDaily(retention.keepDaily())
                .setKeepWeekly(retention.keepWeekly())
                .setKeepMonthly(retention.keepMonthly())
                .setMaxTotalBytes(0)
                .build();
    }

    /** The destination as the wire carries it, credentials included. */
    static lhqm.furimeo.wisper.proto.v1.BackupDestination destinationOf(
            DestinationCredentials credentials) {
        lhqm.furimeo.wisper.proto.v1.BackupDestination.Builder destination =
                lhqm.furimeo.wisper.proto.v1.BackupDestination.newBuilder();
        if (credentials.kind() == DestinationKind.LOCAL) {
            return destination
                    .setKind(lhqm.furimeo.wisper.proto.v1.DestinationKind.DESTINATION_KIND_LOCAL)
                    .setLocalPrefix(credentials.localPrefix())
                    .build();
        }
        return destination
                .setKind(lhqm.furimeo.wisper.proto.v1.DestinationKind.DESTINATION_KIND_S3)
                .setS3(S3Destination.newBuilder()
                        .setEndpoint(credentials.endpoint())
                        .setRegion(credentials.region())
                        .setBucket(credentials.bucket())
                        .setPrefix(credentials.prefix())
                        .setAccessKeyId(credentials.accessKeyId())
                        .setSecretAccessKey(credentials.secretAccessKey())
                        .setPathStyle(credentials.pathStyle())
                        .setServerSideEncryption(credentials.serverSideEncryption()))
                .build();
    }

    static lhqm.furimeo.wisper.proto.v1.BackupTargetKind kindOf(BackupTargetKind kind) {
        return kind == BackupTargetKind.VOLUME
                ? lhqm.furimeo.wisper.proto.v1.BackupTargetKind.BACKUP_TARGET_KIND_VOLUME
                : lhqm.furimeo.wisper.proto.v1.BackupTargetKind.BACKUP_TARGET_KIND_DATABASE;
    }

    /**
     * The engine, refusing to guess.
     *
     * <p>{@code database_engine_engine_known} allows exactly two values, so anything else
     * is a row that was edited by hand. Sending {@code UNSPECIFIED} would have the node
     * pick a dump tool at random, which produces an archive that restores into nothing.
     */
    static DatabaseEngine engineOf(String engine) {
        return switch (engine == null ? "" : engine) {
            case "POSTGRES" -> DatabaseEngine.DATABASE_ENGINE_POSTGRES;
            case "MYSQL" -> DatabaseEngine.DATABASE_ENGINE_MYSQL;
            default -> throw new IllegalStateException(
                    "\"" + engine + "\" is not an engine this panel knows how to dump");
        };
    }
}
