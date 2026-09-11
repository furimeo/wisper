package lhqm.furimeo.wisper.database;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Starts or stops an engine container.
 *
 * <p>An operator's button, under {@code /admin/databases}. It writes intent and
 * republishes the node's spec; the daemon converges on its next pass. Nothing here waits
 * for the container, because nothing needs to - the engine's reported state comes back in
 * a status batch and the screen shows the two side by side until they agree.
 *
 * <h2>Stopping is refused while customers are inside</h2>
 *
 * <p>Stopping a shared engine takes every database on it offline at once, across tenants
 * who have no relationship with each other and no warning. So it is refused while the
 * engine still holds databases, with the number in the message. An operator who genuinely
 * means it moves or drops those first, which is a slower path on purpose.
 *
 * <p>A dedicated instance is different: it belongs to one organization, and stopping it
 * affects only them. It is allowed, and the message on the customer's screen says the
 * engine is stopped rather than that the database vanished.
 */
@Component
public class SetEngineDesiredState {

    private final DatabaseEngineRepository engines;
    private final PublishNodeSpec specs;
    private final AuditTrail audit;

    public SetEngineDesiredState(DatabaseEngineRepository engines, PublishNodeSpec specs,
                                 AuditTrail audit) {
        this.engines = engines;
        this.specs = specs;
        this.audit = audit;
    }

    /**
     * Asks the node to keep this engine running.
     *
     * @throws NotFoundException if there is no such engine
     */
    @Transactional
    public DatabaseEngine start(AuditActor actor, UUID engineId) {
        return move(actor, engineId, EngineDesiredState.RUNNING);
    }

    /**
     * Asks the node to stop it, leaving the data where it is.
     *
     * @throws RequestRejected if it is a shared instance that still holds databases
     */
    @Transactional
    public DatabaseEngine stop(AuditActor actor, UUID engineId) {
        return move(actor, engineId, EngineDesiredState.STOPPED);
    }

    private DatabaseEngine move(AuditActor actor, UUID engineId, EngineDesiredState wanted) {
        DatabaseEngine engine = engines.findById(engineId)
                .orElseThrow(() -> NotFoundException.of("database_engine", engineId));
        if (engine.desiredState() == wanted) {
            return engine;
        }
        if (wanted == EngineDesiredState.STOPPED && engine.isShared()) {
            long held = engines.countDatabasesOn(engineId);
            if (held > 0) {
                throw new RequestRejected(null, "This shared " + engine.engine().label()
                        + " instance holds " + held + " customer database"
                        + (held == 1 ? "" : "s") + ". Stopping it would take all of them offline "
                        + "at once. Move or drop them first.");
            }
        }
        DatabaseEngine saved = engines.save(engine.wanting(wanted));
        specs.toNode(engine.nodeId(), engine.engine().label() + " engine "
                + (wanted == EngineDesiredState.RUNNING ? "started" : "stopped"));
        // database_engine.* rather than database.*: the vocabulary in panel-ports.md §2.4
        // covers customer databases and has nothing for the containers they live in, and
        // filing "an operator stopped PostgreSQL on node 3" under database.delete would
        // make the trail lie. See the note in AdminDatabaseEngineController.
        audit.record(AuditEntry.succeeded(actor,
                wanted == EngineDesiredState.RUNNING
                        ? "database_engine.start" : "database_engine.stop",
                AuditTarget.of("database_engine", engine.id(), engine.describe()),
                engine.organizationId(),
                "Desired state set to " + wanted));
        return saved;
    }
}
