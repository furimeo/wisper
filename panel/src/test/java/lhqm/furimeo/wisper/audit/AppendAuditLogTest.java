package lhqm.furimeo.wisper.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import lhqm.furimeo.wisper.migration.SchemaMigrations;

/**
 * The audit trail against a real PostgreSQL 17.
 *
 * <p>Two things can only be proven here, and both of them are properties every other
 * package depends on without owning.
 *
 * <p><strong>The entry outlives the action.</strong> {@link AppendAuditLog} writes in
 * {@code REQUIRES_NEW}, so "somebody tried to do this and it failed" survives the failure
 * rolling back - which is exactly the line an incident is reconstructed from
 * (panel-ports.md §2.4). A mock cannot show that; a suspended transaction and a real
 * rollback can. Note that this is deliberately <em>not</em> the caller's transaction: an
 * audit row that vanishes with the change it records is a trail with holes in it precisely
 * where the interesting events are.
 *
 * <p><strong>The row maps to the table.</strong> {@link AuditLog} is written by Spring Data
 * JDBC, so the enum-to-text columns, the {@code Instant} that has to reach a
 * {@code timestamptz}, the application-assigned id and the null {@code @Version} that makes
 * it an insert are all conventions nothing else in this package exercises. So is the
 * filtered read in {@link SearchAuditLog}, whose date bounds are the one part of that query
 * a unit test cannot reach.
 *
 * <p>Needs the {@code wisper_test} database from
 * {@code docs/contracts/panel-configuration.md}. It is not skipped when the database is
 * absent: a test that quietly does not run is the testing equivalent of a stub.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppendAuditLogTest {

    /**
     * The same three values {@code src/test/resources/application-test.yml} carries. This
     * context is built by hand rather than by Spring Boot because the panel's full context
     * is far more than an audit row needs, so the properties are not read from there.
     */
    private static final String URL = "jdbc:postgresql://localhost:5432/wisper_test";
    private static final String USER = System.getenv().getOrDefault("WISPER_TEST_DB_USER",
            "wisper");
    private static final String PASSWORD = System.getenv().getOrDefault("WISPER_TEST_DB_PASSWORD",
            "wisper");

    /** Truncated to microseconds: PostgreSQL's {@code timestamptz} keeps no more. */
    private static final Instant OCCURRED =
            Instant.parse("2026-04-02T08:30:00Z").truncatedTo(ChronoUnit.MICROS);

    private AnnotationConfigApplicationContext context;
    private AppendAuditLog append;
    private SearchAuditLog search;
    private TransactionTemplate transactions;
    private JdbcClient jdbc;

    /** This run's marker, so a failed run cannot leave rows another run then counts. */
    private final UUID target = UUID.randomUUID();

    @BeforeAll
    void startAgainstTheTestDatabase() throws SQLException {
        DataSource dataSource = new SimpleDriverDataSource(new org.postgresql.Driver(), URL, USER,
                PASSWORD);
        // Before the context, not as a bean in it: nothing here needs the ordering dance
        // WisperApplication does, and a schema that is already current is a no-op.
        new SchemaMigrations(dataSource).afterPropertiesSet();

        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.register(Wiring.class);
        context.refresh();

        append = context.getBean(AppendAuditLog.class);
        search = context.getBean(SearchAuditLog.class);
        jdbc = context.getBean(JdbcClient.class);
        transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    /**
     * The table is append-only and every test here writes to it, so each one starts from
     * its own empty slice rather than from whatever ran before it.
     */
    @BeforeEach
    void removeWhatTheLastTestWrote() {
        jdbc.sql("DELETE FROM audit_log WHERE target_id = :target").param("target", target)
                .update();
    }

    @AfterAll
    void stop() {
        if (jdbc != null) {
            jdbc.sql("DELETE FROM audit_log WHERE target_id = :target").param("target", target)
                    .update();
        }
        if (context != null) {
            context.close();
        }
    }

    @Test
    void theEntrySurvivesTheActionItRecordsBeingRolledBack() {
        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() ->
                transactions.executeWithoutResult(status -> {
                    append.write(denial("service.delete", "tried to delete the API service"),
                            OCCURRED);
                    throw new IllegalStateException("the action failed after it was audited");
                }));

        assertThat(rowsForThisRun()).isEqualTo(1);
    }

    @Test
    void theRowComesBackThroughTheColumnsTheScreenReads() {
        append.write(denial("service.stop", "a viewer tried to stop the API service"), OCCURRED);

        AuditLogEntry entry = onlyEntry(AuditLogQuery.forTarget("service", target));

        assertThat(entry.occurredAt()).isEqualTo(OCCURRED);
        assertThat(entry.action()).isEqualTo("service.stop");
        assertThat(entry.actorKind()).isEqualTo(AuditActorKind.SYSTEM);
        assertThat(entry.outcome()).isEqualTo(AuditOutcome.DENIED);
        assertThat(entry.actorLabel()).isEqualTo("audit-test");
        assertThat(entry.targetLabel()).isEqualTo("api");
        assertThat(entry.detail()).isEqualTo("a viewer tried to stop the API service");
        assertThat(entry.isRefusal()).isTrue();
    }

    @Test
    void aTimeRangeFindsAnEntryInsideItAndNotOneOutside() {
        append.write(denial("service.start", "outside the window"),
                OCCURRED.minus(2, ChronoUnit.HOURS));
        append.write(denial("service.stop", "inside the window"), OCCURRED);

        AuditLogPage inside = search.find(window(OCCURRED.minus(1, ChronoUnit.HOURS),
                OCCURRED.plus(1, ChronoUnit.HOURS)));

        assertThat(inside.entries()).hasSize(1);
        assertThat(inside.total()).isEqualTo(1);
        assertThat(inside.entries().getFirst().detail()).isEqualTo("inside the window");
    }

    @Test
    void anUpperBoundIsExclusiveSoConsecutivePagesOfADayDoNotOverlap() {
        append.write(denial("service.stop", "on the boundary"), OCCURRED);

        assertThat(search.find(window(OCCURRED.minus(1, ChronoUnit.HOURS), OCCURRED)).entries())
                .isEmpty();
        assertThat(search.find(window(OCCURRED, OCCURRED.plus(1, ChronoUnit.HOURS))).entries())
                .hasSize(1);
    }

    @Test
    void theNewestEntryComesFirst() {
        append.write(denial("service.start", "older"), OCCURRED.minus(1, ChronoUnit.MINUTES));
        append.write(denial("service.stop", "newer"), OCCURRED);

        AuditLogPage page = search.find(AuditLogQuery.forTarget("service", target));

        assertThat(page.entries()).extracting(AuditLogEntry::detail)
                .containsExactly("newer", "older");
        assertThat(page.firstRow()).isEqualTo(1);
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void anActionNothingWritesIsDroppedRatherThanAnsweredWithAnEmptyLog() {
        append.write(denial("service.stop", "still here"), OCCURRED);

        // "service.frobnicate" is not in AuditAction.ALL. Filtering by it must not read as
        // "nothing has ever happened".
        AuditLogQuery invented = new AuditLogQuery(null, null, null, "service.frobnicate",
                "service", target, null, null, null, 50, 0);

        assertThat(search.find(invented).entries()).hasSize(1);
    }

    private long rowsForThisRun() {
        Long count = jdbc.sql("SELECT count(*) FROM audit_log WHERE target_id = :target")
                .param("target", target)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    private AuditLogEntry onlyEntry(AuditLogQuery query) {
        AuditLogPage page = search.find(query);
        assertThat(page.entries()).hasSize(1);
        return page.entries().getFirst();
    }

    private AuditLogQuery window(Instant from, Instant to) {
        return new AuditLogQuery(null, null, null, null, "service", target, null, from, to, 50, 0);
    }

    private AuditEntry denial(String action, String detail) {
        return AuditEntry.denied(AuditActor.system("audit-test"), action,
                AuditTarget.of("service", target, "api"), null, detail);
    }

    /**
     * Just enough Spring to make one repository work: a data source, a transaction manager
     * and Spring Data JDBC's own beans.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJdbcRepositories(basePackageClasses = AuditLogRepository.class)
    static class Wiring extends AbstractJdbcConfiguration {

        @Bean
        NamedParameterJdbcOperations namedParameterJdbcOperations(DataSource dataSource) {
            return new NamedParameterJdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new JdbcTransactionManager(dataSource);
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        AppendAuditLog appendAuditLog(AuditLogRepository entries) {
            return new AppendAuditLog(entries);
        }

        @Bean
        SearchAuditLog searchAuditLog(JdbcClient jdbc) {
            return new SearchAuditLog(jdbc);
        }
    }
}
