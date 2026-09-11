package lhqm.furimeo.wisper.database;

import java.time.Instant;
import java.util.Locale;
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
import lhqm.furimeo.wisper.crypto.SecretCipher;
import lhqm.furimeo.wisper.node.NodeConnections;
import lhqm.furimeo.wisper.node.NodeOffline;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.proto.v1.CommandResult;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Gives one database login a new password and takes the old one away.
 *
 * <p>The customer's "rotate" button, and an operator's answer to a credential that leaked.
 * Either way the promise is the same and it is the only thing that matters here: when this
 * returns successfully, <strong>the previous password no longer works</strong>. Both
 * engines do that with a single {@code ALTER}, so there is no window in which two passwords
 * are valid.
 *
 * <h2>The engine goes first, the panel second</h2>
 *
 * <p>The new value reaches the engine before it is written to the row, and the row is only
 * written if the engine accepted it. The other order is tempting - store, then push - and
 * it is wrong for rotation specifically: somebody rotating a leaked credential would be
 * told it was revoked while the leaked one still worked. Failing here leaves the old
 * password in place, working, and says so.
 *
 * <p>The cost of this ordering is a narrow window: the engine accepts the new password and
 * the panel fails to commit it. The application keeps running on a password only it knows
 * and the panel cannot show one, which is visible, safe, and fixed by pressing rotate
 * again.
 *
 * <h2>Why nothing is held open across the network</h2>
 *
 * <p>The read, the call and the write are three steps and only the first and last are
 * transactions. Waiting for a node with a database connection checked out would hold one of
 * sixteen for the whole round trip, on a page a customer may well press twice.
 */
@Component
public class RotateDatabasePassword {

    private final TransactionTemplate transactions;
    private final ManagedDatabaseRepository databases;
    private final DatabaseEngineRepository engines;
    private final NodeConnections nodes;
    private final SecretCipher cipher;
    private final DatabaseSettings settings;
    private final AuditTrail audit;

    public RotateDatabasePassword(PlatformTransactionManager transactionManager,
                                  ManagedDatabaseRepository databases,
                                  DatabaseEngineRepository engines, NodeConnections nodes,
                                  SecretCipher cipher, DatabaseSettings settings,
                                  AuditTrail audit) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.databases = databases;
        this.engines = engines;
        this.nodes = nodes;
        this.cipher = cipher;
        this.settings = settings;
        this.audit = audit;
    }

    /**
     * Rotates the login of {@code databaseId} and returns the details to show once.
     *
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        when the database is not this
     *                                                  organization's
     * @throws RequestRejected                          when it has not been created yet, or
     *                                                  the node did not accept the change
     */
    public ConnectionString rotate(AuditActor actor, Membership membership, UUID databaseId) {
        membership.requireWrite("database.rotate_password");

        Subject subject = transactions.execute(status -> load(membership, databaseId));
        String password = DatabasePassword.generate();

        lhqm.furimeo.wisper.proto.v1.RotateDatabasePassword command =
                lhqm.furimeo.wisper.proto.v1.RotateDatabasePassword.newBuilder()
                        .setId(subject.database().id().toString())
                        .setUsername(subject.database().dbUsername())
                        .setEngine(subject.engine().engine().toWire())
                        .setNewPassword(password)
                        .build();

        CommandResult result;
        try {
            result = nodes.call(subject.engine().nodeId(),
                            PanelMessage.newBuilder().setRotateDatabasePassword(command))
                    .orTimeout(settings.commandTimeout().toSeconds(), TimeUnit.SECONDS)
                    .join();
        } catch (NodeOffline | CompletionException unreachable) {
            throw refuse(actor, membership, subject, "The node running this database is not "
                    + "answering, so the password was not changed. The one you have still works.");
        }
        if (!result.getOk()) {
            throw refuse(actor, membership, subject, blankToDefault(result.getDetail(),
                    "The engine refused the new password. The one you have still works."));
        }

        Instant at = Instant.now();
        String envelope = cipher.encrypt(password);
        transactions.executeWithoutResult(status ->
                databases.findById(databaseId).ifPresent(current ->
                        databases.save(current.withPassword(envelope, at))));

        audit.record(AuditEntry.succeeded(actor, "database.rotate_password",
                AuditTarget.of("managed_database", subject.database().id(),
                        subject.database().name()),
                membership.organizationId(),
                // The field that changed, never what it changed to (panel-ports.md §2.4).
                "Password rotated for login " + subject.database().dbUsername()));

        return ConnectionString.of(subject.database(), subject.engine(), password);
    }

    private Subject load(Membership membership, UUID databaseId) {
        ManagedDatabase database = databases.findOwnedBy(databaseId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("managed_database", databaseId));
        if (!database.state().isUsable()) {
            throw new RequestRejected(null, database.state() == ManagedDatabaseState.PENDING
                    ? "This database is still being created. Try again in a moment."
                    : "This database is " + database.state().name().toLowerCase(Locale.ROOT)
                            + ", so there is no login to rotate.");
        }
        DatabaseEngine engine = engines.findById(database.databaseEngineId())
                .orElseThrow(() -> NotFoundException.of("database_engine",
                        database.databaseEngineId()));
        return new Subject(database, engine);
    }

    /**
     * Records the refusal and hands back the exception to throw.
     *
     * <p>Returned rather than thrown so the call site reads {@code throw refuse(...)} and
     * the compiler can see the method ends there.
     */
    private RequestRejected refuse(AuditActor actor, Membership membership, Subject subject,
                                   String message) {
        audit.record(AuditEntry.failed(actor, "database.rotate_password",
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
