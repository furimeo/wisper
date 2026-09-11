package lhqm.furimeo.wisper.node;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Removes a node record, once and deliberately (design §7.7).
 *
 * <p>Three gates, and none of them is decoration:
 *
 * <ol>
 * <li><strong>Drained or never enrolled.</strong> A record for a machine still holding
 *     workloads cannot go, because the row is the only thing that says which machine they
 *     are on.</li>
 * <li><strong>The operator types the name.</strong> The one thing that cannot be done by
 *     clicking the wrong row in a list.</li>
 * <li><strong>The database says no if anything still points at it.</strong>
 *     {@code placement.node_id} is {@code ON DELETE RESTRICT} precisely so this cannot be
 *     talked around; the message below turns that constraint into a sentence.</li>
 * </ol>
 *
 * <p>Deleting the row removes nothing from the machine. Containers keep running, volumes
 * keep their bytes, and Caddy keeps serving - the panel has simply stopped knowing about
 * any of it. {@code sasayaki uninstall} is what removes the daemon, and even that leaves
 * {@code /var/lib/wisper} alone unless somebody types a confirmation at {@code --purge}.
 * The confirmation text on the screen says so, because an operator who believes this
 * button wipes a machine will use it expecting exactly that.
 */
@Component
public class DeleteNode {

    private final NodeRepository nodes;
    private final AuditTrail audit;

    public DeleteNode(NodeRepository nodes, AuditTrail audit) {
        this.nodes = nodes;
        this.audit = audit;
    }

    /**
     * @param typedName what the operator typed to confirm; must equal the node's name
     * @throws NotFoundException if there is no such node
     * @throws RequestRejected   if the name does not match, the node is not drained, or
     *                           something still references it
     */
    @Transactional
    public void delete(AuditActor actor, UUID nodeId, String typedName) {
        Node node = nodes.findById(nodeId).orElseThrow(() -> NotFoundException.of("node", nodeId));

        if (typedName == null || !node.name().equals(typedName.strip())) {
            throw new RequestRejected("confirmation",
                    "Type " + node.name() + " to confirm.");
        }
        if (node.isEnrolled() && node.lifecycle() != NodeLifecycle.DRAINED
                && node.lifecycle() != NodeLifecycle.RETIRED) {
            throw new RequestRejected(null, node.name() + " is " + node.lifecycle()
                    + ". Drain it first: deleting the record does not stop anything running on "
                    + "the machine, it only stops the panel knowing where those workloads are.");
        }

        try {
            nodes.delete(node);
        } catch (DataIntegrityViolationException stillReferenced) {
            // placement.node_id is ON DELETE RESTRICT. The constraint is the backstop for
            // the lifecycle check above, and it fires when a placement was created between
            // the drain finishing and this call.
            throw new RequestRejected(null, node.name() + " still has services placed on it. "
                    + "Drain it and let the placements be released before deleting it.");
        }

        audit.record(AuditEntry.succeeded(actor, "node.delete",
                AuditTarget.of("node", nodeId, node.name()), null,
                "Node record deleted. Nothing on the machine was touched; run "
                        + "`sasayaki uninstall` there to remove the daemon."));
    }
}
