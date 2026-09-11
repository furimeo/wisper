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
 * Removes an engine container from a node.
 *
 * <p>Refused while it still holds customer databases. The foreign key is
 * {@code ON DELETE RESTRICT} and would refuse it anyway, but a constraint violation is
 * true and unreadable; this says how many databases are in the way and what to do about
 * them. Dropping them as a side effect of deleting the container is not on offer - that is
 * how customer data disappears without anybody deciding it should.
 *
 * <p>Deleting the row does <strong>not</strong> delete bytes on the node. The container
 * leaves the spec and the daemon removes it; the data directory under
 * {@code /var/lib/wisper/databases} stays until an explicit purge (AGENTS.md §4.5). An
 * operator who deleted the wrong engine can recreate it and get the data back, which is
 * only true because nothing here reaches for the disk.
 *
 * <p>The name has to be typed back for the same reason node deletion asks for one: this is
 * the button that ends several tenants' Tuesday if it is pressed on the wrong row.
 */
@Component
public class DeleteDatabaseEngine {

    private final DatabaseEngineRepository engines;
    private final PublishNodeSpec specs;
    private final AuditTrail audit;

    public DeleteDatabaseEngine(DatabaseEngineRepository engines, PublishNodeSpec specs,
                                AuditTrail audit) {
        this.engines = engines;
        this.specs = specs;
        this.audit = audit;
    }

    /**
     * @param confirmation the engine's host name, typed back by the operator
     * @throws NotFoundException if there is no such engine
     * @throws RequestRejected   if the confirmation does not match, or the engine still
     *                           holds customer databases
     */
    @Transactional
    public void delete(AuditActor actor, UUID engineId, String confirmation) {
        DatabaseEngine engine = engines.findById(engineId)
                .orElseThrow(() -> NotFoundException.of("database_engine", engineId));

        if (confirmation == null || !engine.host().equals(confirmation.strip())) {
            throw new RequestRejected("confirmation",
                    "Type " + engine.host() + " to confirm removing this engine.");
        }
        long held = engines.countDatabasesOn(engineId);
        if (held > 0) {
            throw new RequestRejected(null, "This " + engine.engine().label() + " instance still "
                    + "holds " + held + " customer database" + (held == 1 ? "" : "s")
                    + ". Drop or move them first; removing the engine will not remove them.");
        }

        UUID nodeId = engine.nodeId();
        String described = engine.describe();
        engines.delete(engine);
        // The container leaves the node because it left the spec. Omission is deletion,
        // and it is what makes this survive the node being unreachable right now.
        specs.toNode(nodeId, "database engine removed: " + described);
        audit.record(AuditEntry.succeeded(actor, "database_engine.delete",
                AuditTarget.of("database_engine", engineId, described),
                engine.organizationId(),
                "Removed from the node's spec; the data directory on the node is untouched"));
    }
}
