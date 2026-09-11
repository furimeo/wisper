package lhqm.furimeo.wisper.backup;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.Timestamp;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.proto.v1.BackupCompleted;

/**
 * Takes a snapshot's outcome and closes the row it belongs to.
 *
 * <p>{@code grpc} calls {@link #accept} for a {@code BackupCompleted} frame
 * (panel-ports.md §3), and {@link StartBackupRun} calls the same method when the
 * {@code CommandResult} it was waiting on carries one. Both paths are real and either can
 * arrive first, so this is <strong>idempotent</strong>: the second caller finds a row that
 * is no longer running and returns without touching it.
 *
 * <p>The id it is called with is the {@code restore_point} id. One run produces one
 * snapshot, and the panel minted that id before the command left, which is what lets a
 * result be matched to a row rather than turned into one.
 *
 * <h2>Success needs a location</h2>
 *
 * <p>A node reporting success with no {@code location} is treated as a failure. The
 * {@code restore_point_available_is_locatable} CHECK would refuse the row anyway, and the
 * failure it would produce - a constraint violation inside a gRPC handler - is far less
 * useful than a row that says the archive could not be found. A snapshot nobody can locate
 * is not a snapshot; it is a line in a log claiming one exists.
 */
@Component
public class CompleteBackup {

    private static final Logger log = LoggerFactory.getLogger(CompleteBackup.class);

    private final RestorePointRepository points;
    private final BackupRepository backups;
    private final PruneByRetention pruning;
    private final BackupSettings settings;
    private final AuditTrail audit;

    public CompleteBackup(RestorePointRepository points, BackupRepository backups,
                          PruneByRetention pruning, BackupSettings settings, AuditTrail audit) {
        this.points = points;
        this.backups = backups;
        this.pruning = pruning;
        this.settings = settings;
        this.audit = audit;
    }

    /**
     * Records what the node produced.
     *
     * @param backupId the {@code restore_point} id the command carried
     */
    @Transactional
    public void accept(UUID backupId, BackupCompleted result) {
        RestorePoint point = points.findById(backupId).orElse(null);
        if (point == null) {
            log.debug("Backup result for {}, which no longer exists", backupId);
            return;
        }
        if (point.state() != RestorePointState.RUNNING) {
            log.debug("Ignoring a backup result for {}, which is {}", backupId, point.state());
            return;
        }

        Instant finishedAt = instantOf(result.hasFinishedAt() ? result.getFinishedAt() : null);
        if (!result.getSuccess()) {
            finish(point, describeFailure(result), finishedAt);
            return;
        }
        if (result.getLocation().isBlank()) {
            finish(point, "The node reported success but did not say where the archive is.",
                    finishedAt);
            return;
        }

        Backup policy = policyOf(point);
        RestorePoint stored = points.save(point.available(result.getLocation(),
                Math.max(0L, result.getSizeBytes()), emptyToNull(result.getSha256()), finishedAt,
                expiryOf(policy, finishedAt)));
        if (policy != null) {
            backups.save(policy.finished(BackupRunStatus.SUCCEEDED, null));
            pruning.forPolicy(policy);
        }

        log.info("Snapshot {} of {} finished: {} bytes, {} ms of quiesce{}", stored.id(),
                stored.targetLabel(), stored.sizeBytes(), result.getQuiesceMillis(),
                result.getVerified() ? ", verified" : "");
        audit.record(AuditEntry.succeeded(actor(), "backup.run",
                AuditTarget.of("restore_point", stored.id(), stored.targetLabel()),
                stored.organizationId(),
                stored.sizeBytes() + " bytes at " + stored.objectKey()
                        + (result.getVerified() ? ", verified" : "")
                        + (result.getPruned() > 0
                                ? ", " + result.getPruned() + " old archive(s) removed" : "")));
    }

    /**
     * Ends a snapshot that will not produce an archive: the node refused it, the stream
     * died, the command ran past its deadline.
     *
     * <p>Quiet about a row that has already finished. Both the panel's own timeout and a
     * late answer from the node can land here for the same snapshot, and the first to
     * arrive is the one that matters.
     */
    @Transactional
    public void failed(UUID restorePointId, String reason) {
        RestorePoint point = points.findById(restorePointId).orElse(null);
        if (point == null || point.state() != RestorePointState.RUNNING) {
            return;
        }
        finish(point, reason, Instant.now());
    }

    private void finish(RestorePoint point, String reason, Instant finishedAt) {
        RestorePoint stored = points.save(point.failed(reason, finishedAt));
        Backup policy = policyOf(point);
        if (policy != null) {
            backups.save(policy.finished(BackupRunStatus.FAILED, reason));
        }
        log.warn("Snapshot {} of {} failed: {}", stored.id(), stored.targetLabel(), reason);
        audit.record(AuditEntry.failed(actor(), "backup.run",
                AuditTarget.of("restore_point", stored.id(), stored.targetLabel()),
                stored.organizationId(), reason));
    }

    private Backup policyOf(RestorePoint point) {
        return point.backupId() == null ? null : backups.findById(point.backupId()).orElse(null);
    }

    /**
     * When this snapshot may be deleted.
     *
     * <p>A policy's own {@code retention_days}, or - for a safety snapshot, which belongs to
     * no policy - the platform's {@code wisper.backup.safety-retention}. Both are a clock
     * the sweep reads; the tiering in {@link RetentionPolicy} is the other half, and only
     * applies to a snapshot that has a policy to be one of a series in.
     */
    private Instant expiryOf(Backup policy, Instant finishedAt) {
        return policy == null
                ? finishedAt.plus(settings.safetyRetention())
                : finishedAt.plus(policy.retentionDays(), ChronoUnit.DAYS);
    }

    /** Nobody asked: a scheduled run, or a node answering minutes after a request ended. */
    private static AuditActor actor() {
        return AuditActor.system("backup");
    }

    /**
     * One sentence a person can act on, assembled from the two fields the node fills in on
     * a failure. A failure at {@code UPLOAD} is the customer's object store; one at
     * {@code QUIESCE} is their application.
     */
    private static String describeFailure(BackupCompleted result) {
        StringBuilder message = new StringBuilder("The backup failed");
        if (result.getFailedStageValue() != 0) {
            message.append(" during ").append(stageName(result.getFailedStage().name()));
        }
        message.append('.');
        if (!result.getDetail().isBlank()) {
            message.append(' ').append(result.getDetail());
        }
        return message.toString();
    }

    /** {@code BACKUP_STAGE_QUIESCE} is not a phrase anybody says out loud. */
    private static String stageName(String enumName) {
        String tail = enumName.startsWith("BACKUP_STAGE_")
                ? enumName.substring("BACKUP_STAGE_".length())
                : enumName;
        return tail.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static Instant instantOf(Timestamp timestamp) {
        return timestamp == null
                ? Instant.now()
                : Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
