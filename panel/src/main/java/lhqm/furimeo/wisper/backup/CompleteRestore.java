package lhqm.furimeo.wisper.backup;

import java.time.Instant;
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
import lhqm.furimeo.wisper.proto.v1.RestoreCompleted;

/**
 * Takes a restore's outcome and closes the run.
 *
 * <p>{@code grpc} calls {@link #accept} for a {@code RestoreCompleted} frame
 * (panel-ports.md §3), and {@link RunRestore} calls the same method when the
 * {@code CommandResult} it was waiting on carries one. Either can arrive first, so this is
 * idempotent: a run that is already terminal is left alone.
 *
 * <p>Closing the run matters more here than it does for a backup. The two partial unique
 * indexes on {@code restore_run} hold the target against a second attempt while a run is in
 * flight, so a run left {@code RUNNING} would make the volume permanently un-restorable.
 * That is why {@link #failed} exists as a public entry point and why
 * {@link FailAbandonedRuns} sweeps for the case where nothing reached either of them.
 *
 * <p>Every outcome is audited (design §8.3: restoring is a button and is audited), with the
 * system as the actor. The person who pressed the button is already on the row, in
 * {@code requested_by_account_id}, and was recorded by {@link RestoreFromPoint} when they
 * pressed it; this entry is about what happened afterwards, minutes later, with nobody
 * watching.
 */
@Component
public class CompleteRestore {

    private static final Logger log = LoggerFactory.getLogger(CompleteRestore.class);

    private final RestoreRunRepository runs;
    private final RestorePointRepository points;
    private final BackupSettings settings;
    private final AuditTrail audit;

    public CompleteRestore(RestoreRunRepository runs, RestorePointRepository points,
                           BackupSettings settings, AuditTrail audit) {
        this.runs = runs;
        this.points = points;
        this.settings = settings;
        this.audit = audit;
    }

    /** Records what the node did. */
    @Transactional
    public void accept(UUID restoreRunId, RestoreCompleted result) {
        RestoreRun run = runs.findById(restoreRunId).orElse(null);
        if (run == null) {
            log.debug("Restore result for {}, which no longer exists", restoreRunId);
            return;
        }
        if (run.state().isTerminal()) {
            log.debug("Ignoring a restore result for {}, which is {}", restoreRunId, run.state());
            return;
        }

        Instant finishedAt = instantOf(result.hasFinishedAt() ? result.getFinishedAt() : null);
        if (!result.getSuccess()) {
            close(run, blankToDefault(result.getDetail(), "The restore failed on the node."),
                    finishedAt);
            return;
        }

        StringBuilder line = new StringBuilder("Restored ")
                .append(result.getBytesRestored()).append(" bytes");
        if (!result.getRestoredTo().isBlank()) {
            line.append(" to ").append(result.getRestoredTo());
        }
        if (result.getWorkloadRestarted()) {
            line.append("; the workload was started again");
        }
        line.append('.');
        if (!result.getDetail().isBlank()) {
            line.append(' ').append(result.getDetail());
        }

        RestoreRun stored = runs.save(run
                .withLogLine(line.toString(), settings.restoreLogLimit())
                .succeeded(Math.max(0L, result.getBytesRestored()), finishedAt));
        RestorePoint point = points.findById(stored.restorePointId()).orElse(null);
        log.info("Restore {} finished: {}", stored.id(), line);
        audit.record(AuditEntry.succeeded(actor(), "backup.restore", targetOf(stored, point),
                point == null ? null : point.organizationId(), line.toString()));
    }

    /**
     * Ends a restore that will not finish: the node refused it, the stream died, the
     * command ran past its deadline, or the safety snapshot before it failed.
     */
    @Transactional
    public void failed(UUID restoreRunId, String reason) {
        RestoreRun run = runs.findById(restoreRunId).orElse(null);
        if (run == null || run.state().isTerminal()) {
            return;
        }
        close(run, reason, Instant.now());
    }

    /**
     * One more line of progress on a run that is still going.
     *
     * <p>The restore page reads {@code restore_run.log}, so this is what turns an
     * asynchronous button into a screen that says something while the customer waits -
     * which is the difference between this and the blank frame the predecessor shipped.
     */
    @Transactional
    public void note(UUID restoreRunId, String line) {
        RestoreRun run = runs.findById(restoreRunId).orElse(null);
        if (run == null || run.state().isTerminal()) {
            return;
        }
        runs.save(run.withLogLine(line, settings.restoreLogLimit()));
    }

    private void close(RestoreRun run, String reason, Instant finishedAt) {
        RestoreRun stored = runs.save(run
                .withLogLine(reason, settings.restoreLogLimit())
                .failed(reason, finishedAt));
        RestorePoint point = points.findById(stored.restorePointId()).orElse(null);
        log.warn("Restore {} failed: {}", stored.id(), reason);
        audit.record(AuditEntry.failed(actor(), "backup.restore", targetOf(stored, point),
                point == null ? null : point.organizationId(), reason));
    }

    /**
     * The audit target names the run, labelled with what was being restored.
     *
     * <p>The snapshot can be null: it is {@code ON DELETE CASCADE} from the run's side, so
     * only a race with a tenant deletion gets here, and the trail is still worth writing.
     */
    private static AuditTarget targetOf(RestoreRun run, RestorePoint point) {
        return AuditTarget.of("restore_run", run.id(), point == null ? "" : point.targetLabel());
    }

    private static AuditActor actor() {
        return AuditActor.system("restore");
    }

    private static Instant instantOf(Timestamp timestamp) {
        return timestamp == null
                ? Instant.now()
                : Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
