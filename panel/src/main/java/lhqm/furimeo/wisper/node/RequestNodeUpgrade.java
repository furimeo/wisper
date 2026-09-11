package lhqm.furimeo.wisper.node;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.proto.v1.CommandResult;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;
import lhqm.furimeo.wisper.proto.v1.UpgradeNode;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Tells a node to replace its own binary (design §7.5).
 *
 * <p>Safe to do at any time, and that is a property of the architecture rather than of
 * this code: sasayaki is only a control plane, and a customer's containers are children of
 * Docker, not of the daemon. Restarting it touches no workload. The node reads its SQLite
 * back, reconciles, confirms there is no drift, and reports the new version.
 *
 * <p>The command carries a URL and a SHA-256, and the node verifies the checksum before it
 * replaces anything. It keeps the previous binary and rolls back to it if the new one does
 * not come back, which arrives here as an {@code UpgradeResult} with {@code rolled_back}
 * set - reported rather than inferred from a version that failed to change, so the panel
 * can say plainly that the upgrade failed and the node is fine.
 *
 * <p>The architecture comes out of the stored doctor report. {@code node_status} has no
 * column for it, and it does not need one: a machine's architecture does not change
 * without the machine being replaced, and a replaced machine re-enrols.
 */
@Component
public class RequestNodeUpgrade {

    private static final Logger log = LoggerFactory.getLogger(RequestNodeUpgrade.class);

    /**
     * Long enough for a slow node to fetch thirty megabytes, verify it, restart and
     * reconcile; short enough that a page is not held open all afternoon. A timeout is not
     * a failure - the result arrives on the control stream and
     * {@link CompleteUpgrade} records it whenever it does.
     */
    private static final Duration ANSWER_WINDOW = Duration.ofMinutes(5);

    private final NodeRepository nodes;
    private final NodeStatusRepository statuses;
    private final NodeConnections connections;
    private final ReadAgentReleases releases;
    private final ReadStoredDoctorReport doctorReports;
    private final CompleteUpgrade completeUpgrade;
    private final AuditTrail audit;

    public RequestNodeUpgrade(NodeRepository nodes, NodeStatusRepository statuses,
                              NodeConnections connections, ReadAgentReleases releases,
                              ReadStoredDoctorReport doctorReports,
                              CompleteUpgrade completeUpgrade, AuditTrail audit) {
        this.nodes = nodes;
        this.statuses = statuses;
        this.connections = connections;
        this.releases = releases;
        this.doctorReports = doctorReports;
        this.completeUpgrade = completeUpgrade;
        this.audit = audit;
    }

    /**
     * @param panelBaseUrl the panel's own base URL as the operator's browser reached it,
     *                     so the node is given a URL that resolves from where it is - the
     *                     panel is behind a tunnel and cannot work that out on its own
     * @return the result, or empty when the node has not answered yet
     * @throws NotFoundException if there is no such node
     * @throws RequestRejected   when there is nothing to upgrade to, the node is not
     *                           connected, or the panel does not know its architecture
     */
    public Optional<String> request(AuditActor actor, UUID nodeId, String panelBaseUrl) {
        Node node = nodes.findById(nodeId).orElseThrow(() -> NotFoundException.of("node", nodeId));
        if (!connections.isConnected(nodeId)) {
            throw new RequestRejected(null, node.name() + " has no control stream open, so it "
                    + "cannot be told to upgrade.");
        }
        NodeStatus status = statuses.findRow(nodeId).orElseThrow(() -> new RequestRejected(null,
                node.name() + " has never reported anything about itself yet."));

        String architecture = doctorReports.architectureIn(status.doctorReport())
                .orElseThrow(() -> new RequestRejected(null, "The panel does not know what "
                        + "architecture " + node.name() + " is. It reports one in its preflight "
                        + "report on every reconnect; wait for the next one."));
        AgentRelease target = releases.newestFor(architecture)
                .orElseThrow(() -> new RequestRejected(null, "No sasayaki binary has been "
                        + "published for linux-" + architecture + "."));
        if (target.version().equals(status.agentVersion())) {
            throw new RequestRejected(null, node.name() + " is already running "
                    + target.version() + ".");
        }

        audit.record(AuditEntry.succeeded(actor, "node.upgrade",
                AuditTarget.of("node", nodeId, node.name()), null,
                "Upgrade from " + status.agentVersion() + " to " + target.version()
                        + " (sha256 " + target.sha256() + ")"));

        PanelMessage.Builder command = PanelMessage.newBuilder()
                .setUpgrade(UpgradeNode.newBuilder()
                        .setTargetVersion(target.version())
                        .setDownloadUrl(target.downloadUrl(panelBaseUrl))
                        .setSha256(target.sha256()));
        CompletableFuture<CommandResult> answer = connections.call(nodeId, command)
                .orTimeout(ANSWER_WINDOW.toSeconds(), TimeUnit.SECONDS);

        try {
            CommandResult result = answer.join();
            completeUpgrade.accept(nodeId, result.getUpgrade());
            return Optional.of(result.getUpgrade().getRolledBack()
                    ? node.name() + " rolled back to " + result.getUpgrade().getPreviousVersion()
                            + ": " + result.getUpgrade().getDetail()
                    : node.name() + " is now running " + result.getUpgrade().getNewVersion()
                            + ".");
        } catch (CompletionException failed) {
            if (failed.getCause() instanceof TimeoutException
                    || failed.getCause() instanceof NodeOffline) {
                // Both are expected here: the node restarts as part of the upgrade, which
                // ends the very stream the answer would have come back on.
                log.info("Node {} has not confirmed its upgrade yet; it restarts as part of "
                        + "one, so the result arrives on the next control stream", node.name());
                return Optional.empty();
            }
            throw failed;
        }
    }
}
