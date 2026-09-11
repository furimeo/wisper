package lhqm.furimeo.wisper.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import lhqm.furimeo.wisper.migration.SchemaMigrations;

/**
 * The admin queue screen's two reads, against the real {@code scheduled_tasks}.
 *
 * <p>Both statements are written by hand against a table this project does not own:
 * db-scheduler reads and writes those column names, {@code panel-configuration.md}
 * reproduces them, and V1 creates them. A column renamed on any of those three sides breaks
 * {@code /admin/jobs} and nothing else notices, because the failure is a page an operator
 * only opens when something else is already wrong.
 *
 * <p>Rows are inserted directly rather than through {@code SchedulerClient}: what is being
 * checked is the SQL against the schema, and a scheduler that is deliberately switched off
 * in tests cannot produce a job with a fourteen-attempt failure streak on demand.
 *
 * <p>Needs the {@code wisper_test} database from
 * {@code docs/contracts/panel-configuration.md}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ListFailedJobsTest {

    /** The same three values {@code src/test/resources/application-test.yml} carries. */
    private static final String URL = "jdbc:postgresql://localhost:5432/wisper_test";
    private static final String USER = System.getenv().getOrDefault("WISPER_TEST_DB_USER",
            "wisper");
    private static final String PASSWORD = System.getenv().getOrDefault("WISPER_TEST_DB_PASSWORD",
            "wisper");

    private static final Instant NOW = Instant.parse("2026-07-09T06:00:00Z");

    /** This run's own task name, so a queue with real rows in it cannot change the counts. */
    private final String taskName = "jobs-test-" + UUID.randomUUID().toString().substring(0, 8);

    private JdbcClient jdbc;
    private ListFailedJobs failures;
    private SummariseJobQueue summary;

    @BeforeAll
    void startAgainstTheTestDatabase() throws SQLException {
        DataSource dataSource = new SimpleDriverDataSource(new org.postgresql.Driver(), URL, USER,
                PASSWORD);
        new SchemaMigrations(dataSource).afterPropertiesSet();

        jdbc = JdbcClient.create(dataSource);
        SchedulerTable table = new SchedulerTable("scheduled_tasks");
        failures = new ListFailedJobs(jdbc, table);
        summary = new SummariseJobQueue(jdbc, table);
    }

    @BeforeEach
    void removeWhatTheLastTestWrote() {
        jdbc.sql("DELETE FROM scheduled_tasks WHERE task_name = :task").param("task", taskName)
                .update();
    }

    @AfterAll
    void stop() {
        if (jdbc != null) {
            jdbc.sql("DELETE FROM scheduled_tasks WHERE task_name = :task").param("task", taskName)
                    .update();
        }
    }

    @Test
    void onlyAJobWithACurrentFailureStreakIsListed() {
        queue("healthy", 0, false);
        queue("broken", 3, false);

        assertThat(failures.page(25, 0).jobs()).extracting(FailedJob::instanceId)
                .containsExactly("broken");
    }

    @Test
    void theWorstComesFirstRatherThanTheOneDueSoonest() {
        // db-scheduler pushes execution_time further out on every failure, so ordering by
        // when a job will next run puts the worst problem at the bottom of the list.
        queue("failed-once", 1, false);
        queue("failed-fourteen-times", 14, false);

        assertThat(failures.page(25, 0).jobs()).extracting(FailedJob::instanceId)
                .containsExactly("failed-fourteen-times", "failed-once");
    }

    @Test
    void aJobAWorkerIsHoldingOffersNoButtons() {
        queue("running", 2, true);

        FailedJob job = failures.page(25, 0).jobs().getFirst();

        assertThat(job.picked()).isTrue();
        // A running job is stopped by cancelling its work, not by touching its row.
        assertThat(job.isActionable()).isFalse();
        assertThat(job.pickedBy()).isEqualTo("panel-under-test");
    }

    @Test
    void aPageCarriesTheFullCountBehindIt() {
        queue("one", 1, false);
        queue("two", 2, false);
        queue("three", 3, false);

        FailedJobPage page = failures.page(2, 0);

        assertThat(page.jobs()).hasSize(2);
        assertThat(page.total()).isGreaterThanOrEqualTo(3);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.isEmpty()).isFalse();
    }

    @Test
    void aRowThatIsNoLongerThereReadsAsGoneRatherThanThrowing() {
        assertThat(failures.byId(taskName, "never-queued")).isNull();
    }

    @Test
    void theSummaryCountsTheQueueInOnePass() {
        queue("due", 0, false, NOW.minus(1, ChronoUnit.HOURS));
        queue("later", 0, false, Instant.now().plus(1, ChronoUnit.DAYS));
        queue("running", 4, true, NOW);

        JobQueueSummary counts = summary.now();

        assertThat(counts.queued()).isGreaterThanOrEqualTo(3);
        assertThat(counts.due()).isGreaterThanOrEqualTo(1);
        assertThat(counts.running()).isGreaterThanOrEqualTo(1);
        assertThat(counts.failing()).isGreaterThanOrEqualTo(1);
        assertThat(counts.isHealthy()).isFalse();
    }

    private void queue(String instanceId, int consecutiveFailures, boolean picked) {
        queue(instanceId, consecutiveFailures, picked,
                NOW.plus(consecutiveFailures, ChronoUnit.MINUTES));
    }

    private void queue(String instanceId, int consecutiveFailures, boolean picked,
                       Instant executionTime) {
        jdbc.sql("""
                        INSERT INTO scheduled_tasks (task_name, task_instance, execution_time,
                                                     picked, picked_by, consecutive_failures,
                                                     last_failure, version)
                        VALUES (:task, :instance, :executionTime, :picked, :pickedBy, :failures,
                                :lastFailure, 1)
                        """)
                .param("task", taskName)
                .param("instance", instanceId)
                .param("executionTime", executionTime.atOffset(ZoneOffset.UTC))
                .param("picked", picked)
                .param("pickedBy", picked ? "panel-under-test" : null)
                // Left null for a job that is working: db-scheduler resets the counter on a
                // success, and "no streak" is what the list filters on.
                .param("failures", consecutiveFailures > 0 ? consecutiveFailures : null)
                .param("lastFailure", consecutiveFailures > 0
                        ? NOW.minus(consecutiveFailures, ChronoUnit.MINUTES)
                                .atOffset(ZoneOffset.UTC)
                        : null)
                .update();
    }
}
