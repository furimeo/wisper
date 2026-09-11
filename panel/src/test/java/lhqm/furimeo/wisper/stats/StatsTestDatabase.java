package lhqm.furimeo.wisper.stats;

import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import lhqm.furimeo.wisper.migration.SchemaMigrations;

/**
 * A migrated {@code wisper_test} and one scratch node to hang readings off.
 *
 * <p>Three tests in this package need a real PostgreSQL 17 - the rollup, the retention
 * sweep and the chart query all live in SQL, and none of them can be checked anywhere else.
 * They need the same four lines of setup, so the four lines are here rather than three
 * times.
 *
 * <p>Deliberately not a Spring context. Everything under test in this package takes a
 * {@link JdbcClient} and a {@link java.time.Clock}, so the panel's context would be several
 * hundred beans standing between a test and a {@code GROUP BY}.
 *
 * <p>The database itself is the one {@code docs/contracts/panel-configuration.md} tells a
 * developer to create. A missing one fails the test loudly: a test that quietly does not run
 * is the testing equivalent of a stub, and this is the only place the rollup arithmetic is
 * checked at all.
 */
final class StatsTestDatabase implements AutoCloseable {

    /** The same three values {@code src/test/resources/application-test.yml} carries. */
    private static final String URL = "jdbc:postgresql://localhost:5432/wisper_test";
    private static final String USER = System.getenv().getOrDefault("WISPER_TEST_DB_USER",
            "wisper");
    private static final String PASSWORD = System.getenv().getOrDefault("WISPER_TEST_DB_PASSWORD",
            "wisper");

    private final JdbcClient jdbc;
    private final UUID nodeId = UUID.randomUUID();

    private StatsTestDatabase(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Migrates the test database and inserts a node to attach readings to.
     *
     * @param namePrefix distinguishes this test class's node in a database somebody is
     *                   looking at by hand; it becomes part of {@code node.name}, which has
     *                   to satisfy {@code node_name_shape}
     */
    static StatsTestDatabase migrated(String namePrefix) throws SQLException {
        DataSource dataSource = new SimpleDriverDataSource(new org.postgresql.Driver(), URL, USER,
                PASSWORD);
        // A schema that is already current is a no-op, so every test class may ask.
        new SchemaMigrations(dataSource).afterPropertiesSet();

        StatsTestDatabase database = new StatsTestDatabase(JdbcClient.create(dataSource));
        database.jdbc.sql("INSERT INTO node (id, name) VALUES (:id, :name)")
                .param("id", database.nodeId)
                .param("name", namePrefix + "-" + database.nodeId.toString().substring(0, 8))
                .update();
        return database;
    }

    JdbcClient jdbc() {
        return jdbc;
    }

    /** The scratch node. A machine-level reading needs no service row, so there is none. */
    UUID nodeId() {
        return nodeId;
    }

    /** Empties both observability tables for this node, between tests. */
    void clearReadings() {
        jdbc.sql("DELETE FROM stat_sample WHERE node_id = :node").param("node", nodeId).update();
        jdbc.sql("DELETE FROM stat_rollup WHERE node_id = :node").param("node", nodeId).update();
    }

    /** Removes the node, which cascades to every reading and bucket that referenced it. */
    @Override
    public void close() {
        jdbc.sql("DELETE FROM node WHERE id = :id").param("id", nodeId).update();
    }
}
