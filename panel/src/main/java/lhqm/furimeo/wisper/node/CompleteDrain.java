package lhqm.furimeo.wisper.node;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.proto.v1.DrainReport;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Records what a node did when it was told to drain.
 *
 * <p>Reached two ways, and both have to work: the operator's page waits for the answer to
 * the command it sent, and {@code grpc} routes a {@code DrainReport} arriving on the
 * control stream here (panel-ports.md §3) - which is what happens when the drain outlived
 * the page that started it. Writing the same report twice is harmless, because both writes
 * say the same thing.
 *
 * <p>{@code DRAINED} means nothing evacuable is left and the record is safe to delete. It
 * does <strong>not</strong> mean the machine is empty: everything with a volume is still
 * there, still running, still serving. The two are different and the deletion screen says
 * so, because a node that looks empty and is not is how a customer's database disappears.
 */
@Component
public class CompleteDrain {

    private static final Logger log = LoggerFactory.getLogger(CompleteDrain.class);

    private final NodeRepository nodes;
    private final AuditTrail audit;

    public CompleteDrain(NodeRepository nodes, AuditTrail audit) {
        this.nodes = nodes;
        this.audit = audit;
    }

    /** @throws NotFoundException if there is no such node */
    @Transactional
    public void accept(UUID nodeId, DrainReport report) {
        Node node = nodes.findById(nodeId).orElseThrow(() -> NotFoundException.of("node", nodeId));

        String detail = "Evacuated " + report.getEvacuatedWorkloadsCount() + ", "
                + report.getPinnedWorkloadsCount() + " pinned by a volume"
                + (report.getPinnedWorkloadsCount() == 0 ? ""
                        : ": " + String.join(", ", report.getPinnedWorkloadsList()));

        if (report.getComplete() && node.lifecycle() == NodeLifecycle.DRAINING) {
            nodes.save(node.drained());
            log.info("Node {} finished draining. {}", node.name(), detail);
        } else if (node.lifecycle() == NodeLifecycle.DRAINING) {
            log.info("Node {} is still draining. {}", node.name(), detail);
        }

        audit.record(AuditEntry.succeeded(AuditActor.node(nodeId, node.name(), null),
                "node.drain", AuditTarget.of("node", nodeId, node.name()), null,
                (report.getComplete() ? "Drain complete. " : "Drain in progress. ") + detail));
    }
}
