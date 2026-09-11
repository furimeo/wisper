package lhqm.furimeo.wisper.node;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.proto.v1.NodeHello;
import lhqm.furimeo.wisper.sql.SqlTimestamp;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Accepts, or refuses, the first frame of a control stream.
 *
 * <p>{@code NodeHello} is where a stream becomes usable. Everything that has to be settled
 * before a command can be dispatched is settled here, in this order:
 *
 * <ol>
 * <li>the protocol version - refused without negotiating down, because a partly-understood
 *     control channel is worse than none (design §7.5);</li>
 * <li>whether this node is one the panel still talks to at all;</li>
 * <li>whether a second machine is holding the same credential (design §7.3);</li>
 * <li>the machine's current facts and preflight report, which are refreshed on every
 *     reconnect and not only at enrolment;</li>
 * <li>whether the node is behind, in which case it is sent the whole spec.</li>
 * </ol>
 *
 * <p>A refused protocol version still writes {@code agent_version} and
 * {@code protocol_version}. That is the difference between a node's page saying "speaks
 * protocol 2, this panel speaks 1, upgrade the node" and it showing a machine that
 * mysteriously never connects - which is exactly the failure this handshake was added to
 * make legible.
 */
@Component
public class RecordHandshake {

    private static final Logger log = LoggerFactory.getLogger(RecordHandshake.class);

    private static final String CONNECTED_SQL = """
            INSERT INTO node_status (node_id, connection_state, last_connected_at,
                                     remote_address, agent_version, protocol_version,
                                     applied_generation, updated_at, version)
            VALUES (:nodeId, 'CONNECTED', :at, :remoteAddress, :agentVersion, :protocolVersion,
                    :appliedGeneration, :at, 0)
            ON CONFLICT (node_id) DO UPDATE
               SET connection_state   = 'CONNECTED',
                   last_connected_at  = EXCLUDED.last_connected_at,
                   remote_address     = EXCLUDED.remote_address,
                   agent_version      = EXCLUDED.agent_version,
                   protocol_version   = EXCLUDED.protocol_version,
                   applied_generation = GREATEST(node_status.applied_generation,
                                                 EXCLUDED.applied_generation),
                   reconcile_error    = NULL,
                   updated_at         = EXCLUDED.updated_at,
                   version            = node_status.version + 1
            """;

    private static final String VERSION_ONLY_SQL = """
            INSERT INTO node_status (node_id, agent_version, protocol_version, remote_address,
                                     updated_at, version)
            VALUES (:nodeId, :agentVersion, :protocolVersion, :remoteAddress, :at, 0)
            ON CONFLICT (node_id) DO UPDATE
               SET agent_version    = EXCLUDED.agent_version,
                   protocol_version = EXCLUDED.protocol_version,
                   remote_address   = EXCLUDED.remote_address,
                   updated_at       = EXCLUDED.updated_at,
                   version          = node_status.version + 1
            """;

    private final NodeRepository nodes;
    private final DetectClonedNode clones;
    private final DetectGenerationDrift drift;
    private final RecordMachineFacts machineFacts;
    private final JdbcClient jdbc;

    public RecordHandshake(NodeRepository nodes, DetectClonedNode clones,
                           DetectGenerationDrift drift, RecordMachineFacts machineFacts,
                           JdbcClient jdbc) {
        this.nodes = nodes;
        this.clones = clones;
        this.drift = drift;
        this.machineFacts = machineFacts;
        this.jdbc = jdbc;
    }

