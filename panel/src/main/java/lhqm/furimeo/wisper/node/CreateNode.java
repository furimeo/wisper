package lhqm.furimeo.wisper.node;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * Creates the node record an operator will then enrol a machine against.
 *
 * <p>The record comes first and the machine second, which is the order that makes the
 * bootstrap token possible: a token belongs to exactly one record, so a token that leaks
 * can enrol one machine into one slot an operator already decided to have, rather than
 * adding an arbitrary machine to the fleet (design §7.1).
 *
 * <p>Nothing here talks to anything. A node record is a row, and the machine that will
 * fill it may not exist yet.
 */
@Component
public class CreateNode {

    private final NodeRepository nodes;
    private final AuditTrail audit;

    public CreateNode(NodeRepository nodes, AuditTrail audit) {
        this.nodes = nodes;
        this.audit = audit;
    }

    /**
     * @param name          lower case, and the phrase an operator has to type again to
     *                      delete this node. Shaped by {@code node_name_shape}.
     * @param publicAddress where customers' DNS will point. The panel never dials it; it
     *                      is shown to the customer so they can create an A record.
     * @param tags          placement filters - {@code region=eu}, {@code ssd}
     * @throws RequestRejected for a name that is malformed or taken
     */
    @Transactional
    public Node create(AuditActor actor, String name, String description, String publicAddress,
                       List<String> tags) {
        String cleaned = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        if (!cleaned.matches(Node.NAME_PATTERN)) {
            throw new RequestRejected("name", "A node name is 2 to 63 characters of lower-case "
                    + "letters, digits, dots and dashes, starting with a letter or digit.");
        }
        if (nodes.existsByName(cleaned)) {
            throw new RequestRejected("name", "There is already a node called " + cleaned + ".");
        }
        List<String> cleanTags = tags == null ? List.of() : tags.stream()
                .map(tag -> tag.strip().toLowerCase(Locale.ROOT))
                .filter(tag -> !tag.isEmpty())
                .distinct()
                .toList();

        Node node = nodes.save(Node.created(UUID.randomUUID(), cleaned, description,
                publicAddress, cleanTags));
        audit.record(AuditEntry.succeeded(actor, "node.create",
                AuditTarget.of("node", node.id(), node.name()), null,
                cleanTags.isEmpty() ? "No tags" : "Tags: " + String.join(", ", cleanTags)));
        return node;
    }
}
