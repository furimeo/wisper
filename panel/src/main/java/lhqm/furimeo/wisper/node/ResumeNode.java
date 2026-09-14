package lhqm.furimeo.wisper.node;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Lifts a suspension and starts publishing to the machine again.
 *
 * <p>Always a deliberate act, never automatic, and that is the whole design of the
 * suspension path. The two reasons the panel suspends by itself - a cloned machine and two
 * panels driving one node - are situations where a person has to decide which side is the
 * real one. A panel that un-suspended on its own once the symptom stopped would resume
 * exactly the state it stopped to protect.
 *
 * <p>Resuming publishes the current spec straight away. The node has been converging
 * against whatever it last received for however long the suspension lasted, so the first
 * thing it needs is the truth.
 */
@Component
public class ResumeNode {

    private final NodeRepository nodes;
    private final PublishNodeSpec publishNodeSpec;
    private final RecordDisconnect recordDisconnect;
    private final AuditTrail audit;

    public ResumeNode(NodeRepository nodes, PublishNodeSpec publishNodeSpec,
                      RecordDisconnect recordDisconnect, AuditTrail audit) {
        this.nodes = nodes;
        this.publishNodeSpec = publishNodeSpec;
        this.recordDisconnect = recordDisconnect;
        this.audit = audit;
    }

    /**
     * @throws NotFoundException if there is no such node
     * @throws RequestRejected   if it is not suspended
     */
    @Transactional
    public Node resume(AuditActor actor, UUID nodeId) {
        Node node = nodes.findById(nodeId).orElseThrow(() -> NotFoundException.of("node", nodeId));
        if (!node.isSuspended()) {
            throw new RequestRejected(null, node.name() + " is not suspended; it is "
                    + node.lifecycle() + ".");
        }

        Node resumed = nodes.save(node.resumed());
        recordDisconnect.accept(nodeId, "operator resumed the node");
        audit.record(AuditEntry.succeeded(actor, "node.resume",
                AuditTarget.of("node", nodeId, node.name()), null,
                "Suspension lifted; the current spec was republished"));
        publishNodeSpec.toNode(nodeId, "node resumed");
        return resumed;
    }
}
