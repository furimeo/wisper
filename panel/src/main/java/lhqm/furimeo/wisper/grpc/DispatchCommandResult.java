package lhqm.furimeo.wisper.grpc;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.backup.CompleteBackup;
import lhqm.furimeo.wisper.backup.CompleteRestore;
import lhqm.furimeo.wisper.database.CompleteProvision;
import lhqm.furimeo.wisper.deploy.CompleteDeployment;
import lhqm.furimeo.wisper.node.CompleteDrain;
import lhqm.furimeo.wisper.node.CompleteUpgrade;
import lhqm.furimeo.wisper.proto.v1.CommandResult;

/**
 * Routes the typed outcome of a finished command to the package that owns the table it
 * belongs in.
 *
 * <p>This is the whole of {@code grpc}'s job for command results: it parses a frame and
 * hands the content on. It writes nothing itself, which is the rule that keeps six
 * packages' invariants in the six packages that know them (panel-ports.md §3).
 *
 * <p>Separate from {@link NodeControlStream} because the two change for different reasons.
 * The stream changes when the handshake or the lifecycle does; this changes every time a
 * new kind of command is added, which is a different person on a different day.
 *
 * <h2>Why dispatch happens even when somebody is waiting</h2>
 *
 * <p>A command's caller usually holds a future and could handle the outcome itself. It
 * must not be the only path: a build takes minutes, a restore takes longer, and the
 * request that started one is long gone by the time it finishes - or the panel restarted,
 * or the operator closed the tab. Persisting on arrival is what makes the result survive
 * anybody watching for it.
 *
 * <h2>Malformed ids do not end the stream</h2>
 *
 * <p>Every id here is a string on the wire. One that is not a UUID is logged and dropped:
 * the stream carries every other command for that node, and killing it over one bad frame
 * would turn a cosmetic bug on the node into an outage for the machine.
 */
@Component
public class DispatchCommandResult {

    private static final Logger log = LoggerFactory.getLogger(DispatchCommandResult.class);

    private final CompleteDrain completeDrain;
    private final CompleteUpgrade completeUpgrade;
    private final CompleteDeployment completeDeployment;
    private final CompleteBackup completeBackup;
    private final CompleteRestore completeRestore;
    private final CompleteProvision completeProvision;

    public DispatchCommandResult(CompleteDrain completeDrain, CompleteUpgrade completeUpgrade,
                                 CompleteDeployment completeDeployment,
                                 CompleteBackup completeBackup, CompleteRestore completeRestore,
                                 CompleteProvision completeProvision) {
        this.completeDrain = completeDrain;
        this.completeUpgrade = completeUpgrade;
        this.completeDeployment = completeDeployment;
        this.completeBackup = completeBackup;
        this.completeRestore = completeRestore;
        this.completeProvision = completeProvision;
    }

    /**
     * @param nodeId the node that answered, which is the subject for the two node-owned
     *               outcomes and the authorisation for the rest
     */
    public void accept(UUID nodeId, CommandResult result) {
        switch (result.getOutcomeCase()) {
            case DRAIN -> completeDrain.accept(nodeId, result.getDrain());
            case UPGRADE -> completeUpgrade.accept(nodeId, result.getUpgrade());
            case BUILD -> asUuid("build", result.getBuild().getBuildId())
                    .ifPresent(id -> completeDeployment.accept(id, result.getBuild()));
            case BACKUP -> asUuid("backup", result.getBackup().getBackupId())
                    .ifPresent(id -> completeBackup.accept(id, result.getBackup()));
            case RESTORE -> asUuid("restore", result.getRestore().getRestoreId())
                    .ifPresent(id -> completeRestore.accept(id, result.getRestore()));
            case DATABASE -> asUuid("database", result.getDatabase().getId())
                    .ifPresent(id -> completeProvision.accept(id, result.getDatabase()));
            // ApplySpec and the log subscriptions answer with ok and a sentence and
            // nothing else. There is no table for them; SpecApplied has its own frame.
            case OUTCOME_NOT_SET -> logPlainResult(nodeId, result);
        }
    }

    private static void logPlainResult(UUID nodeId, CommandResult result) {
        if (result.getOk()) {
            log.debug("Node {} finished command {}: {}", nodeId, result.getCommandId(),
                    result.getDetail());
        } else {
            log.warn("Node {} failed command {}: {}", nodeId, result.getCommandId(),
                    result.getDetail());
        }
    }

    private static Optional<UUID> asUuid(String what, String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException notAUuid) {
            log.error("A node reported a {} result for \"{}\", which is not an id this panel "
                    + "issued. Dropped.", what, value);
            return Optional.empty();
        }
    }
}
