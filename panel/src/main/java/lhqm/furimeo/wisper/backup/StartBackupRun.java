package lhqm.furimeo.wisper.backup;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lhqm.furimeo.wisper.node.NodeConnections;
import lhqm.furimeo.wisper.proto.v1.CommandResult;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;
import lhqm.furimeo.wisper.proto.v1.RunBackup;

/**
 * The body of the {@code backup-run} job: work out what to copy and where, write the
 * snapshot's row, and hand the command to the node that holds the data.
 *
 * <h2>Two phases, and the commit between them is the point</h2>
 *
 * <p>The {@code restore_point} row is written and <strong>committed</strong> before
 * {@code RunBackup} goes anywhere. A node on the same machine can answer in single-digit
 * milliseconds; if the answer arrived while the transaction was still open,
 * {@link CompleteBackup} would look for a row that does not exist yet and drop a result the
 * customer is waiting for. So the database work runs in an explicit
 * {@link TransactionTemplate} and the network work runs after it returns.
 *
 * <p>The job then finishes as soon as the command is on the wire. The snapshot takes
 * minutes on the node and no worker thread is sitting in it; the result comes back through
 * {@link CompleteBackup}, from whichever path reaches it first - the {@code CommandResult}
 * this call is waiting on, or the frame {@code grpc} routes there directly.
 *
 * <p>Nothing here is logged with the command in it. {@code RunBackup} carries the
 * destination's secret access key, and protobuf's {@code toString} prints every field.
 */
@Component
public class StartBackupRun {

    private static final Logger log = LoggerFactory.getLogger(StartBackupRun.class);

    /**
     * How much longer than the node's own deadline the panel waits.
     *
     * <p>The node is meant to give up and report a failure. This is the backstop for a node
     * that died instead of answering, and it has to be later than the node's deadline or
     * the panel would fail backups that were about to succeed.
     */
    private static final long PANEL_TIMEOUT_SLACK_SECONDS = 120;

    private final TransactionTemplate transactions;
    private final BackupRepository backups;
    private final BackupDestinationRepository destinations;
    private final ResolveBackupTarget targets;
    private final ReadDestinationCredentials credentials;
    private final RecordRestorePoint restorePoints;
    private final ComposeRunBackup commands;
    private final NodeConnections nodes;
    private final CompleteBackup completions;
    private final BackupSettings settings;

