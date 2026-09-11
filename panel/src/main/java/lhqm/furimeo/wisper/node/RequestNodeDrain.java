package lhqm.furimeo.wisper.node;

import java.time.Duration;
import java.time.Instant;
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
import lhqm.furimeo.wisper.proto.v1.DrainNode;
import lhqm.furimeo.wisper.proto.v1.DrainReport;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Asks a node to stop taking work and move what can be moved (design §7.7).
 *
 * <p>Two calls in one, distinguished by {@code evacuateStateless}:
 *
 * <ul>
 * <li><strong>A survey.</strong> Changes nothing anywhere and answers "what would happen
 *     if I drained this". An operator about to take a machine away needs that answer
 *     before they commit, not after.</li>
 * <li><strong>A drain.</strong> Marks the record {@code DRAINING} - which stops new
 *     placements immediately, whether or not the node ever answers - and asks the node to
 *     stop the workloads that have no volume.</li>
 * </ul>
 *
 * <p>Anything with a volume is listed and never moved. Moving it means moving a customer's
 * data, and a migration nobody asked for is data loss with extra steps; the operator gets
 * the list and decides.
 *
 * <h2>Why there is no surrounding transaction</h2>
 *
 * <p>The lifecycle change has to be committed <em>before</em> the command goes out, and
 * the wait for an answer must not be holding a database connection for two minutes. Each
 * write here is its own short transaction - the repository provides one, and the audit
 * trail writes in {@code REQUIRES_NEW} regardless - which is also the behaviour an
 * operator wants: a node that is unreachable is exactly the node they most want to stop
 * scheduling onto, and making that intent depend on the machine answering gets it
 * backwards.
 */
@Component
public class RequestNodeDrain {

    private static final Logger log = LoggerFactory.getLogger(RequestNodeDrain.class);

    /**
     * How long the operator's page waits. Stopping containers that have no volume is
     * quick; if it is not, the honest answer is "it is running, the page will show the
     * report" rather than a request thread held open behind a machine that may have gone.
     */
    private static final Duration ANSWER_WINDOW = Duration.ofMinutes(2);

    private final NodeRepository nodes;
    private final NodeConnections connections;
    private final CompleteDrain completeDrain;
    private final AuditTrail audit;

    public RequestNodeDrain(NodeRepository nodes, NodeConnections connections,
                            CompleteDrain completeDrain, AuditTrail audit) {
        this.nodes = nodes;
        this.connections = connections;
        this.completeDrain = completeDrain;
        this.audit = audit;
    }

    /**
     * @param evacuateStateless true to actually stop the movable workloads, false to only
     *                          ask what would happen
     * @return the node's report, or empty when it did not answer inside
     *         {@link #ANSWER_WINDOW}. Empty is not a failure: the drain is recorded and
     *         the node finishes it whenever it can.
     * @throws NotFoundException if there is no such node
     * @throws RequestRejected   if the node has never enrolled, or has no open stream
     */
    public Optional<DrainReport> request(AuditActor actor, UUID nodeId, String reason,
                                         boolean evacuateStateless) {
        Node node = nodes.findById(nodeId).orElseThrow(() -> NotFoundException.of("node", nodeId));
        if (!node.isEnrolled()) {
            throw new RequestRejected(null, node.name() + " has never enrolled, so there is "
                    + "nothing on it to drain.");
        }
        if (!connections.isConnected(nodeId)) {
            throw new RequestRejected(null, node.name() + " has no control stream open, so it "
                    + "cannot be asked to drain. Its containers are still running.");
        }

        if (evacuateStateless) {
            nodes.save(node.draining(Instant.now()));
            audit.record(AuditEntry.succeeded(actor, "node.drain",
                    AuditTarget.of("node", nodeId, node.name()), null,
                    "Drain requested: " + (reason == null || reason.isBlank()
                            ? "no reason given" : reason)));
        }

        PanelMessage.Builder command = PanelMessage.newBuilder()
                .setDrain(DrainNode.newBuilder()
                        .setEvacuateStateless(evacuateStateless)
                        .setReason(reason == null ? "" : reason));
        CompletableFuture<CommandResult> answer = connections.call(nodeId, command)
                .orTimeout(ANSWER_WINDOW.toSeconds(), TimeUnit.SECONDS);

        try {
            CommandResult result = answer.join();
            if (!result.getOk()) {
                throw new RequestRejected(null, node.name() + " refused to drain: "
                        + result.getDetail());
            }
            DrainReport report = result.getDrain();
            if (evacuateStateless) {
                completeDrain.accept(nodeId, report);
            }
            return Optional.of(report);
        } catch (CompletionException failed) {
            if (failed.getCause() instanceof TimeoutException) {
                log.info("Node {} has not finished draining yet; its report will arrive on the "
                        + "control stream", node.name());
                return Optional.empty();
            }
            if (failed.getCause() instanceof NodeOffline) {
                throw new RequestRejected(null, node.name() + " dropped its control stream "
                        + "before answering. The drain is recorded and finishes when it "
                        + "reconnects.");
            }
            throw failed;
        }
    }
}
