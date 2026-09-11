package lhqm.furimeo.wisper.node;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the fleet.
 *
 * <p>Two queries and a join in Java rather than one query with a {@code LEFT JOIN} and a
 * hand-written row mapper. A fleet is tens of machines, not millions of rows; the join
 * saves one round trip and costs a mapper that has to be kept in step with two tables by
 * hand, which is the sort of thing that quietly starts returning nulls after somebody adds
 * a column.
 *
 * <p>A node with no status row is included and shown as disconnected. That is the state of
 * every record between an operator creating it and a machine enrolling against it, and
 * leaving it off the list would hide the row whose install command the operator is looking
 * for.
 */
@Component
public class ListNodes {

    private final NodeRepository nodes;
    private final NodeStatusRepository statuses;
    private final ReadAgentReleases releases;

    public ListNodes(NodeRepository nodes, NodeStatusRepository statuses,
                     ReadAgentReleases releases) {
        this.nodes = nodes;
        this.statuses = statuses;
        this.releases = releases;
    }

    /** Every node, by name, with what it last said about itself. */
    @Transactional(readOnly = true)
    public List<NodeSummary> all() {
        String newestVersion = releases.newestVersion().orElse(null);
        Map<UUID, NodeStatus> byNode = new HashMap<>();
        for (NodeStatus status : statuses.findAllRows()) {
            byNode.put(status.nodeId(), status);
        }
        return nodes.findAllByName().stream()
                .map(node -> NodeSummary.of(node, byNode.get(node.id()), newestVersion))
                .toList();
    }

    /**
     * The ones an operator has to deal with: suspended, or draining and not finished.
     *
     * <p>Its own query rather than a filter over {@link #all()} because it is what the
     * admin dashboard leads with, and a suspended node buried in an alphabetical list of
     * forty is a suspended node nobody notices.
     */
    @Transactional(readOnly = true)
    public List<NodeSummary> needingAttention() {
        String newestVersion = releases.newestVersion().orElse(null);
        return nodes.findNeedingAttention().stream()
                .map(node -> NodeSummary.of(node,
                        statuses.findRow(node.id()).orElse(null), newestVersion))
                .toList();
    }
}