    public StartBackupRun(PlatformTransactionManager transactionManager,
                          BackupRepository backups, BackupDestinationRepository destinations,
                          ResolveBackupTarget targets, ReadDestinationCredentials credentials,
                          RecordRestorePoint restorePoints, ComposeRunBackup commands,
                          NodeConnections nodes, CompleteBackup completions,
                          BackupSettings settings) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.backups = backups;
        this.destinations = destinations;
        this.targets = targets;
        this.credentials = credentials;
        this.restorePoints = restorePoints;
        this.commands = commands;
        this.nodes = nodes;
        this.completions = completions;
        this.settings = settings;
    }

    /** Runs one queued backup. Called by db-scheduler; see {@link BackupTasks}. */
    public void run(BackupJob job) {
        Dispatch dispatch;
        try {
            dispatch = transactions.execute(status -> prepare(job));
        } catch (RuntimeException refused) {
            recordRefusal(job.backupId(), reasonOf(refused));
            return;
        }
        if (dispatch == null) {
            return;
        }
        try {
            nodes.call(dispatch.nodeId(),
                            PanelMessage.newBuilder().setRunBackup(dispatch.command()))
                    .orTimeout(settings.backupTimeoutSeconds() + PANEL_TIMEOUT_SLACK_SECONDS,
                            TimeUnit.SECONDS)
                    .whenComplete((result, error) ->
                            settle(dispatch.restorePointId(), result, error));
        } catch (RuntimeException unreachable) {
            // The node dropped between the commit and the send. The snapshot row exists and
            // has to be closed, or it says RUNNING for ever.
            completions.failed(dispatch.restorePointId(),
                    "The node could not be reached: " + reasonOf(unreachable));
        }
    }

    /**
     * Everything that has to be decided and written before a node is asked.
     *
     * <p>Returns null when there is nothing to send. Every such case is recorded on the
     * policy first, because "the backup did not run" with no reason next to it is what a
     * customer discovers three weeks later.
     */
    private Dispatch prepare(BackupJob job) {
        Backup policy = backups.findById(job.backupId()).orElse(null);
        if (policy == null) {
            log.debug("Backup policy {} is gone; nothing to run", job.backupId());
            return null;
        }
        if (!policy.enabled()) {
            log.debug("Backup policy {} is switched off", policy.id());
            return null;
        }

        BackupTargetRef target = targets.forPolicy(policy).orElse(null);
        if (target == null) {
            return refuse(policy, "The " + policy.targetKind().label().toLowerCase(Locale.ROOT)
                    + " this policy backs up no longer exists.");
        }
        String refusal = target.refusalReason();
        if (refusal != null) {
            return refuse(policy, refusal);
        }

        BackupDestination destination = destinations.findById(policy.destinationId()).orElse(null);
        if (destination == null || !destination.enabled()) {
            return refuse(policy, "The destination this policy pushes to is switched off or has "
                    + "been removed.");
        }

        DestinationCredentials secrets;
        try {
            secrets = credentials.of(destination);
        } catch (RuntimeException unreadable) {
            // A plaintext value in an encrypted column, or a key version nobody configured.
            // The message names the destination, never the value.
            return refuse(policy, "The credentials for \"" + destination.name()
                    + "\" could not be read: " + reasonOf(unreadable));
        }

        Instant now = Instant.now();
        // Not caught here, and that is the point. RecordRestorePoint is @Transactional, so a
        // refusal thrown out of it - out of restore points, or the organization is suspended -
        // has already marked this transaction rollback-only. Writing the reason at this point
        // would be discarded at commit, and the commit itself would then throw
        // UnexpectedRollbackException, which db-scheduler retries for ever with nothing on the
        // policy to say why. run() catches it and records the reason in a transaction of its
        // own.
        RestorePoint point = restorePoints.of(policy, target, destination, job.trigger(),
                secrets.isEncryptedAtRest(), now);

        backups.save(policy.started(now, policy.nextRunAt()));
        RunBackup command = commands.forSnapshot(point.id().toString(), target, secrets,
                RetentionPolicy.of(policy, settings));
        return new Dispatch(point.id(), target.nodeId(), command);
    }

    private Dispatch refuse(Backup policy, String reason) {
        backups.save(policy.started(Instant.now(), policy.nextRunAt())
                .finished(BackupRunStatus.FAILED, reason));
        log.warn("Backup policy {} did not run: {}", policy.id(), reason);
        return null;
    }

    /**
     * Writes the reason on the policy in a fresh transaction, for the refusals that came out
     * of {@link #prepare} as an exception rather than as a null.
     *
     * <p>The policy is read again rather than carried out of the failed attempt: that
     * transaction rolled back, so anything read in it is a value with no row behind it.
     */
    private void recordRefusal(UUID backupId, String reason) {
        transactions.executeWithoutResult(status -> backups.findById(backupId)
                .ifPresent(policy -> backups.save(policy
                        .started(Instant.now(), policy.nextRunAt())
                        .finished(BackupRunStatus.FAILED, reason))));
        log.warn("Backup policy {} did not run: {}", backupId, reason);
    }

    /** Turns whatever came back from the node into one call on {@link CompleteBackup}. */
    private void settle(UUID restorePointId, CommandResult result, Throwable error) {
        if (error != null) {
            completions.failed(restorePointId,
                    "The node did not report a result: " + reasonOf(error));
            return;
        }
        if (result.hasBackup()) {
            completions.accept(restorePointId, result.getBackup());
            return;
        }
        completions.failed(restorePointId, result.getOk()
                ? "The node acknowledged the backup but reported no result."
                : blankToDefault(result.getDetail(), "The node refused the backup."));
    }

    /**
     * The sentence to show for a failure, with a
     * {@link java.util.concurrent.CompletionException} unwrapped - its own message is the
     * cause's class name, which says less than the cause does.
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
     * <p>{@code command} holds a decrypted secret key. Never log an instance of this.
     */
    private record Dispatch(UUID restorePointId, UUID nodeId, RunBackup command) {
    }
}
