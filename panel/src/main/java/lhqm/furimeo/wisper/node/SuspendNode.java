package lhqm.furimeo.wisper.node;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Stops the panel talking to a node.
 *
 * <p>Suspension is the panel's only unilateral move against a machine it cannot reach, and
 * what it does is narrower than it sounds: no new placements, no spec published, no
 * command dispatched, and the next control stream refused. It does <strong>not</strong>
 * stop a single customer container. The node keeps reconciling against the last spec it
 * received, which is the correct behaviour for every reason a node gets suspended - a
 * cloned virtual machine, a protocol the panel cannot speak, an operator who wants a
 * machine left alone (design §7.3, §7.6).
 *
 * <p>Called from three places: an operator's button, the clone detector, and the
 * generation-drift check that concludes two panels are driving one node. All three write
 * the same audit action, {@code node.suspend}, with a different reason.
 */
@Component
public class SuspendNode {

    private static final Logger log = LoggerFactory.getLogger(SuspendNode.class);

    private final NodeRepository nodes;
    private final AuditTrail audit;

    public SuspendNode(NodeRepository nodes, AuditTrail audit) {
        this.nodes = nodes;
        this.audit = audit;
    }

    /**
     * Suspends one node.
     *
     * <p>Idempotent: suspending an already-suspended node returns the row unchanged and
     * writes no second audit line. The clone detector reaches this twice for the same pair
     * when two clones race, and two identical alerts is noise an operator learns to skip.
     *
     * <h2>Why {@code REQUIRES_NEW}</h2>
     *
     * <p>Both automatic callers suspend and then <em>refuse</em> - the clone detector
     * throws {@code EnrolmentRefused}, and the handshake throws {@code HandshakeRefused}
     * a moment later. In the caller's own transaction that refusal would roll the
     * suspension back, so the machine that was just detected as a clone would be free to
     * try again against a record that is no longer suspended, and no operator would ever
     * be told. The suspension has to outlive the refusal that follows it.
     *
     * <p>{@link #byOperator} calls this from inside the same object, so it is not
     * proxied and stays in one transaction - which is right there: nothing is refused on
     * that path.
     *
     * @param actor  who or what decided; the detectors pass {@link AuditActor#system}
     * @param reason which of the four suspensions this is
     * @param detail one sentence for the operator, stored on the row and shown on the page
     * @throws NotFoundException if there is no such node
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Node suspend(AuditActor actor, UUID nodeId, NodeSuspensionReason reason,
                        String detail) {
        Node node = nodes.findById(nodeId).orElseThrow(() -> NotFoundException.of("node", nodeId));
        if (node.isSuspended()) {
            return node;
        }
        Node suspended = nodes.save(node.suspended(reason, Instant.now()));
        // At error, not warn: every reason this is reached is something an operator has to
        // look at, and two of them mean a machine has been cloned.
        log.error("Node {} suspended ({}): {}", node.name(), reason, detail);
        audit.record(AuditEntry.succeeded(actor, "node.suspend",
                AuditTarget.of("node", nodeId, node.name()), null,
                reason.name() + ": " + detail));
        return suspended;
    }

    /**
     * The operator's version, which refuses the transitions that make no sense.
     *
     * <p>A record nobody has enrolled against has nothing to suspend: there is no machine,
     * no credential and no workload, and the button that deletes it is one row away.
     * Saying so is more useful than writing a suspension that changes nothing.
     */
    @Transactional
    public Node byOperator(AuditActor actor, UUID nodeId, String reason) {
        Node node = nodes.findById(nodeId).orElseThrow(() -> NotFoundException.of("node", nodeId));
        if (reason == null || reason.isBlank()) {
            throw new RequestRejected("reason", "Say why. It is shown on the node's page.");
        }
        if (!node.isEnrolled()) {
            throw new RequestRejected(null, node.name() + " has never enrolled, so there is "
                    + "nothing to suspend. Delete the record instead.");
        }
        if (node.isSuspended()) {
            throw new RequestRejected(null, node.name() + " is already suspended.");
        }
        return suspend(actor, nodeId, NodeSuspensionReason.OPERATOR, reason);
    }
}
