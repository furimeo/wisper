package lhqm.furimeo.wisper.database;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;

/**
 * Decides what happens when a database outgrows the ceiling the panel set for it.
 *
 * <h2>Why the decision is the panel's</h2>
 *
 * <p>Neither PostgreSQL nor MySQL has a per-database size limit, so nothing on the node can
 * refuse the write that goes over. The node measures and says {@code over_quota}; what that
 * means is a policy question, and a daemon quietly dropping a customer's writes would be
 * the worst possible answer to it (database.proto, design §8.1).
 *
 * <p>The answer here is to mark the row {@code SUSPENDED} and say so, everywhere the
 * database appears, with the two ways out: delete data, or raise the quota. Nothing is
 * revoked and nothing is deleted. That is a deliberate choice about which failure is worse:
 * a customer over their limit who keeps working and is nagged, against a customer whose
 * application stopped at 3 a.m. because a log table grew.
 *
 * <p>It is symmetric, which matters more than it sounds. The same call that suspends also
 * un-suspends: the next measurement under the limit - because rows were deleted, or because
 * {@link SetDatabaseQuota} raised it - puts the database back to {@code READY} without
 * anybody having to notice and click something.
 */
@Component
public class EnforceDatabaseQuota {

    private static final Logger log = LoggerFactory.getLogger(EnforceDatabaseQuota.class);

    /** The actor on the entries this writes: nobody pressed anything. */
    private static final String SWEEP = "database-quota";

    private final ManagedDatabaseRepository databases;
    private final AuditTrail audit;

    public EnforceDatabaseQuota(ManagedDatabaseRepository databases, AuditTrail audit) {
        this.databases = databases;
        this.audit = audit;
    }

    /**
     * Applies the policy to one database after a measurement.
     *
     * <p>Called from {@link RecordDatabaseStatus} for every grant in a status batch, so it
     * runs several times a minute per database and writes only when something changes.
     *
     * @param nodeSaysOver what the node computed against the quota <em>it</em> was given.
     *                     Trusted only as far as it agrees with the current row: a quota
     *                     raised a second ago has not reached the node yet, and suspending
     *                     against the old number would undo the customer's fix
     * @param sizeBytes    the measurement the node took
     * @return the state the row is now in, or null if there is no such database
     */
    @Transactional
    public ManagedDatabaseState apply(UUID databaseId, boolean nodeSaysOver, long sizeBytes) {
        ManagedDatabase database = databases.findById(databaseId).orElse(null);
        if (database == null) {
            return null;
        }
        // The panel's own comparison wins, because the panel owns the ceiling. The node's
        // flag is kept as corroboration for the log line when the two disagree.
        boolean over = sizeBytes > database.quotaBytes();
        if (over != nodeSaysOver) {
            log.debug("Node says over_quota={} for {} but {} of {} bytes says {}", nodeSaysOver,
                    database.name(), sizeBytes, database.quotaBytes(), over);
        }

        if (over && database.state() == ManagedDatabaseState.READY) {
            String message = "Over its " + database.quotaBytes() + " byte limit, currently "
                    + sizeBytes + ". Delete data or raise the quota; nothing has been blocked "
                    + "and nothing has been deleted.";
            databases.save(database.suspended(message));
            note(database, "database.quota_exceeded", message);
            return ManagedDatabaseState.SUSPENDED;
        }
        if (!over && database.state() == ManagedDatabaseState.SUSPENDED) {
            databases.save(database.resumed(Instant.now()));
            note(database, "database.quota_restored", "Back under its limit at " + sizeBytes
                    + " of " + database.quotaBytes() + " bytes");
            return ManagedDatabaseState.READY;
        }
        return database.state();
    }

    /**
     * Writes the trail entry for a suspension or a resumption.
     *
     * <p>The tenant is looked up rather than left null. Nobody pressed anything, so the
     * actor is the sweep - but the entry belongs in the customer's own trail, because "why
     * did my database stop being writable" is the question it exists to answer, and an
     * entry with no organization on it is one they never see.
     */
    private void note(ManagedDatabase database, String action, String detail) {
        UUID organizationId = databases.findOrganizationIdOf(database.id()).orElse(null);
        audit.record(AuditEntry.succeeded(AuditActor.system(SWEEP), action,
                AuditTarget.of("managed_database", database.id(), database.name()),
                organizationId, detail));
    }

    /**
     * Re-evaluates one database against its stored measurement, without a new report.
     *
     * <p>What {@link SetDatabaseQuota} calls after raising a limit, so the suspension lifts
     * on the same request rather than fifteen seconds later when the node next speaks. A
     * database nobody has measured yet is left alone: null usage is "not measured", not
     * zero, and treating it as zero would resume a database that is still over.
     */
    @Transactional
    public ManagedDatabaseState reevaluate(UUID databaseId) {
        ManagedDatabase database = databases.findById(databaseId).orElse(null);
        if (database == null || database.usedBytes() == null) {
            return database == null ? null : database.state();
        }
        return apply(databaseId, database.isOverQuota(), database.usedBytes());
    }
}
