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
import lhqm.furimeo.wisper.proto.v1.UpgradeResult;

/**
 * Records how a node's self-upgrade ended.
 *
 * <p>Two outcomes, and the distinction is the whole reason this message exists. A node
 * that came back on the new binary reports the new version. A node whose new binary did
 * not come back put the old one back and says so - {@code rolled_back} is reported rather
 * than inferred from a version that failed to change, because inferring it would make a
 * failed upgrade look identical to an upgrade that never started (design §7.5).
 *
 * <p>A rollback is recorded as a {@code FAILED} audit entry and left at that. It is not an
 * outage: the node is running the binary it was running before, every container is
 * untouched, and the correct next step is a person reading the detail rather than the
 * panel retrying an upgrade that has already been shown not to work on this machine.
 *
 * <p>{@code agent_version} is written here as well as at the next handshake. The node
 * restarts as part of an upgrade, so the handshake is seconds away - but if this result
 * arrived on the old stream just before it died, that write is what stops the fleet screen
 * showing a stale version until the node happens to reconnect.
 */
@Component
public class CompleteUpgrade {

    private static final Logger log = LoggerFactory.getLogger(CompleteUpgrade.class);

    private static final String SQL = """
            INSERT INTO node_status (node_id, agent_version, updated_at, version)
            VALUES (:nodeId, :agentVersion, :at, 0)
            ON CONFLICT (node_id) DO UPDATE
               SET agent_version = EXCLUDED.agent_version,
                   updated_at    = EXCLUDED.updated_at,
                   version       = node_status.version + 1
            """;

    private final JdbcClient jdbc;
    private final NodeRepository nodes;
    private final AuditTrail audit;

    public CompleteUpgrade(JdbcClient jdbc, NodeRepository nodes, AuditTrail audit) {
        this.jdbc = jdbc;
        this.nodes = nodes;
        this.audit = audit;
    }

    @Transactional
    public void accept(UUID nodeId, UpgradeResult result) {
        String name = nodes.findById(nodeId).map(Node::name).orElse(nodeId.toString());
        String running = result.getRolledBack() ? result.getPreviousVersion()
                : result.getNewVersion();

        if (!running.isBlank()) {
            jdbc.sql(SQL)
                    .param("nodeId", nodeId)
                    .param("agentVersion", running)
                    .param("at", Instant.now())
                    .update();
        }

        AuditActor actor = AuditActor.node(nodeId, name, null);
        AuditTarget target = AuditTarget.of("node", nodeId, name);
        if (result.getRolledBack()) {
            log.error("Node {} rolled back to {} after a failed upgrade: {}", name,
                    result.getPreviousVersion(), result.getDetail());
            audit.record(AuditEntry.failed(actor, "node.upgrade", target, null,
                    "Upgrade failed and the node rolled back to " + result.getPreviousVersion()
                            + ": " + result.getDetail()));
            return;
        }
        log.info("Node {} upgraded from {} to {}", name, result.getPreviousVersion(),
                result.getNewVersion());
        audit.record(AuditEntry.succeeded(actor, "node.upgrade", target, null,
                "Upgraded from " + result.getPreviousVersion() + " to "
                        + result.getNewVersion()));
    }
}
