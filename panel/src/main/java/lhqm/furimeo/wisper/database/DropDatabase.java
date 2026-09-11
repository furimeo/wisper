package lhqm.furimeo.wisper.database;

import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.node.NodeConnections;
import lhqm.furimeo.wisper.node.NodeOffline;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.proto.v1.CommandResult;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Removes a customer database and the login that owns it.
 *
 * <p>Destructive, irreversible from the panel's side, and therefore confirmed by typing the
 * database's name. The row is not the data: dropping this is dropping a customer's tables,
 * so the confirmation exists for the same reason node deletion has one.
 *
 * <h2>Three steps, and each one is recoverable if the next does not happen</h2>
 *
 * <ol>
 * <li>The row moves to {@code DELETING} and commits. The grant leaves the node's spec at
 *     that moment - {@code BuildDatabaseSpecs} filters this state out - so the node removes
 *     it on its next reconcile pass even if everything below fails.</li>
 * <li>{@code DropDatabase} goes to the node with {@code drop_data} set, so it happens now
 *     rather than within fifteen seconds, and the answer is something a person can be
 *     shown.</li>
 * <li>The row is deleted and the spec is republished. A row stuck in {@code DELETING} is a
 *     visible failure with a message on it, which is the point of not deleting first.</li>
 * </ol>
 *
 * <p>{@code drop_data} is set explicitly rather than left to the node. Dropping the role
 * and keeping the tables is a real operation - it locks an application out while somebody
 * investigates - and a destructive default is how customer data disappears without anybody
 * choosing it.
 */
@Component
public class DropDatabase {

    private final TransactionTemplate transactions;
    private final ManagedDatabaseRepository databases;
    private final DatabaseEngineRepository engines;
    private final NodeConnections nodes;
    private final PublishNodeSpec specs;
    private final DatabaseSettings settings;
    private final AuditTrail audit;

    public DropDatabase(PlatformTransactionManager transactionManager,
                        ManagedDatabaseRepository databases, DatabaseEngineRepository engines,
                        NodeConnections nodes, PublishNodeSpec specs, DatabaseSettings settings,
                        AuditTrail audit) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.databases = databases;
        this.engines = engines;
        this.nodes = nodes;
        this.specs = specs;
        this.settings = settings;
        this.audit = audit;
    }

    /**
     * Drops {@code databaseId} and everything in it.
     *
     * @param confirmation the database's name as the panel shows it, typed back
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        when it is not this organization's
     * @throws RequestRejected                          when the confirmation does not
     *                                                  match, or the node refused
     */
    public void drop(AuditActor actor, Membership membership, UUID databaseId,
                     String confirmation) {
        membership.requireWrite("database.delete");

        Subject subject = transactions.execute(status -> markDeleting(membership, databaseId,
                confirmation));

        lhqm.furimeo.wisper.proto.v1.DropDatabase command =
                lhqm.furimeo.wisper.proto.v1.DropDatabase.newBuilder()
                        .setId(subject.database().id().toString())
                        .setEngine(subject.engine().engine().toWire())
                        .setDatabaseName(subject.database().name())
                        .setUsername(subject.database().dbUsername())
                        .setDropData(true)
                        .build();

        CommandResult result;
        try {
            result = nodes.call(subject.engine().nodeId(),
                            PanelMessage.newBuilder().setDropDatabase(command))
                    .orTimeout(settings.commandTimeout().toSeconds(), TimeUnit.SECONDS)
                    .join();
        } catch (NodeOffline | CompletionException unreachable) {
            throw halt(actor, membership, subject, "The node running this database is not "
                    + "answering. The database is marked for deletion and will be removed when "
                    + "the node comes back; nothing has been lost.");
        }
        if (!result.getOk()) {
            throw halt(actor, membership, subject, blankToDefault(result.getDetail(),
                    "The engine refused to drop this database."));
        }

        transactions.executeWithoutResult(status -> {
            databases.deleteById(databaseId);
            specs.toNode(subject.engine().nodeId(), "database " + subject.database().name()
                    + " dropped");
        });

        audit.record(AuditEntry.succeeded(actor, "database.delete",
                AuditTarget.of("managed_database", subject.database().id(),
                        subject.database().name()),
                membership.organizationId(),
                "Dropped from " + subject.engine().describe() + " with its data"));
    }

    /**
     * Checks the confirmation and takes the grant out of the node's spec.
     *
     * <p>Committing this before the command is what makes an unanswered drop converge
     * anyway: the node removes what is not in its spec, so the worst case is a delay rather
     * than a database the customer deleted still accepting connections.
     */
    private Subject markDeleting(Membership membership, UUID databaseId, String confirmation) {
        ManagedDatabase database = databases.findOwnedBy(databaseId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("managed_database", databaseId));
        if (confirmation == null || !database.name().equals(confirmation.strip())) {
            throw new RequestRejected("confirmation", "Type " + database.name()
                    + " to confirm. This deletes the database and everything in it.");
        }
        DatabaseEngine engine = engines.findById(database.databaseEngineId())
                .orElseThrow(() -> NotFoundException.of("database_engine",
                        database.databaseEngineId()));
        ManagedDatabase deleting = databases.save(database.deleting());
        specs.toNode(engine.nodeId(), "database " + database.name() + " being dropped");
        return new Subject(deleting, engine);
    }

    /** Leaves the row visible in {@code DELETING} with a message, and refuses. */
    private RequestRejected halt(AuditActor actor, Membership membership, Subject subject,
                                 String message) {
        transactions.executeWithoutResult(status ->
                databases.findById(subject.database().id())
                        .ifPresent(current -> databases.save(current.noting(message))));
        audit.record(AuditEntry.failed(actor, "database.delete",
                AuditTarget.of("managed_database", subject.database().id(),
                        subject.database().name()),
                membership.organizationId(), message));
        return new RequestRejected(null, message);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** The two rows the command needs, read once. */
    private record Subject(ManagedDatabase database, DatabaseEngine engine) {
    }
}
