package lhqm.furimeo.wisper.database;

import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lhqm.furimeo.wisper.crypto.SecretCipher;
import lhqm.furimeo.wisper.node.NodeConnections;
import lhqm.furimeo.wisper.node.NodeOffline;
import lhqm.furimeo.wisper.proto.v1.CommandResult;
import lhqm.furimeo.wisper.proto.v1.DatabaseGrant;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;

/**
 * The body of the {@code database-provision} job: tell the node to create the database and
 * the role, and record what it said.
 *
 * <h2>Two phases, and the commit between them is the point</h2>
 *
 * <p>The row is read and the command is built inside a transaction that then
 * <strong>commits</strong>; the network call happens after it returns. Holding a database
 * transaction open across a call that waits minutes for an image pull would occupy a
 * connection from a pool of sixteen for the whole of it.
 *
 * <h2>Why this one blocks and the others do not</h2>
 *
 * <p>{@link RotateDatabasePassword} and {@link DropDatabase} run on a request thread with a
 * person watching, so they wait for the node inline. This runs on a db-scheduler worker
 * with nobody watching, and it waits too - deliberately. Waiting is what makes the retry
 * free: a node behind a tunnel that dropped produces an exception, db-scheduler reschedules
 * the same row, and the database is created when the machine comes back. The alternative,
 * completing the job and marking the row failed, turns a thirty-second network blip into
 * something a customer has to notice and press a button about.
 *
 * <p>A node that answers "no" is different and is terminal: the engine refused, and trying
 * the identical statement again in five minutes will be refused identically.
 */
@Component
public class ProvisionOnNode {

    private static final Logger log = LoggerFactory.getLogger(ProvisionOnNode.class);

    private final TransactionTemplate transactions;
    private final ManagedDatabaseRepository databases;
    private final DatabaseEngineRepository engines;
    private final NodeConnections nodes;
    private final CompleteProvision completions;
    private final SecretCipher cipher;
    private final DatabaseSettings settings;

    public ProvisionOnNode(PlatformTransactionManager transactionManager,
                           ManagedDatabaseRepository databases, DatabaseEngineRepository engines,
                           NodeConnections nodes, CompleteProvision completions,
                           SecretCipher cipher, DatabaseSettings settings) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.databases = databases;
        this.engines = engines;
        this.nodes = nodes;
        this.completions = completions;
        this.cipher = cipher;
        this.settings = settings;
    }

    /** Provisions one pending database. Called by db-scheduler; see {@link DatabaseTasks}. */
    public void run(ProvisionJob job) {
        UUID databaseId = job.managedDatabaseId();
        Dispatch dispatch = transactions.execute(status -> prepare(databaseId));
        if (dispatch == null) {
            return;
        }
        CommandResult result;
        try {
            result = nodes.call(dispatch.nodeId(),
                            PanelMessage.newBuilder().setProvisionDatabase(dispatch.command()))
                    .orTimeout(settings.provisionTimeout().toSeconds(), TimeUnit.SECONDS)
                    .join();
        } catch (NodeOffline | CompletionException unreachable) {
            String reason = reasonOf(unreachable);
            completions.unreachable(databaseId, reason);
            // Rethrown so db-scheduler reschedules this exact row. The grant is already in
            // the node's spec, so nothing is lost while it waits.
            throw new IllegalStateException("Node " + dispatch.nodeId()
                    + " did not answer the provision for database " + databaseId + ": " + reason,
                    unreachable);
        }
        settle(databaseId, result);
    }

    /**
     * Loads the row, builds the command and decrypts the password for it.
     *
     * <p>Returns null when there is nothing to do: the database was dropped while the job
     * waited, or it is no longer {@code PENDING} because an earlier attempt already
     * finished. Both are ordinary outcomes rather than job failures.
     */
    private Dispatch prepare(UUID databaseId) {
        ManagedDatabase database = databases.findById(databaseId).orElse(null);
        if (database == null) {
            log.debug("Database {} is gone; nothing to provision", databaseId);
            return null;
        }
        if (database.state() != ManagedDatabaseState.PENDING) {
            log.debug("Database {} is {} and no longer pending", databaseId, database.state());
            return null;
        }
        DatabaseEngine engine = engines.findById(database.databaseEngineId()).orElse(null);
        if (engine == null) {
            // RESTRICT on the foreign key makes this all but impossible; recording it beats
            // a NullPointerException inside a worker thread if it ever happens.
            databases.save(database.failed("The engine this database was assigned to no longer "
                    + "exists. Delete this database and create it again."));
            return null;
        }
        lhqm.furimeo.wisper.proto.v1.ProvisionDatabase command =
                lhqm.furimeo.wisper.proto.v1.ProvisionDatabase.newBuilder()
                        .setGrant(grantFor(database, engine))
                        // Decrypted here and nowhere else on this path. The node applies it
                        // and keeps no copy outside the engine.
                        .setPassword(cipher.decrypt(database.dbPassword()))
                        .build();
        return new Dispatch(engine.nodeId(), command);
    }

    /**
     * The same grant shape {@code placement} puts in the spec, built here for the command.
     *
     * <p>Both have to agree, because the node matches the command against what it already
     * has: a grant id in one and not the other is a database created twice or not at all.
     */
    private static DatabaseGrant grantFor(ManagedDatabase database, DatabaseEngine engine) {
        return DatabaseGrant.newBuilder()
                .setId(database.id().toString())
                .setEngine(engine.engine().toWire())
                .setDatabaseName(database.name())
                .setUsername(database.dbUsername())
                .setQuotaBytes(database.quotaBytes())
                .setDedicatedInstance(!engine.isShared())
                .setEncoding(database.dbCharset() == null ? "" : database.dbCharset())
                .build();
    }

    /** Turns whatever came back into exactly one call on {@link CompleteProvision}. */
    private void settle(UUID databaseId, CommandResult result) {
        if (result.hasDatabase()) {
            completions.accept(databaseId, result.getDatabase());
            return;
        }
        completions.failed(databaseId, result.getOk()
                ? "The node acknowledged the request but reported no database."
                : blankToDefault(result.getDetail(), "The node refused to create the database."));
    }

    private static String reasonOf(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        return blankToDefault(cause.getMessage(), cause.getClass().getSimpleName());
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** What {@link #prepare} decided, carried out of the transaction it decided it in. */
    private record Dispatch(UUID nodeId,
                            lhqm.furimeo.wisper.proto.v1.ProvisionDatabase command) {
    }
}
