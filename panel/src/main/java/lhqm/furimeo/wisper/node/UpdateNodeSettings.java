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
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Edits the four things about a node an operator may change after it exists: what it is
 * for, where customers' DNS points at it, which placement filters it satisfies, and
 * whether the scheduler may put anything new on it.
 *
 * <p>The name is not one of them. It is the confirmation phrase {@code DeleteNode} demands
 * and the label every audit entry about this machine already carries; letting it change
 * would make a year of trail read as though two different machines were involved.
 *
 * <p>Turning {@code schedulable} off is the light version of draining: no new work lands
 * here, nothing currently running is touched, and no evacuation is attempted. It is what
 * an operator wants before a maintenance window; {@code RequestNodeDrain} is what they
 * want before pulling the machine out (design §7.7).
 */
@Component
public class UpdateNodeSettings {

    /** A tag is a filter, not a sentence: {@code region=eu}, {@code ssd}, {@code gpu}. */
    private static final String TAG_PATTERN = "[a-z0-9][a-z0-9._=-]{0,62}";

    private final NodeRepository nodes;
    private final AuditTrail audit;

    public UpdateNodeSettings(NodeRepository nodes, AuditTrail audit) {
        this.nodes = nodes;
        this.audit = audit;
    }

    /**
     * @throws NotFoundException if there is no such node
     * @throws RequestRejected   for a tag that is not shaped like one
     */
    @Transactional
    public Node update(AuditActor actor, UUID nodeId, String description, String publicAddress,
                       List<String> tags, boolean schedulable) {
        Node node = nodes.findById(nodeId).orElseThrow(() -> NotFoundException.of("node", nodeId));
        List<String> cleanTags = clean(tags);

        boolean schedulabilityChanged = node.schedulable() != schedulable;
        Node updated = nodes.save(node.withDetails(description, publicAddress, cleanTags,
                schedulable));

        // Only the change worth reading. An audit line saying "updated" for a typo in a
        // description buries the one that says a machine stopped accepting work.
        String detail = schedulabilityChanged
                ? (schedulable ? "Accepting new placements again."
                        : "No longer accepting new placements. Nothing running was touched.")
                : "Description, address and tags updated.";
        // panel-ports.md §2.4 lists no node.update; it is added here rather than filing an
        // edit under node.create, which would make the trail unreadable in the one place
        // it matters - working out who stopped a machine taking work.
        audit.record(AuditEntry.succeeded(actor, "node.update",
                AuditTarget.of("node", nodeId, node.name()), null, detail));
        return updated;
    }

    private static List<String> clean(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        List<String> cleaned = tags.stream()
                .map(tag -> tag.strip().toLowerCase(Locale.ROOT))
                .filter(tag -> !tag.isEmpty())
                .distinct()
                .toList();
        for (String tag : cleaned) {
            if (!tag.matches(TAG_PATTERN)) {
                throw new RequestRejected("tags", "\"" + tag + "\" is not a usable tag. Tags are "
                        + "short lower-case words, optionally with an = in them, like "
                        + "region=eu.");
            }
        }
        return cleaned;
    }
}
