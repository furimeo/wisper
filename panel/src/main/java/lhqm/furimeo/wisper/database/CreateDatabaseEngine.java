package lhqm.furimeo.wisper.database;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.crypto.SecretCipher;
import lhqm.furimeo.wisper.node.Node;
import lhqm.furimeo.wisper.node.NodeRepository;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Puts a PostgreSQL or MySQL container on a node.
 *
 * <p>Called from two places and they want different things. An operator on
 * {@code /admin/databases} adds a shared instance ahead of demand;
 * {@link ChooseDatabaseEngine} calls it when a customer asks for a database and there is
 * nothing to put it in - a shared one on the emptiest node, or a dedicated one for an
 * organization that paid for isolation. Both are the same six decisions, so they are one
 * file.
 *
 * <p>The engine is not started here in any imperative sense. The row is written and the
 * node's spec is republished; the daemon sees a {@code DatabaseEngineSpec} it is not
 * running and starts it on its next reconcile pass. That is what makes a node that lost
 * its disk rebuild the container rather than sit there with a panel insisting it has one
 * (design §5.1).
 *
 * <h2>The administrative password</h2>
 *
 * <p>Generated here and stored encrypted. It has to be the panel's, not the node's: the
 * panel is what creates customer databases through it, rotates it, and hands it to a
 * backup job. A secret the node invented is one the panel could do none of those with.
 */
@Component
public class CreateDatabaseEngine {

    private final DatabaseEngineRepository engines;
    private final NodeRepository nodes;
    private final AllocateEnginePort ports;
    private final SecretCipher cipher;
    private final PublishNodeSpec specs;
    private final DatabaseSettings settings;
    private final AuditTrail audit;

    public CreateDatabaseEngine(DatabaseEngineRepository engines, NodeRepository nodes,
                                AllocateEnginePort ports, SecretCipher cipher,
                                PublishNodeSpec specs, DatabaseSettings settings,
                                AuditTrail audit) {
        this.engines = engines;
        this.nodes = nodes;
        this.ports = ports;
        this.cipher = cipher;
        this.specs = specs;
        this.settings = settings;
        this.audit = audit;
    }

    /**
     * An instance every tenant on the node shares.
     *
     * @throws NotFoundException if there is no such node
     * @throws RequestRejected   if the node already carries a shared instance of this
     *                           engine, which {@code database_engine_shared_per_node_key}
     *                           would refuse anyway, or if it cannot take one
     */
    @Transactional
    public DatabaseEngine shared(AuditActor actor, UUID nodeId, EngineKind engine) {
        Node node = usableNode(nodeId);
        if (engines.findSharedOn(nodeId, engine.stored()).isPresent()) {
            throw new RequestRejected(null, node.name() + " already runs a shared "
                    + engine.label() + " instance. One per engine per node is the limit, and it "
                    + "is the point: a second one would double the idle memory and halve the "
                    + "databases each can hold.");
        }
        UUID id = UUID.randomUUID();
        DatabaseEngine created = engines.save(DatabaseEngine.shared(id, nodeId, engine,
                settings.versionFor(engine), settings.imageFor(engine),
                ports.onNode(nodeId, engine), cipher.encrypt(DatabasePassword.generate())));
        publish(actor, created, node, "shared " + engine.label() + " engine added");
        return created;
    }

    /**
     * An instance one organization has to itself, which is the paid way out of a shared
     * engine (design §8.1).
     *
     * @param organizationId the owner. {@code database_engine_dedicated_has_owner} makes
     *                       the mode and the owner one fact, so there is no way to create
     *                       a dedicated instance belonging to nobody
     */
    @Transactional
    public DatabaseEngine dedicated(AuditActor actor, UUID nodeId, UUID organizationId,
                                    EngineKind engine) {
        Node node = usableNode(nodeId);
        UUID id = UUID.randomUUID();
        DatabaseEngine created = engines.save(DatabaseEngine.dedicated(id, nodeId, organizationId,
                engine, settings.versionFor(engine), settings.imageFor(engine),
                ports.onNode(nodeId, engine), cipher.encrypt(DatabasePassword.generate())));
        publish(actor, created, node, "dedicated " + engine.label() + " engine added");
        return created;
    }

    /**
     * A node that can actually be given one.
     *
     * <p>Placeable rather than merely existing: a suspended or draining machine would take
     * the row and never receive the spec, and the customer waiting on the database would
     * be waiting on nothing.
     */
    private Node usableNode(UUID nodeId) {
        Node node = nodes.findById(nodeId).orElseThrow(() -> NotFoundException.of("node", nodeId));
        if (!node.isPlaceable()) {
            throw new RequestRejected("nodeId", node.name() + " is " + node.lifecycle()
                    + " and is not accepting new work. Resume it, or pick another node.");
        }
        return node;
    }

    private void publish(AuditActor actor, DatabaseEngine engine, Node node, String reason) {
        specs.toNode(node.id(), reason);
        // database_engine.create, not database.create: one is an operator adding a
        // container to a machine and the other is a customer getting a database, and a
        // trail that cannot tell them apart is a trail nobody can filter.
        audit.record(AuditEntry.succeeded(actor, "database_engine.create",
                AuditTarget.of("database_engine", engine.id(), engine.describe()),
                engine.organizationId(),
                engine.mode() + " " + engine.engine().label() + " on " + node.name()
                        + " port " + engine.port()));
    }
}
