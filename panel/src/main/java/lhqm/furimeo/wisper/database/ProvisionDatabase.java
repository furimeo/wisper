package lhqm.furimeo.wisper.database;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.crypto.SecretCipher;
import lhqm.furimeo.wisper.jobs.JobQueue;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.OrganizationRepository;
import lhqm.furimeo.wisper.org.QuotaGuard;
import lhqm.furimeo.wisper.org.QuotaResource;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.project.Project;
import lhqm.furimeo.wisper.project.ProjectRepository;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Gives a project a database: writes the row, publishes the grant and queues the work.
 *
 * <p>The only door into database creation. The screen and the API route both come through
 * here, which is why the quota check, the name collision and the audit entry are here and
 * not duplicated in a controller.
 *
 * <h2>Everything commits together</h2>
 *
 * <p>The row, the spec bump and the job are one transaction. That is the whole reason the
 * queue is PostgreSQL: a provision job for a row that was rolled back, or a row nothing
 * will ever create, cannot occur.
 *
 * <h2>Two paths to the same database, on purpose</h2>
 *
 * <p>The grant goes into the node's spec <em>and</em> a command is queued. The spec is what
 * makes it survive - a node that lost its disk rebuilds from the spec rather than leaving
 * an application unable to log in - and the command is what makes it happen now, because a
 * customer is watching a screen for a connection string and the reconcile loop is fifteen
 * seconds wide (design §5.1, §8.1).
 *
 * <p>The password is generated here, encrypted before it is stored and decrypted only on
 * the two paths that need the plaintext: the command that reaches the engine, and the
 * connection details a customer explicitly asks to see.
 */
@Component
public class ProvisionDatabase {

    private final ManagedDatabaseRepository databases;
    private final ChooseDatabaseEngine chooseEngine;
    private final ProjectRepository projects;
    private final OrganizationRepository organizations;
    private final QuotaGuard quotas;
    private final SecretCipher cipher;
    private final JobQueue jobs;
    private final PublishNodeSpec specs;
    private final DatabaseSettings settings;
    private final AuditTrail audit;

    public ProvisionDatabase(ManagedDatabaseRepository databases,
                             ChooseDatabaseEngine chooseEngine, ProjectRepository projects,
                             OrganizationRepository organizations, QuotaGuard quotas,
                             SecretCipher cipher, JobQueue jobs, PublishNodeSpec specs,
                             DatabaseSettings settings, AuditTrail audit) {
        this.databases = databases;
        this.chooseEngine = chooseEngine;
        this.projects = projects;
        this.organizations = organizations;
        this.quotas = quotas;
        this.cipher = cipher;
        this.jobs = jobs;
        this.specs = specs;
        this.settings = settings;
        this.audit = audit;
    }

    /**
     * Creates a database in {@code projectId}.
     *
     * @param requested  what the customer typed; the stored name is this prefixed with the
     *                   organization's slug, so two tenants on a shared engine cannot
     *                   collide and neither learns of the other
     * @param quotaBytes the storage ceiling, or a non-positive value for the configured
     *                   default
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        when the project is not this
     *                                                  organization's
     * @throws RequestRejected                          for an unusable name, a name the
     *                                                  organization already used, or a
     *                                                  quota outside what one database may
     *                                                  have
     * @throws lhqm.furimeo.wisper.org.QuotaExceeded    at the plan's database ceiling, or
     *                                                  when the organization is suspended
     */
    @Transactional
    public ManagedDatabase create(AuditActor actor, Membership membership, UUID projectId,
                                  EngineKind engine, String requested, long quotaBytes,
                                  boolean dedicated) {
        membership.requireWrite("database.create");

        Project project = projects.findByIdAndOrganizationId(projectId,
                membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("project", projectId));
        if (project.isArchived()) {
            throw new RequestRejected(null, "This project is archived. Restore it before adding "
                    + "a database to it.");
        }
        if (!DatabaseIdentifier.isUsableRequest(requested)) {
            throw new RequestRejected("name", "That name will not work. "
                    + DatabaseIdentifier.rule());
        }

        long quota = resolveQuota(quotaBytes);
        String slug = organizations.findById(membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("organization",
                        membership.organizationId()))
                .slug();
        String name = DatabaseIdentifier.nameFor(slug, requested);

        // Before the quota, because "you already have one called that" is a better answer
        // than "you are at your limit" when both are true.
        DatabaseEngine target = chooseEngine.forNewDatabase(actor, membership.organizationId(),
                engine, dedicated);
        if (databases.existsOnEngineNamed(target.id(), name)) {
            throw new RequestRejected("name", "You already have a database called \"" + requested
                    + "\" on this engine. Pick another name.");
        }

        quotas.require(membership.organizationId(), QuotaResource.MANAGED_DATABASE, 1);

        UUID id = UUID.randomUUID();
        ManagedDatabase created = databases.save(ManagedDatabase.requested(id, projectId,
                target.id(), name, DatabaseIdentifier.usernameFor(name, id),
                cipher.encrypt(DatabasePassword.generate()),
                engine.defaultEncoding(), null, quota));

        // The grant belongs in the spec whatever happens to the command below: it is what
        // rebuilds the account on a node that lost its disk.
        specs.toNode(target.nodeId(), "database " + name + " added");
        jobs.enqueue(DatabaseTasks.PROVISION, created.id().toString(), new ProvisionJob(id));

        audit.record(AuditEntry.succeeded(actor, "database.create",
                AuditTarget.of("managed_database", created.id(), name),
                membership.organizationId(),
                engine.label() + (dedicated ? " (dedicated)" : " (shared)") + " in project "
                        + project.slug() + ", quota " + quota + " bytes"));
        return created;
    }

    /**
     * The ceiling for a new database.
     *
     * <p>A non-positive figure is the form not asking, which is the common case and gets
     * the configured default. Anything above {@code wisper.database.max-quota} is refused
     * rather than clamped: silently giving somebody a tenth of what they asked for is how
     * a database fills up at three in the morning.
     */
    private long resolveQuota(long requested) {
        if (requested <= 0) {
            return settings.defaultQuotaBytes();
        }
        if (requested < DatabaseSettings.MIN_QUOTA_BYTES) {
            throw new RequestRejected("quotaBytes", "A database needs at least "
                    + DatabaseSettings.MIN_QUOTA_BYTES / (1024 * 1024) + " MiB.");
        }
        if (requested > settings.maxQuotaBytes()) {
            throw new RequestRejected("quotaBytes", "One database may be at most "
                    + settings.maxQuotaBytes() / (1024L * 1024 * 1024) + " GiB. Beyond that the "
                    + "answer is a dedicated instance rather than a bigger number.");
        }
        return requested;
    }
}
