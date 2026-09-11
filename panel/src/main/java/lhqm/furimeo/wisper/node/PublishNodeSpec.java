package lhqm.furimeo.wisper.node;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lhqm.furimeo.wisper.proto.v1.ApplySpec;
import lhqm.furimeo.wisper.proto.v1.NodeSpec;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;
import lhqm.furimeo.wisper.proto.v1.SpecApplied;
import lhqm.furimeo.wisper.sql.SqlTimestamp;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Publishes desired state to a node: bump the counter, build the whole document, put it on
 * the stream.
 *
 * <p>Every write anywhere in the panel that changes what a node must be running ends with
 * a call to {@link #forService}. That is the single seam, and it is why no other package
 * has to know which node a service is on or that a generation exists (panel-ports.md §4).
 *
 * <h2>Three things that are easy to get wrong and are handled here</h2>
 *
 * <ol>
 * <li><strong>The counter is taken in one statement.</strong> A read-then-write increment
 *     lets two publishers landing together read the same value and send two different
 *     specs under one generation, and the node would converge on whichever arrived second
 *     while reporting the generation both claimed.</li>
 * <li><strong>The frame goes out after the transaction commits.</strong> Sending inside it
 *     means a rollback leaves the node holding a generation the panel no longer believes
 *     it published - and the next status report then looks like two panels driving one
 *     machine, which suspends a perfectly healthy node.</li>
 * <li><strong>An undeliverable spec is not a lost spec.</strong> {@link NodeOffline} is
 *     swallowed. The node reconnects, the handshake finds it behind, and the whole spec is
 *     sent again; there is no queue and nothing to drain (design §5.2).</li>
 * </ol>
 */
@Component
public class PublishNodeSpec {

    private static final Logger log = LoggerFactory.getLogger(PublishNodeSpec.class);

    private static final String MARK_APPLIED_SQL = """
            INSERT INTO node_status (node_id, applied_generation, last_reconcile_at,
                                     reconcile_error, updated_at, version)
            VALUES (:nodeId, :generation, :at, :error, :at, 0)
            ON CONFLICT (node_id) DO UPDATE
               SET applied_generation = GREATEST(node_status.applied_generation,
                                                 EXCLUDED.applied_generation),
                   last_reconcile_at  = EXCLUDED.last_reconcile_at,
                   reconcile_error    = EXCLUDED.reconcile_error,
                   updated_at         = EXCLUDED.updated_at,
                   version            = node_status.version + 1
            """;

    private final NodeRepository nodes;
    private final NodeSpecSource specSource;
    private final NodeConnections connections;
    private final JdbcClient jdbc;

    public PublishNodeSpec(NodeRepository nodes, NodeSpecSource specSource,
                           NodeConnections connections, JdbcClient jdbc) {
        this.nodes = nodes;
        this.specSource = specSource;
        this.connections = connections;
        this.jdbc = jdbc;
    }

    /**
     * Publishes to one node.
     *
     * <p>Does nothing for a node that is suspended or retired: suspension means the panel
     * has stopped being an author of that machine's state, and quietly continuing to
     * publish would make it one again (design §7.3).
     *
     * @param reason what the daemon writes in its log - {@code "deployment 412"},
     *               {@code "reconnect"}, {@code "node resumed"}. A routine resend and a
     *               real change look identical without it.
     * @throws NotFoundException if there is no such node
     */
    @Transactional
    public void toNode(UUID nodeId, String reason) {
        Node node = nodes.findById(nodeId).orElseThrow(() -> NotFoundException.of("node", nodeId));
        if (!node.lifecycle().acceptsControl()) {
            log.debug("Not publishing to {}: it is {}", node.name(), node.lifecycle());
            return;
        }
        Instant at = Instant.now();
        Long generation = nodes.bumpGeneration(nodeId, at);
        if (generation == null) {
            throw NotFoundException.of("node", nodeId);
        }
        NodeSpec spec = specSource.buildSpec(nodeId, generation, at);
        PanelMessage.Builder message = PanelMessage.newBuilder()
                .setApplySpec(ApplySpec.newBuilder().setSpec(spec).setReason(reason));
        afterCommit(() -> deliver(nodeId, node.name(), generation, message, reason));
    }

    /**
     * Publishes to every node a service touches.
     *
     * <p>At most two in v1: a placement being migrated has a {@code DRAINING} row and an
     * {@code ACTIVE} one at the same time, and both machines have to be told. Empty for a
     * service nobody has placed yet, which is not an error - the scheduler publishes when
     * it places it.
     */
    @Transactional
    public void forService(UUID serviceId, String reason) {
        List<UUID> hosts = specSource.nodesHosting(serviceId);
        for (UUID nodeId : hosts) {
            toNode(nodeId, reason);
        }
    }

    /**
     * Records the node's immediate answer to an {@code ApplySpec}.
     *
     * <p>Deliberately thin, because {@code SpecApplied} is thin: it means received,
     * understood and stored, not converged. What happened to each workload arrives in the
     * next status batch.
     *
     * <p>{@code GREATEST} in the statement is not decoration. Two acknowledgements can
     * cross on a reconnect, and applying the older one would make the node look like it
     * had gone backwards - which the next drift check would read as the node being behind
     * and answer by republishing, forever.
     */
    @Transactional
    public void markApplied(UUID nodeId, SpecApplied applied) {
        Instant at = applied.hasReceivedAt()
                ? Instant.ofEpochSecond(applied.getReceivedAt().getSeconds(),
                        applied.getReceivedAt().getNanos())
                : Instant.now();
        if (!applied.getAccepted()) {
            log.warn("Node {} refused generation {}: {}", nodeId, applied.getGeneration(),
                    applied.getRejectedReason());
        }
        jdbc.sql(MARK_APPLIED_SQL)
                .param("nodeId", nodeId)
                // A refused spec was not applied, so the column must not move. -1 loses to
                // whatever is already there under GREATEST, which is exactly the intent.
                .param("generation", applied.getAccepted()
                        ? applied.getGeneration() : NodeStatus.NEVER_REPORTED)
                .param("at", SqlTimestamp.at(at))
                .param("error", applied.getAccepted() ? null : applied.getRejectedReason())
                .update();
    }

    private void deliver(UUID nodeId, String nodeName, long generation,
                         PanelMessage.Builder message, String reason) {
        try {
            connections.send(nodeId, message);
            log.debug("Published generation {} to {} ({})", generation, nodeName, reason);
        } catch (NodeOffline offline) {
            // Not a failure. The node gets the whole spec the moment it reconnects.
            log.debug("Node {} is away; generation {} will go out on reconnect", nodeName,
                    generation);
        }
    }

    /**
     * Runs the send once the surrounding transaction has committed, or immediately when
     * there is none to wait for.
     */
    private static void afterCommit(Runnable send) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            send.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send.run();
            }
        });
    }
}
