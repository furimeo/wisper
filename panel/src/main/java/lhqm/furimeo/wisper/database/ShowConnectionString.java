package lhqm.furimeo.wisper.database;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.crypto.SecretCipher;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Decrypts one database credential, once, because somebody asked for it.
 *
 * <p>This is the only place in the panel that turns {@code managed_database.db_password}
 * back into a password, and everything about it is arranged around that. It is a separate
 * request rather than a field on the database page, so the credential is not in the props
 * of a page the customer merely visited, not in the browser's back-forward cache and not in
 * the screenshot they send to support. And it writes an audit entry <strong>before</strong>
 * it decrypts, so a reveal that happened is recorded even if the response never reached the
 * browser.
 *
 * <h2>Who may</h2>
 *
 * <p>Write access, not read. A {@code VIEWER} can see that a database exists, how full it is
 * and whether it is healthy; the credential is the thing that lets somebody change data, and
 * handing it to a role defined as read-only would make the role a label rather than a
 * permission.
 */
@Component
public class ShowConnectionString {

    private final ManagedDatabaseRepository databases;
    private final DatabaseEngineRepository engines;
    private final SecretCipher cipher;
    private final AuditTrail audit;

    public ShowConnectionString(ManagedDatabaseRepository databases,
                                DatabaseEngineRepository engines, SecretCipher cipher,
                                AuditTrail audit) {
        this.databases = databases;
        this.engines = engines;
        this.cipher = cipher;
        this.audit = audit;
    }

    /**
     * The full connection details for {@code databaseId}, including the password.
     *
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        when it is not this organization's
     * @throws RequestRejected                          when it has not been created yet, so
     *                                                  there is nothing to connect to
     */
    @Transactional
    public ConnectionString reveal(AuditActor actor, Membership membership, UUID databaseId) {
        // database.reveal_credentials is not in the vocabulary in panel-ports.md §2.4. It
        // has to exist: "credentials are revealed only on explicit request, which is
        // audited" is the requirement, and filing it under database.rotate_password would
        // make the trail claim a password changed when it did not.
        membership.requireWrite("database.reveal_credentials");

        ManagedDatabase database = databases.findOwnedBy(databaseId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("managed_database", databaseId));
        if (!database.state().isUsable()) {
            throw new RequestRejected(null, database.state() == ManagedDatabaseState.PENDING
                    ? "This database is still being created. Its connection details will be here "
                            + "as soon as the node confirms it."
                    : "This database has no working login to show.");
        }
        DatabaseEngine engine = engines.findById(database.databaseEngineId())
                .orElseThrow(() -> NotFoundException.of("database_engine",
                        database.databaseEngineId()));

        // Recorded before the decryption, not after. A reveal whose response was lost is
        // still a reveal, and the entry somebody comes looking for is the attempt.
        audit.record(AuditEntry.succeeded(actor, "database.reveal_credentials",
                AuditTarget.of("managed_database", database.id(), database.name()),
                membership.organizationId(),
                "Connection details shown for login " + database.dbUsername() + " on "
                        + engine.describe()));

        return ConnectionString.of(database, engine, cipher.decrypt(database.dbPassword()));
    }
}
