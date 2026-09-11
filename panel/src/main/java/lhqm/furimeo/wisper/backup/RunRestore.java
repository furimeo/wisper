package lhqm.furimeo.wisper.backup;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lhqm.furimeo.wisper.node.NodeConnections;
import lhqm.furimeo.wisper.proto.v1.CommandResult;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;
import lhqm.furimeo.wisper.proto.v1.RestoreBackup;
import lhqm.furimeo.wisper.proto.v1.RunBackup;

/**
 * The body of the {@code restore-run} job: take a safety snapshot if anything is going to
 * be overwritten, then put the chosen snapshot back.
 *
 * <h2>The order is the feature</h2>
 *
 * <p>An in-place restore takes a {@code PRE_RESTORE} snapshot <strong>and waits for it to
 * succeed</strong> before the overwrite is even sent. Restoring the wrong snapshot is a
 * mistake people make while already under pressure, and this is what makes that mistake
 * undoable. A safety snapshot that fails aborts the restore: the customer keeps their data
 * and is told why nothing happened, which is the only acceptable outcome when the
 * alternative is an irreversible overwrite.
 *
 * <p>The two commands are chained on the futures rather than by blocking, so a restore that
 * takes an hour does not hold a db-scheduler worker for an hour. The progress the customer
 * watches comes from {@code restore_run}, not from this thread being alive.
 *
 * <p>Nothing here is logged with a command in it: both carry the destination's secret
 * access key, and protobuf's {@code toString} prints every field.
 */
@Component
public class RunRestore {

    private static final Logger log = LoggerFactory.getLogger(RunRestore.class);

    private static final long PANEL_TIMEOUT_SLACK_SECONDS = 120;

    /**
     * The retention rule sent with a safety snapshot whose policy has been deleted.
     *
     * <p>{@code RetentionRule} has no "prune nothing" flag, and a rule the node reads as
     * "keep one" would have it delete every other archive of that subject at the
     * destination - which, for a subject whose policy is gone, is every archive the
     * customer has left. A keep-last of {@code Integer.MAX_VALUE} is how "do not prune" is
     * said in this message. Where the policy still exists, its own rule is sent instead, so
     * the node and the panel prune the same set.
     */
    private static final RetentionPolicy KEEP_EVERYTHING =
            new RetentionPolicy(Integer.MAX_VALUE, 0, 0, 0, ZoneId.of("UTC"));

    private final TransactionTemplate transactions;
    private final RestoreRunRepository runs;
    private final RestorePointRepository points;
    private final BackupRepository backups;
    private final BackupDestinationRepository destinations;
    private final ResolveBackupTarget targets;
    private final ReadDestinationCredentials credentials;
    private final RecordRestorePoint restorePoints;
    private final ComposeRunBackup backupCommands;
    private final ComposeRestoreBackup restoreCommands;
    private final NodeConnections nodes;
    private final CompleteBackup completeBackup;
    private final CompleteRestore completeRestore;
    private final BackupSettings settings;

