package lhqm.furimeo.wisper.database;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * A PostgreSQL or MySQL server running on one node, inside which customers get databases.
 * Maps the {@code database_engine} table, column for column.
 *
 * <p>Shared by default, one instance per engine per node. The reason is arithmetic rather
 * than taste: an idle PostgreSQL holds 30-50 MB of resident memory and a node carries
 * hundreds of customers, so a private container each is the entire machine (design §8.1).
 * A customer who pays for isolation gets a {@link EngineMode#DEDICATED} row here, owned by
 * their organization.
 *
 * <p>The row has two owners and the split runs through the middle of it. The panel writes
 * everything down to {@code desiredState}; {@code reportedState}, {@code reportedAt},
 * {@code diskBytesUsed} and {@code lastError} are written from a node's status batch by
 * {@link RecordDatabaseStatus} and by nothing else (schema.md §2). Every wither below
 * therefore copies the node's four columns through untouched.
 *
 * @param host          the name the engine answers to on the node's private network.
 *                      Derived from the id by {@link #hostFor}, so both sides can compute
 *                      it: {@code DatabaseEngineSpec} carries no host field, and a name
 *                      only one side can work out is a name the other cannot connect to
 * @param port          published on the node's private interface only; never public
 * @param adminPassword an encrypted envelope. The panel generates it, because a secret the
 *                      node invented is one the panel could neither rotate nor put in a
 *                      backup job
 * @param dataPath      where the engine's data directory lives on the node, for display
 *                      and for an operator with a shell. The spec sends the engine id and
 *                      the node resolves it under its own state root; the panel never
 *                      sends an absolute path
 * @param reportedState what the node last said, or null before it has said anything. Null
 *                      is "not reported yet", which is not the same as stopped
 * @param version       null means new; see schema.md §1
 */
public record DatabaseEngine(
        @Id UUID id,
        UUID nodeId,
        EngineKind engine,
        String engineVersion,
        EngineMode mode,
        UUID organizationId,

        String image,
        String host,
        int port,
        String adminUsername,
        String adminPassword,
        String dataPath,

        EngineDesiredState desiredState,

        String reportedState,
        Instant reportedAt,
        Long diskBytesUsed,
        String lastError,

        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    public DatabaseEngine {
        if (mode.needsOwner() != (organizationId != null)) {
            // The same rule as database_engine_dedicated_has_owner, checked here so the
            // failure names the mistake instead of arriving as a constraint violation.
            throw new IllegalArgumentException("A DEDICATED engine belongs to exactly one "
                    + "organization and a SHARED engine belongs to none");
        }
    }

    /**
     * An instance every tenant on the node shares.
     *
     * @param adminPasswordEnvelope already encrypted; this record never holds plaintext
     */
    public static DatabaseEngine shared(UUID id, UUID nodeId, EngineKind engine, String version,
                                        String image, int port, String adminPasswordEnvelope) {
        return new DatabaseEngine(id, nodeId, engine, version, EngineMode.SHARED, null,
                image, hostFor(id, engine), port, engine.adminUsername(), adminPasswordEnvelope,
                dataPathFor(id), EngineDesiredState.RUNNING,
                null, null, null, null, null, null, null);
    }

    /** An instance one organization paid to have to itself. */
    public static DatabaseEngine dedicated(UUID id, UUID nodeId, UUID organizationId,
                                           EngineKind engine, String version, String image,
                                           int port, String adminPasswordEnvelope) {
        return new DatabaseEngine(id, nodeId, engine, version, EngineMode.DEDICATED,
                organizationId, image, hostFor(id, engine), port, engine.adminUsername(),
                adminPasswordEnvelope, dataPathFor(id), EngineDesiredState.RUNNING,
                null, null, null, null, null, null, null);
    }

    /**
     * The network name for an engine with this id.
     *
     * <p>Deterministic on purpose. {@code DatabaseEngineSpec} carries the engine's id as
     * {@code data_volume_id} and no host name, so the node has to be able to derive the
     * alias it publishes the container under from the same value the panel puts in a
     * connection string. Twelve hex characters of the id: short enough to read in
     * {@code docker ps}, wide enough that two engines on one node cannot collide.
     */
    public static String hostFor(UUID id, EngineKind engine) {
        String shortId = id.toString().replace("-", "").substring(0, 12);
        return "wisper-" + engine.name().toLowerCase(Locale.ROOT) + "-" + shortId;
    }

    /** Where the data directory sits on the node, decided once and never moved. */
    public static String dataPathFor(UUID id) {
        return "/var/lib/wisper/databases/" + id;
    }

    /** Start or stop it. The node's four columns are left exactly as the node left them. */
    public DatabaseEngine wanting(EngineDesiredState state) {
        return new DatabaseEngine(id, nodeId, engine, engineVersion, mode, organizationId,
                image, host, port, adminUsername, adminPassword, dataPath, state,
                reportedState, reportedAt, diskBytesUsed, lastError,
                createdAt, updatedAt, version);
    }

    /** A new image and the version it carries, applied together so they cannot disagree. */
    public DatabaseEngine running(String newImage, String newVersion) {
        return new DatabaseEngine(id, nodeId, engine, newVersion, mode, organizationId,
                newImage, host, port, adminUsername, adminPassword, dataPath, desiredState,
                reportedState, reportedAt, diskBytesUsed, lastError,
                createdAt, updatedAt, version);
    }

    /** A re-keyed administrative login, as an envelope. */
    public DatabaseEngine withAdminPassword(String envelope) {
        return new DatabaseEngine(id, nodeId, engine, engineVersion, mode, organizationId,
                image, host, port, adminUsername, envelope, dataPath, desiredState,
                reportedState, reportedAt, diskBytesUsed, lastError,
                createdAt, updatedAt, version);
    }

    public boolean isShared() {
        return mode == EngineMode.SHARED;
    }

    /** Whether an operator has asked for this container to be up. */
    public boolean shouldRun() {
        return desiredState == EngineDesiredState.RUNNING;
    }

    /** Whether the node has confirmed it is serving. False also covers "never reported". */
    public boolean isReportedRunning() {
        return "RUNNING".equals(reportedState);
    }

    /** "PostgreSQL 17 on wisper-postgres-1a2b3c4d5e6f:15432", for a log line or a screen. */
    public String describe() {
        return engine.label() + " " + engineVersion + " on " + host + ":" + port;
    }
}
