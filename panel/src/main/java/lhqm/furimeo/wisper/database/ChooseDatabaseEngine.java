package lhqm.furimeo.wisper.database;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.node.Node;
import lhqm.furimeo.wisper.node.NodeRepository;
import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * Decides which engine a new customer database goes into.
 *
 * <p>Placement for databases, and much simpler than placement for services: a database has
 * no volume of its own, no ports and no image, so the only question is which container
 * already exists that it can live in. Three answers, in order:
 *
 * <ol>
 * <li><strong>Dedicated asked for.</strong> Reuse the organization's own instance of that
 *     engine if it has one, otherwise create it. One container per organization, not one
 *     per database - the point of paying for isolation is not being queried by strangers,
 *     and a second private container gives no more of that.</li>
 * <li><strong>Shared, and one exists.</strong> Take the least loaded, where load is how
 *     many customer databases it already holds. It is the only load figure the panel knows
 *     without asking a node, and it is the one that matters: connections and disk are both
 *     roughly proportional to it.</li>
 * <li><strong>Shared, and none exists.</strong> Create one on a placeable node. A platform
 *     whose first customer is told "an operator has to set up PostgreSQL first" is a
 *     platform with a door and no room behind it.</li>
 * </ol>
 *
 * <p>Nothing here moves an existing database. A database is bytes inside one container on
 * one machine, and relocating it is a dump and a reload, which is a deliberate operation
 * and not something a scheduler does quietly.
 */
@Component
public class ChooseDatabaseEngine {

    private static final Logger log = LoggerFactory.getLogger(ChooseDatabaseEngine.class);

    private final DatabaseEngineRepository engines;
    private final CreateDatabaseEngine creation;
    private final NodeRepository nodes;

    public ChooseDatabaseEngine(DatabaseEngineRepository engines, CreateDatabaseEngine creation,
                                NodeRepository nodes) {
        this.engines = engines;
        this.creation = creation;
        this.nodes = nodes;
    }

    /**
     * The engine a new database should be created in, creating one if there is nothing
     * suitable.
     *
     * @param dedicated whether the customer asked for their own instance
     * @throws RequestRejected when no node can take an engine, which is a capacity problem
     *         an operator has to solve and not something to retry
     */
    @Transactional
    public DatabaseEngine forNewDatabase(AuditActor actor, UUID organizationId, EngineKind engine,
                                         boolean dedicated) {
        if (dedicated) {
            return engines.findDedicatedFor(organizationId, engine.stored())
                    .orElseGet(() -> creation.dedicated(actor, emptiestNode(engine),
                            organizationId, engine));
        }
        List<DatabaseEngine> candidates = engines.findSharedCandidates(engine.stored());
        for (DatabaseEngine candidate : candidates) {
            // The engine's node has to be able to receive a spec, or the database would be
            // created as a row and never as a database. A shared instance on a draining
            // machine is left where it is and simply not chosen for anything new.
            Node node = nodes.findById(candidate.nodeId()).orElse(null);
            if (node != null && node.isPlaceable()) {
                return candidate;
            }
            log.debug("Skipping {} on an unplaceable node", candidate.describe());
        }
        return creation.shared(actor, emptiestNode(engine), engine);
    }

    /**
     * The placeable node carrying the fewest engines of this kind, which for the first
     * database on a fresh platform is simply the only node there is.
     *
     * <p>Counting engines rather than measuring memory is deliberate. The memory an engine
     * costs is already subtracted from the node's capacity by {@code placement}, so the
     * scheduler's own view of the machine is the one that matters for services;
     * duplicating that arithmetic here would give two answers to one question.
     */
    private UUID emptiestNode(EngineKind engine) {
        List<Node> placeable = nodes.findPlaceable();
        if (placeable.isEmpty()) {
            throw new RequestRejected(null, "No node is available to run a database engine. "
                    + "Enrol one, or resume a node that is suspended, and try again.");
        }
        Node best = null;
        long bestCount = Long.MAX_VALUE;
        for (Node node : placeable) {
            long count = engines.findByNodeIdOrderByPort(node.id()).size();
            if (count < bestCount) {
                best = node;
                bestCount = count;
            }
        }
        return best.id();
    }
}
