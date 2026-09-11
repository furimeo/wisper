package lhqm.furimeo.wisper.placement;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.node.Node;
import lhqm.furimeo.wisper.node.NodeLifecycle;
import lhqm.furimeo.wisper.node.NodeRepository;

/**
 * Moves work off every node an operator has put into {@code DRAINING}.
 *
 * <p>This is the panel half of a drain, and it runs on a timer rather than being called by
 * the screen that started one. Two reasons, and they are the same two reasons the node's
 * own reconcile loop runs on a timer:
 *
 * <ul>
 * <li><strong>The intent is the row.</strong> {@code node.lifecycle = 'DRAINING'} is what
 *     an operator asked for. Converging on it repeatedly is what makes the outcome
 *     independent of whether their browser stayed open, whether the node answered, and
 *     whether the fleet had room at that exact second.</li>
 * <li><strong>Nothing may point the other way.</strong> {@code node} must not depend on
 *     {@code placement} (panel-ports.md §6), so the package that owns the lifecycle column
 *     cannot call the package that owns the placement table. A sweep needs no arrow at
 *     all.</li>
 * </ul>
 *
 * <p>It is not an automatic reschedule. Nothing moves unless somebody marked the machine
 * draining, and a service with storage is never moved by it - that stays a deliberate act
 * through {@link MigrateService} (design §7.8).
 */
@Component
public class EvacuateDrainingNodes {

    private static final Logger log = LoggerFactory.getLogger(EvacuateDrainingNodes.class);

    private final NodeRepository nodes;
    private final DrainNodePlacements drain;

    public EvacuateDrainingNodes(NodeRepository nodes, DrainNodePlacements drain) {
        this.nodes = nodes;
        this.drain = drain;
    }

    /**
     * One pass over the draining nodes.
     *
     * <p>Each node is its own transaction inside {@link DrainNodePlacements}, and a failure
     * on one is logged and stepped over. A fleet where one node cannot be emptied is not a
     * reason to stop emptying the others, and the sweep will be back in a moment.
     *
     * @return how many services were moved across the whole fleet
     */
    public int sweep() {
        AuditActor actor = AuditActor.system("placement drain sweep");
        List<Node> draining = nodes.findNeedingAttention().stream()
                .filter(node -> node.lifecycle() == NodeLifecycle.DRAINING)
                .toList();
        int moved = 0;
        for (Node node : draining) {
            try {
                DrainOutcome outcome = drain.forNode(actor, node.id(), true,
                        "node " + node.name() + " is draining");
                moved += outcome.evacuated().size();
                if (!outcome.stranded().isEmpty()) {
                    log.warn("Node {} still holds {} service(s) the fleet has no room for",
                            node.name(), outcome.stranded().size());
                }
            } catch (RuntimeException failed) {
                log.warn("Could not evacuate node {}: {}", node.name(), failed.getMessage());
            }
        }
        return moved;
    }
}
