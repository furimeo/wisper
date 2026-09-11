package lhqm.furimeo.wisper.placement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.crypto.SecretCipher;
import lhqm.furimeo.wisper.proto.v1.DatabaseEngine;
import lhqm.furimeo.wisper.proto.v1.DatabaseEngineSpec;
import lhqm.furimeo.wisper.proto.v1.DatabaseGrant;

/**
 * The database engines a node must run and the customer databases carved out of them.
 *
 * <p>Both halves are in the spec rather than created once by a command, and for the same
 * reason everything else here is: a node that lost its disk has to be able to rebuild what
 * it was holding. An engine created by an imperative call and then forgotten leaves a
 * machine with no PostgreSQL and a panel that believes it has one; a grant created that way
 * leaves an application unable to log in with credentials the panel is still displaying
 * (design §8.1).
 *
 * <p>Shared by default, one instance per engine per node, because a few hundred private
 * database containers at 30-50 MB of idle memory each is a machine nobody can afford. A
 * customer who pays for isolation gets a {@code DEDICATED} engine, which is another row in
 * the same table and another entry in this list.
 *
 * <p>Sizes come from {@code wisper.placement} and not from a column, because
 * {@code database_engine} has none - and the same settings are what
 * {@link LoadNodeCapacity} subtracts from the node, so the ceiling the node applies and the
 * space the scheduler believes is gone are the same number.
 */
@Component
public class BuildDatabaseSpecs {

    private static final String ENGINES = """
            SELECT id, engine, image, port, admin_username, admin_password
              FROM database_engine
             WHERE node_id = :nodeId
               AND desired_state = 'RUNNING'
             ORDER BY engine, port
            """;

    /**
     * Everything but the ones on their way out. A grant being deleted is left out of the
     * spec, which is how the node comes to remove it: omission is deletion.
     */
    private static final String GRANTS = """
            SELECT md.id, de.engine, de.mode, md.name, md.db_username, md.quota_bytes,
                   md.db_charset
              FROM managed_database md
              JOIN database_engine de ON de.id = md.database_engine_id
             WHERE de.node_id = :nodeId
               AND md.state <> 'DELETING'
             ORDER BY de.engine, md.name
            """;

    private final JdbcClient jdbc;
    private final SecretCipher cipher;
    private final PlacementSettings settings;

    public BuildDatabaseSpecs(JdbcClient jdbc, SecretCipher cipher, PlacementSettings settings) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.settings = settings;
    }

    /** The engine containers this node must be running. */
    @Transactional(readOnly = true)
    public List<DatabaseEngineSpec> enginesOn(UUID nodeId) {
        return jdbc.sql(ENGINES).param("nodeId", nodeId).query(this::engine).list();
    }

    /** One entry per customer database on any engine this node hosts. */
    @Transactional(readOnly = true)
    public List<DatabaseGrant> grantsOn(UUID nodeId) {
        return jdbc.sql(GRANTS).param("nodeId", nodeId).query(BuildDatabaseSpecs::grant).list();
    }

    private DatabaseEngineSpec engine(ResultSet row, int rowNumber) throws SQLException {
        return DatabaseEngineSpec.newBuilder()
                .setEngine(engineOf(row.getString("engine")))
                .setImage(row.getString("image"))
                .setListenPort(row.getInt("port"))
                .setAdminUsername(row.getString("admin_username"))
                // Decrypted on its way to the node and nowhere else. The panel generates
                // this password so it can rotate it and put it in a backup job; a secret
                // the node invented would be one the panel could not show or reuse.
                .setAdminPassword(cipher.decrypt(row.getString("admin_password")))
                // The engine's own id, not its data_path. The wire field is a volume id the
                // node resolves under its state root, and sending an absolute path where an
                // id is expected is exactly the confusion the id-only rule exists to stop.
                .setDataVolumeId(row.getObject("id", UUID.class).toString())
                .setNanoCpus(settings.databaseEngineMillicores() * 1_000_000L)
                .setMemoryBytes(settings.databaseEngineMemory().toBytes())
                .setMaxConnections(settings.databaseEngineMaxConnections())
                .build();
    }

    private static DatabaseGrant grant(ResultSet row, int rowNumber) throws SQLException {
        String charset = row.getString("db_charset");
        return DatabaseGrant.newBuilder()
                .setId(row.getObject("id", UUID.class).toString())
                .setEngine(engineOf(row.getString("engine")))
                .setDatabaseName(row.getString("name"))
                .setUsername(row.getString("db_username"))
                .setQuotaBytes(row.getLong("quota_bytes"))
                .setDedicatedInstance("DEDICATED".equals(row.getString("mode")))
                .setEncoding(charset == null ? "" : charset)
                .build();
    }

    /**
     * The two engines, from the text the CHECK constrains.
     *
     * <p>No default case that shrugs: an engine value the panel does not recognise means a
     * migration added one and this file was not updated, and a spec that quietly says
     * "unspecified" would have the node create nothing and report nothing wrong.
     */
    private static DatabaseEngine engineOf(String stored) {
        return switch (stored) {
            case "POSTGRES" -> DatabaseEngine.DATABASE_ENGINE_POSTGRES;
            case "MYSQL" -> DatabaseEngine.DATABASE_ENGINE_MYSQL;
            default -> throw new IllegalStateException(
                    "database_engine.engine holds \"" + stored + "\", which no spec can express");
        };
    }
}
