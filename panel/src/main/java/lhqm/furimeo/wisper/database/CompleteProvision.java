package lhqm.furimeo.wisper.database;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.proto.v1.DatabaseProvisioned;

/**
 * Records the outcome of a provision, whichever way it went.
 *
 * <p>The {@code DatabaseProvisioned} entry point is the one {@code grpc} calls when the
 * frame arrives on its own rather than as the answer to a command (panel-ports.md §3), and
 * it is the same one {@link ProvisionOnNode} uses for the answer it waited for. Whichever
 * arrives first wins; the second finds a row that is already {@code READY} and does
 * nothing. One entry point rather than two is what makes that true without a lock.
 *
 * <h2>What is written, and what is deliberately not</h2>
 *
 * <p>{@code state}, {@code provisioned_at} and {@code last_error} are panel columns
 * (schema.md §2). They are written here from a node's report, which is not a contradiction:
 * the node supplies the observation and the panel decides what it means. Nothing on this
 * path touches {@code used_bytes} - that is the node's, and {@link TrackDatabaseSize} owns
 * it.
 *
 * <p>{@code DatabaseProvisioned} also carries a host, a port and an engine version. Those
 * three live on {@code database_engine} and are panel intent, so they are not written back;
 * a difference between what the panel published and what the node reports is logged at
 * {@code WARN}, because it means the connection string the customer is about to be shown
 * does not point at the container that answered.
 */
@Component
public class CompleteProvision {

    private static final Logger log = LoggerFactory.getLogger(CompleteProvision.class);

    private final ManagedDatabaseRepository databases;
    private final DatabaseEngineRepository engines;

    public CompleteProvision(ManagedDatabaseRepository databases,
                             DatabaseEngineRepository engines) {
        this.databases = databases;
        this.engines = engines;
    }

    /**
     * The node created the database and the role.
     *
     * <p>The signature {@code grpc} compiles against (panel-ports.md §3).
     */
    @Transactional
    public void accept(UUID managedDatabaseId, DatabaseProvisioned result) {
        ManagedDatabase database = databases.findById(managedDatabaseId).orElse(null);
        if (database == null) {
            log.info("Node reported a provisioned database {} the panel no longer has",
                    managedDatabaseId);
            return;
        }
        if (database.state() == ManagedDatabaseState.READY) {
            return;
        }
        if (database.state() == ManagedDatabaseState.DELETING) {
            // A drop overtook the provision. The grant has already left the spec, so the
            // node will remove what it just made; marking this READY would put a database
            // the customer deleted back on their screen.
            log.info("Database {} was dropped while it was being provisioned", managedDatabaseId);
            return;
        }
        warnOnMismatch(database, result);
        databases.save(database.ready(Instant.now()));
    }

    /**
     * The node tried and could not.
     *
     * <p>Terminal. The engine refused the statement, so the identical request in five
     * minutes will be refused identically; the row says why and the customer's next move is
     * to change something or delete it.
     */
    @Transactional
    public void failed(UUID managedDatabaseId, String detail) {
        databases.findById(managedDatabaseId)
                .filter(database -> database.state() == ManagedDatabaseState.PENDING)
                .ifPresent(database -> databases.save(database.failed(detail)));
    }

    /**
     * The node never answered.
     *
     * <p>Not terminal, and that distinction is the point: the row stays {@code PENDING} and
     * the message says the node is not reachable, because the grant is in the node's spec
     * and the job is being retried. Marking this {@code FAILED} would tell a customer their
     * database could not be created when in fact nobody has asked the machine yet.
     */
    @Transactional
    public void unreachable(UUID managedDatabaseId, String detail) {
        databases.findById(managedDatabaseId)
                .filter(database -> database.state() == ManagedDatabaseState.PENDING)
                .ifPresent(database -> databases.save(database.noting(
                        "Waiting for the node, which has not answered yet: " + detail)));
    }

    /**
     * Says so loudly when the address the node published is not the one the panel is about
     * to print in a connection string.
     */
    private void warnOnMismatch(ManagedDatabase database, DatabaseProvisioned result) {
        engines.findById(database.databaseEngineId()).ifPresent(engine -> {
            if (!result.getHost().isEmpty() && !result.getHost().equals(engine.host())) {
                log.warn("Node reports database {} on host \"{}\" but the panel publishes \"{}\";"
                                + " the connection string will not resolve",
                        database.name(), result.getHost(), engine.host());
            }
            if (result.getPort() != 0 && result.getPort() != engine.port()) {
                log.warn("Node reports database {} on port {} but the panel publishes {}",
                        database.name(), result.getPort(), engine.port());
            }
            if (!result.getEngineVersion().isEmpty()
                    && !result.getEngineVersion().startsWith(engine.engineVersion())) {
                log.warn("Engine {} runs {} but the panel records {}; a dump taken here may not "
                                + "restore where the panel thinks it will",
                        engine.host(), result.getEngineVersion(), engine.engineVersion());
            }
        });
    }
}
