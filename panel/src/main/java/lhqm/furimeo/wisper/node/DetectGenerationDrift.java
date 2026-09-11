package lhqm.furimeo.wisper.node;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;

/**
 * Compares what a node says it has applied with what the panel published, and does the
 * one thing each answer calls for.
 *
 * <p>Three cases, and only three (schema.md §2):
 *
 * <ul>
 * <li><strong>equal</strong> - converged. Nothing to do, which is the overwhelmingly
 *     common outcome and the reason this is cheap.</li>
 * <li><strong>lower</strong> - the node is behind. Either the spec is still in flight or
 *     the frame carrying it was lost when a tunnel dropped, and the fix for both is the
 *     same: publish again. There is no delta to work out and no catch-up path, which is
 *     exactly why a week-long outage needs no special handling (design §5.2).</li>
 * <li><strong>higher</strong> - impossible. A node cannot invent a generation, so either
 *     a second panel is publishing to this machine or this panel's database went
 *     backwards. Both mean the desired state has two authors, and the answer is to stop
 *     being one of them: suspend, and do not publish again until a person has looked.</li>
 * </ul>
 *
 * <p>Called from the three places a node states a generation - the handshake, a heartbeat
 * and a status batch - because a node that fell behind between two status reports should
 * not have to wait for the next one to be caught up.
 */
@Component
public class DetectGenerationDrift {

    private static final Logger log = LoggerFactory.getLogger(DetectGenerationDrift.class);

    private final NodeRepository nodes;
    private final SuspendNode suspendNode;
    private final PublishNodeSpec publishNodeSpec;

    public DetectGenerationDrift(NodeRepository nodes, SuspendNode suspendNode,
                                 PublishNodeSpec publishNodeSpec) {
        this.nodes = nodes;
        this.suspendNode = suspendNode;
        this.publishNodeSpec = publishNodeSpec;
    }

    /**
     * @param appliedGeneration what the node reported. {@code -1} means it has never
     *                          applied anything, which is the state of a machine that has
     *                          just enrolled and is not drift.
     * @param reason            what to write in the {@code ApplySpec} if one is sent, so
     *                          the daemon's log distinguishes a resend from a real change
     * @return true when the node was suspended, in which case the caller must stop talking
     *         to it
     */
    @Transactional
    public boolean check(UUID nodeId, String nodeName, long appliedGeneration, String reason) {
        long desired = nodes.desiredGenerationOf(nodeId).orElse(0L);
        if (appliedGeneration == desired) {
            return false;
        }
        if (appliedGeneration > desired) {
            String detail = "Node " + nodeName + " reports generation " + appliedGeneration
                    + " but this panel has only ever published " + desired
                    + ". Two panels are driving one node, or this panel's database was "
                    + "restored behind the node. Suspended; no container was touched.";
            suspendNode.suspend(AuditActor.system("node-generation-drift"), nodeId,
                    NodeSuspensionReason.DUPLICATE_FINGERPRINT, detail);
            return true;
        }
        log.debug("Node {} is at generation {} and the panel has published {}; resending",
                nodeName, appliedGeneration, desired);
        publishNodeSpec.toNode(nodeId, reason);
        return false;
    }
}