    /**
     * @throws HandshakeRefused  when the stream must be ended before anything is dispatched
     * @throws NotFoundException if the credential resolved to a node that has since gone
     */
    @Transactional
    public void accept(UUID nodeId, NodeHello hello, String remoteAddress) {
        Node node = nodes.findById(nodeId).orElseThrow(() -> NotFoundException.of("node", nodeId));
        Instant now = Instant.now();

        if (!NodeProtocol.canSpeak(hello.getProtocolVersion())) {
            recordVersionOnly(nodeId, hello, remoteAddress, now);
            log.warn("Refusing the control stream from {}: {}", node.name(),
                    NodeProtocol.mismatchMessage(hello.getProtocolVersion()));
            throw new HandshakeRefused(HandshakeRefused.Reason.PROTOCOL_UNSUPPORTED,
                    NodeProtocol.mismatchMessage(hello.getProtocolVersion()));
        }
        if (!node.lifecycle().acceptsControl()) {
            recordVersionOnly(nodeId, hello, remoteAddress, now);
            throw new HandshakeRefused(HandshakeRefused.Reason.NOT_ACCEPTING_CONTROL,
                    node.name() + " is " + node.lifecycle() + "; the panel is not publishing "
                            + "to it. Its containers are unaffected.");
        }
        if (clones.atHandshake(nodeId, node.name(), remoteAddress)) {
            throw new HandshakeRefused(HandshakeRefused.Reason.DUPLICATE_STREAM,
                    node.name() + " already has a control stream open from another address. "
                            + "Both have been stopped for an operator to look at.");
        }

        jdbc.sql(CONNECTED_SQL)
                .param("nodeId", nodeId)
                .param("at", SqlTimestamp.at(now))
                .param("remoteAddress", remoteAddress)
                .param("agentVersion", hello.getAgentVersion())
                .param("protocolVersion", hello.getProtocolVersion())
                .param("appliedGeneration", hello.getAppliedGeneration())
                .update();

        machineFacts.accept(nodeId, hello.getMachine(), hello.getDoctor(), now);

        log.info("Node {} connected from {} running {} ({}), applied generation {}{}",
                node.name(), remoteAddress, hello.getAgentVersion(), hello.getAgentCommit(),
                hello.getAppliedGeneration(), hello.getFreshStart() ? ", fresh start" : "");
    }

    /**
     * Sends the whole spec if the node is behind.
     *
     * <p>Deliberately <strong>not</strong> part of {@link #accept}, and the ordering is the
     * reason. {@code PublishNodeSpec} puts its frame on the wire after its transaction
     * commits, so a catch-up started inside the handshake would try to write to a stream
     * the caller has not registered yet, find the node offline, and swallow a spec the node
     * is waiting for. The caller registers the stream, sends {@code PanelHello}, and then
     * calls this - which also puts the two frames in the order {@code node.proto} says
     * they arrive in.
     *
     * <p>There is never a delta, so a node that was away for a week needs nothing special
     * (design §5.2).
     *
     * @return true when the node was suspended instead - it claims a generation this panel
     *         never published, which means two panels are driving it
     */
    @Transactional
    public boolean catchUp(UUID nodeId, NodeHello hello) {
        String name = nodes.findById(nodeId).map(Node::name).orElse(nodeId.toString());
        return drift.check(nodeId, name, hello.getAppliedGeneration(),
                hello.getFreshStart() ? "daemon started" : "reconnect");
    }

    /** What the panel is prepared to say about a node whose stream it is about to refuse. */
    private void recordVersionOnly(UUID nodeId, NodeHello hello, String remoteAddress,
                                   Instant at) {
        jdbc.sql(VERSION_ONLY_SQL)
                .param("nodeId", nodeId)
                .param("agentVersion", hello.getAgentVersion())
                .param("protocolVersion", hello.getProtocolVersion())
                .param("remoteAddress", remoteAddress)
                .param("at", SqlTimestamp.at(at))
                .update();
    }

    /**
     * The generation the {@code PanelHello} answering this handshake announces.
     *
     * <p>Read after {@link #accept} so it reflects anything the drift check published, and
     * read from {@code node_status} rather than remembered from the row that was loaded a
     * moment ago, which would be stale by exactly one publish.
     */
    @Transactional(readOnly = true)
    public long currentGeneration(UUID nodeId) {
        return nodes.desiredGenerationOf(nodeId).orElse(0L);
    }
}
