package lhqm.furimeo.wisper.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.migration.MigrationFiles.Migration;

/**
 * Brings the database up to date before anything else uses it.
 *
 * <p>No Flyway, no Liquibase: the whole job is "run these files in order, once each",
 * and that is short enough to read. What it does add on top of that is the part that
 * actually bites - a lock so two panels starting at the same moment cannot both apply
 * V12, and a checksum so a migration edited after it was applied is a startup failure
 * rather than a schema that quietly differs between machines.
 *
 * <p>This runs during bean initialization, not from a {@code CommandLineRunner}. Runners
 * execute after the context is refreshed, and db-scheduler starts polling
 * {@code scheduled_tasks} on {@code ContextRefreshedEvent} - which is before that. The
 * first boot of a fresh database would fail on a table this class had not created yet.
 */
@Component
public class SchemaMigrations implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrations.class);

    /**
     * Key for {@code pg_advisory_lock}. Arbitrary, but fixed forever: two processes only
     * exclude each other if they pick the same number.
     */
    private static final long LOCK_KEY = 6_723_951_884_012L;

    private final DataSource dataSource;

    public SchemaMigrations(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() throws SQLException {
        List<Migration> migrations = MigrationFiles.discover();

        /*
         * One connection for the whole run. A session-level advisory lock belongs to the
         * connection that took it, so taking it through a pooled JdbcTemplate would lock
         * on one connection and unlock on whichever came back next.
         */
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            lock(connection);
            try {
                ensureVersionTable(connection);
                Map<Integer, String> applied = appliedChecksums(connection);
                verifyUnchanged(migrations, applied);

                int pending = 0;
                for (Migration migration : migrations) {
                    if (!applied.containsKey(migration.version())) {
                        apply(connection, migration);
                        pending++;
                    }
                }

                if (pending == 0) {
                    log.info("Database schema is up to date at V{}", highestVersion(migrations));
                } else {
                    log.info("Applied {} migration(s); schema is now at V{}",
                            pending, highestVersion(migrations));
                }
            } finally {
                unlock(connection);
            }
        }
    }

    private static int highestVersion(List<Migration> migrations) {
        return migrations.isEmpty() ? 0 : migrations.get(migrations.size() - 1).version();
    }

    /**
     * Blocks until whichever process is migrating has finished. The second panel then
     * finds every migration already applied and starts normally, which is what a rolling
     * restart needs to do.
     */
    private static void lock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
            statement.setLong(1, LOCK_KEY);
            statement.execute();
        }
    }

    private static void unlock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, LOCK_KEY);
            statement.execute();
        }
    }

    private static void ensureVersionTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_version (
                        version     integer     PRIMARY KEY,
                        name        text        NOT NULL,
                        checksum    text        NOT NULL,
                        applied_at  timestamptz NOT NULL DEFAULT now(),
                        duration_ms bigint      NOT NULL
                    )
                    """);
        }
    }

    private static Map<Integer, String> appliedChecksums(Connection connection) throws SQLException {
        Map<Integer, String> applied = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT version, checksum FROM schema_version")) {
            while (rows.next()) {
                applied.put(rows.getInt("version"), rows.getString("checksum"));
            }
        }
        return applied;
    }

    /**
     * An applied migration is history. Editing one changes what a fresh database gets
     * without changing any database that already ran it, and the two drift apart with
     * nothing to say so. Fix it forward with a new file.
     */
    private static void verifyUnchanged(List<Migration> migrations, Map<Integer, String> applied) {
        for (Migration migration : migrations) {
            String recorded = applied.get(migration.version());
            if (recorded != null && !recorded.equals(migration.checksum())) {
                throw new IllegalStateException(migration.filename()
                        + " has changed since it was applied to this database. Migrations are "
                        + "immutable once they run - add a new V" + (highestVersion(migrations) + 1)
                        + " file that makes the correction instead.");
            }
        }
    }

    /**
     * One file, one transaction. A migration that fails halfway leaves the database as
     * it was and {@code schema_version} unchanged, so the next start retries it rather
     * than skipping past a half-created set of tables.
     */
    private static void apply(Connection connection, Migration migration) throws SQLException {
        log.info("Applying {}", migration.filename());
        long startedAt = System.nanoTime();

        connection.setAutoCommit(false);
        try {
            try (Statement statement = connection.createStatement()) {
                for (String sql : SqlStatements.split(migration.sql())) {
                    statement.execute(sql);
                }
            }
            long durationMillis = (System.nanoTime() - startedAt) / 1_000_000;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO schema_version (version, name, checksum, duration_ms)
                    VALUES (?, ?, ?, ?)
                    """)) {
                statement.setInt(1, migration.version());
                statement.setString(2, migration.name());
                statement.setString(3, migration.checksum());
                statement.setLong(4, durationMillis);
                statement.executeUpdate();
            }
            connection.commit();
            log.info("Applied {} in {} ms", migration.filename(), durationMillis);
        } catch (SQLException e) {
            connection.rollback();
            throw new IllegalStateException("Migration " + migration.filename() + " failed: "
                    + e.getMessage(), e);
        } finally {
            connection.setAutoCommit(true);
        }
    }
}
