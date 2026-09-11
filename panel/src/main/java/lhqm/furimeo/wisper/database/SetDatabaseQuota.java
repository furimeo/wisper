package lhqm.furimeo.wisper.database;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Changes how large one database is allowed to get.
 *
 * <p>The other half of {@link EnforceDatabaseQuota}, and the reason a suspension is
 * something a customer can get out of. Without this, a database that grew past its ceiling
 * would be marked over quota forever and the only escape would be deleting data, which is
 * not a choice anybody should be forced into by a number somebody typed once.
 *
 * <p>The new ceiling reaches the node in the spec - {@code DatabaseGrant.quota_bytes} - so
 * the node's own {@code over_quota} arithmetic uses the same figure the panel does on the
 * next pass. The suspension is lifted here rather than being waited for, because the
 * customer has just raised the limit and a screen that still says "over quota" for another
 * fifteen seconds reads as the button not having worked.
 *
 * <p>Lowering is allowed and does not delete anything. It can put a database straight into
 * {@code SUSPENDED}, which is the honest outcome: the limit is now below what is stored, and
 * saying so is better than refusing the change and leaving somebody wondering why.
 */
@Component
public class SetDatabaseQuota {

    private final ManagedDatabaseRepository databases;
    private final DatabaseEngineRepository engines;
    private final EnforceDatabaseQuota quotaPolicy;
    private final PublishNodeSpec specs;
    private final DatabaseSettings settings;
    private final AuditTrail audit;

    public SetDatabaseQuota(ManagedDatabaseRepository databases, DatabaseEngineRepository engines,
                            EnforceDatabaseQuota quotaPolicy, PublishNodeSpec specs,
                            DatabaseSettings settings, AuditTrail audit) {
        this.databases = databases;
        this.engines = engines;
        this.quotaPolicy = quotaPolicy;
        this.specs = specs;
        this.settings = settings;
        this.audit = audit;
    }

    /**
     * Sets the ceiling on {@code databaseId} to {@code quotaBytes}.
     *
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        when it is not this organization's
     * @throws RequestRejected                          for a figure outside what one
     *                                                  database may have
     */
    @Transactional
    public ManagedDatabase set(AuditActor actor, Membership membership, UUID databaseId,
                               long quotaBytes) {
        membership.requireWrite("database.set_quota");

        ManagedDatabase database = databases.findOwnedBy(databaseId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("managed_database", databaseId));
        if (quotaBytes < DatabaseSettings.MIN_QUOTA_BYTES) {
            throw new RequestRejected("quotaBytes", "A database needs at least "
                    + DatabaseSettings.MIN_QUOTA_BYTES / (1024 * 1024) + " MiB.");
        }
        if (quotaBytes > settings.maxQuotaBytes()) {
            throw new RequestRejected("quotaBytes", "One database may be at most "
                    + settings.maxQuotaBytes() / (1024L * 1024 * 1024) + " GiB. Beyond that the "
                    + "answer is a dedicated instance rather than a bigger number.");
        }
        if (quotaBytes == database.quotaBytes()) {
            return database;
        }

        long previous = database.quotaBytes();
        ManagedDatabase saved = databases.save(database.withQuota(quotaBytes));

        DatabaseEngine engine = engines.findById(database.databaseEngineId())
                .orElseThrow(() -> NotFoundException.of("database_engine",
                        database.databaseEngineId()));
        specs.toNode(engine.nodeId(), "quota changed on database " + database.name());

        // Applied immediately against the last measurement, so raising the limit clears the
        // suspension on this request instead of on the node's next status batch.
        quotaPolicy.reevaluate(databaseId);

        audit.record(AuditEntry.succeeded(actor, "database.set_quota",
                AuditTarget.of("managed_database", database.id(), database.name()),
                membership.organizationId(),
                "Quota changed from " + previous + " to " + quotaBytes + " bytes"));
        return saved;
    }
}