    public RunRestore(PlatformTransactionManager transactionManager, RestoreRunRepository runs,
                      RestorePointRepository points, BackupRepository backups,
                      BackupDestinationRepository destinations, ResolveBackupTarget targets,
                      ReadDestinationCredentials credentials, RecordRestorePoint restorePoints,
                      ComposeRunBackup backupCommands, ComposeRestoreBackup restoreCommands,
                      NodeConnections nodes, CompleteBackup completeBackup,
                      CompleteRestore completeRestore, BackupSettings settings) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.runs = runs;
        this.points = points;
        this.backups = backups;
        this.destinations = destinations;
        this.targets = targets;
        this.credentials = credentials;
        this.restorePoints = restorePoints;
        this.backupCommands = backupCommands;
        this.restoreCommands = restoreCommands;
        this.nodes = nodes;
        this.completeBackup = completeBackup;
        this.completeRestore = completeRestore;
        this.settings = settings;
    }

    /** Runs one queued restore. Called by db-scheduler; see {@link BackupTasks}. */
    public void run(RestoreJob job) {
        Plan plan = transactions.execute(status -> prepare(job.restoreRunId()));
        if (plan == null) {
            return;
        }
        if (plan.safetyCommand() == null) {
            dispatchRestore(plan);
            return;
        }
        takeSafetySnapshotFirst(plan);
    }

    /**
     * Everything decided and written before a node is asked.
     *
     * <p>Returns null when there is nothing to send, having cancelled the run with a reason
     * the page can show. The target is resolved <em>now</em>: a service migrated since the
     * snapshot was taken restores onto the machine that runs it today, not onto the one
     * that took the copy.
     */
    private Plan prepare(UUID runId) {
        RestoreRun run = runs.findById(runId).orElse(null);
        if (run == null) {
            log.debug("Restore {} is gone; nothing to run", runId);
            return null;
        }
        if (run.state() != RestoreState.QUEUED) {
            log.debug("Restore {} is {} and no longer queued", runId, run.state());
            return null;
        }

        RestorePoint point = points.findById(run.restorePointId()).orElse(null);
        if (point == null || !point.isRestorable()) {
            return abandon(run, "The snapshot this restore reads from is no longer available.");
        }
        BackupTargetRef target = targets.forSnapshot(point).orElse(null);
        if (target == null) {
            return abandon(run, "The " + point.targetKind().label().toLowerCase(Locale.ROOT)
                    + " this snapshot belongs to has been deleted.");
        }
        if (!target.isPlaced()) {
            return abandon(run, "No node is holding " + target.label() + " at the moment.");
        }
        BackupDestination destination = destinations.findById(point.destinationId()).orElse(null);
        if (destination == null || !destination.enabled()) {
            return abandon(run, "The destination this snapshot lives in is switched off or has "
                    + "been removed.");
        }

        DestinationCredentials secrets;
        try {
            secrets = credentials.of(destination);
        } catch (RuntimeException unreadable) {
            return abandon(run, "The credentials for \"" + destination.name()
                    + "\" could not be read: " + reasonOf(unreadable));
        }

        Instant now = Instant.now();
        RestoreRun started = runs.save(run.startedOn(target.nodeId(), now)
                .withLogLine("Node " + target.nodeId() + " is holding " + target.label() + ".",
                        settings.restoreLogLimit()));

        RunBackup safetyCommand = null;
        UUID safetyPointId = null;
        if (run.mode().overwritesTheTarget()) {
            Backup policy = point.backupId() == null
                    ? null : backups.findById(point.backupId()).orElse(null);
            RestorePoint safety = restorePoints.of(policy, target, destination,
                    RestorePointTrigger.PRE_RESTORE, secrets.isEncryptedAtRest(), now);
            safetyPointId = safety.id();
            safetyCommand = backupCommands.forSnapshot(safety.id().toString(), target, secrets,
                    policy == null ? KEEP_EVERYTHING : RetentionPolicy.of(policy, settings));
            started = runs.save(started.protectedBy(safety.id())
                    .withLogLine("Taking a snapshot of the current data before overwriting it.",
                            settings.restoreLogLimit()));
        }

        RestoreBackup restoreCommand = restoreCommands.forRun(runId.toString(), point, target,
                secrets, started.mode());
        return new Plan(runId, target.nodeId(), safetyPointId, safetyCommand, restoreCommand);
    }

    private Plan abandon(RestoreRun run, String reason) {
        runs.save(run.cancelled(reason, Instant.now())
                .withLogLine(reason, settings.restoreLogLimit()));
        log.warn("Restore {} was abandoned: {}", run.id(), reason);
        return null;
    }

    /** Snapshot the current data, and only then overwrite it. */
    private void takeSafetySnapshotFirst(Plan plan) {
        call(plan.nodeId(), PanelMessage.newBuilder().setRunBackup(plan.safetyCommand()),
                settings.backupTimeoutSeconds())
                .whenComplete((result, error) -> {
                    if (safetySucceeded(plan, result, error)) {
                        dispatchRestore(plan);
                    }
                });
    }

    /**
     * Records the safety snapshot's outcome and says whether the restore may proceed.
     *
     * <p>The outcome goes through {@link CompleteBackup} exactly as any other snapshot's
     * does, so a safety snapshot appears in the customer's list and can itself be restored
     * from - which is the entire reason for taking it.
     */
    private boolean safetySucceeded(Plan plan, CommandResult result, Throwable error) {
        if (error != null) {
            String reason = "The snapshot taken before restoring did not finish: "
                    + reasonOf(error);
            completeBackup.failed(plan.safetyPointId(), reason);
            completeRestore.failed(plan.runId(), reason + " Nothing was overwritten.");
            return false;
        }
        if (!result.hasBackup()) {
            String reason = "The node did not report the snapshot it was asked to take before "
                    + "restoring.";
            completeBackup.failed(plan.safetyPointId(), reason);
            completeRestore.failed(plan.runId(), reason + " Nothing was overwritten.");
            return false;
        }
        completeBackup.accept(plan.safetyPointId(), result.getBackup());
        if (!result.getBackup().getSuccess()) {
            completeRestore.failed(plan.runId(), "The snapshot taken before restoring failed, so "
                    + "the restore was stopped. Nothing was overwritten.");
            return false;
        }
        completeRestore.note(plan.runId(), "Safety snapshot taken. Restoring.");
        return true;
    }

    private void dispatchRestore(Plan plan) {
        call(plan.nodeId(), PanelMessage.newBuilder().setRestoreBackup(plan.restoreCommand()),
                settings.restoreTimeoutSeconds())
                .whenComplete((result, error) -> settle(plan.runId(), result, error));
    }

    /**
     * One command, with a deadline, and with a node that dropped between the commit and the
     * send turned into a failed future rather than an exception on this thread. Both are
     * the same thing to the caller - the restore did not happen - and only one of them
     * needs a second code path.
     */
    private CompletableFuture<CommandResult> call(UUID nodeId, PanelMessage.Builder message,
                                                  long timeoutSeconds) {
        try {
            return nodes.call(nodeId, message)
                    .orTimeout(timeoutSeconds + PANEL_TIMEOUT_SLACK_SECONDS, TimeUnit.SECONDS);
        } catch (RuntimeException offline) {
            return CompletableFuture.failedFuture(offline);
        }
    }

    private void settle(UUID runId, CommandResult result, Throwable error) {
        if (error != null) {
            completeRestore.failed(runId, "The node did not report a result: " + reasonOf(error));
            return;
        }
        if (result.hasRestore()) {
            completeRestore.accept(runId, result.getRestore());
            return;
        }
        completeRestore.failed(runId, result.getOk()
                ? "The node acknowledged the restore but reported no result."
                : blankToDefault(result.getDetail(), "The node refused the restore."));
    }

    /**
     * The sentence to show for a failure.
     *
     * <p>A {@link java.util.concurrent.CompletionException} is unwrapped: its own message is
     * the cause's class name, which tells a customer nothing that the cause's message does
     * not tell them better.
     */
    private static String reasonOf(Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException
                && failure.getCause() != null ? failure.getCause() : failure;
        return blankToDefault(cause.getMessage(), cause.getClass().getSimpleName());
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * What {@link #prepare} decided, carried out of the transaction it decided it in.
     *
     * <p>Both commands hold a decrypted secret key. Never log an instance of this.
     */
    private record Plan(UUID runId, UUID nodeId, UUID safetyPointId, RunBackup safetyCommand,
                        RestoreBackup restoreCommand) {
    }
}
