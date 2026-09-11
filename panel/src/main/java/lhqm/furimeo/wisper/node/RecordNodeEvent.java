package lhqm.furimeo.wisper.node;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.proto.v1.EventSeverity;
import lhqm.furimeo.wisper.proto.v1.NodeEvent;

/**
 * Handles the things a node reports that nobody asked about.
 *
 * <p>Heartbeats carry steady state; a {@code NodeEvent} carries a transition between two
 * of them. A certificate issued, a container the kernel killed, an upgrade that rolled
 * back and a Docker socket that stopped answering are all invisible in either neighbouring
 * heartbeat, and they are precisely the moments an operator later needs to find.
 *
 * <p>Three kinds change a column, and the rest are a log line with the right severity on
 * it. That is not a shrug: the objects those events are about have their own status paths -
 * a crash-looping workload arrives in the next {@code StatusBatch} as a
 * {@code WorkloadStatus} that {@code placement} writes, and a certificate arrives as a
 * {@code RouteStatus} that {@code domain} writes. Writing them twice, from two directions,
 * is exactly the shape of bug the ownership rule exists to prevent (AGENTS.md §4.2).
 */
@Component
public class RecordNodeEvent {

    private static final Logger log = LoggerFactory.getLogger(RecordNodeEvent.class);

    private static final String DOCKER_SQL = """
            INSERT INTO node_status (node_id, docker_healthy, reconcile_error, updated_at,
                                     version)
            VALUES (:nodeId, :healthy, :detail, :at, 0)
            ON CONFLICT (node_id) DO UPDATE
               SET docker_healthy  = EXCLUDED.docker_healthy,
                   reconcile_error = EXCLUDED.reconcile_error,
                   updated_at      = EXCLUDED.updated_at,
                   version         = node_status.version + 1
            """;

    private final JdbcClient jdbc;
    private final NodeRepository nodes;
    private final AuditTrail audit;

    public RecordNodeEvent(JdbcClient jdbc, NodeRepository nodes, AuditTrail audit) {
        this.jdbc = jdbc;
        this.nodes = nodes;
        this.audit = audit;
    }

    @Transactional
    public void accept(UUID nodeId, NodeEvent event) {
        Instant at = event.hasAt()
                ? Instant.ofEpochSecond(event.getAt().getSeconds(), event.getAt().getNanos())
                : Instant.now();
        String name = nodes.findById(nodeId).map(Node::name).orElse(nodeId.toString());

        switch (event.getKind()) {
            case NODE_EVENT_KIND_DOCKER_UNREACHABLE ->
                    dockerIs(nodeId, false, event.getDetail(), at);
            case NODE_EVENT_KIND_DOCKER_RECOVERED -> dockerIs(nodeId, true, null, at);
            case NODE_EVENT_KIND_UPGRADE_ROLLED_BACK -> audit.record(AuditEntry.failed(
                    AuditActor.node(nodeId, name, null), "node.upgrade",
                    AuditTarget.of("node", nodeId, name), null,
                    "The new binary did not come back and the previous one was restored: "
                            + event.getDetail()));
            default -> { }
        }

        write(event, name, at);
    }

    /**
     * Docker not answering is a state, not a moment, so it gets a column - and the column
     * is the one thing that must never be read as "the containers are gone". The node
     * keeps every one of them and keeps retrying (AGENTS.md §4.5).
     */
    private void dockerIs(UUID nodeId, boolean healthy, String detail, Instant at) {
        jdbc.sql(DOCKER_SQL)
                .param("nodeId", nodeId)
                .param("healthy", healthy)
                .param("detail", detail == null || detail.isBlank() ? null : detail)
                .param("at", at)
                .update();
    }

    /** One line, at the severity the node chose, naming the subject when there is one. */
    private static void write(NodeEvent event, String nodeName, Instant at) {
        String kind = event.getKind().name().replace("NODE_EVENT_KIND_", "");
        String subject = event.getSubjectId().isBlank() ? "" : " [" + event.getSubjectId() + "]";
        String line = "Node {} {}{} at {}: {}";
        Object[] parts = {nodeName, kind, subject, at, event.getDetail()};
        if (event.getSeverity() == EventSeverity.EVENT_SEVERITY_CRITICAL) {
            log.error(line, parts);
        } else if (event.getSeverity() == EventSeverity.EVENT_SEVERITY_WARNING) {
            log.warn(line, parts);
        } else {
            log.info(line, parts);
        }
    }
}
