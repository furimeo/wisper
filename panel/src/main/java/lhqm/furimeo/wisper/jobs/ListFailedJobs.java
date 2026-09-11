package lhqm.furimeo.wisper.jobs;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The jobs that are not working, for {@code /admin/jobs}.
 *
 * <p>"Failing" is {@code consecutive_failures > 0}: db-scheduler resets that counter on a
 * success, so it is the length of the current streak rather than a lifetime tally, and a
 * job that failed once last Tuesday and has worked ever since does not appear here.
 *
 * <p>The point of this screen is that a job which keeps failing is otherwise completely
 * silent. db-scheduler logs each failure at the configured level and reschedules with
 * backoff, so a broken backup policy produces a warning every few minutes in a log nobody
 * reads and no visible symptom anywhere else until a customer asks for a restore.
 */
@Component
public class ListFailedJobs {

    private final JdbcClient jdbc;
    private final SchedulerTable table;

    public ListFailedJobs(JdbcClient jdbc, SchedulerTable table) {
        this.jdbc = jdbc;
        this.table = table;
    }

    /**
     * A page of failing jobs.
     *
     * @param limit  rows to return, already clamped by the caller's settings
     * @param offset rows to skip
     */
    @Transactional(readOnly = true)
    public FailedJobPage page(int limit, int offset) {
        int size = Math.max(1, limit);
        int skip = Math.max(0, offset);
        List<FailedJob> jobs = jdbc.sql("""
                        SELECT task_name, task_instance, execution_time, consecutive_failures,
                               last_failure, last_success, picked, picked_by, last_heartbeat
                          FROM %s
                         WHERE consecutive_failures > 0
                         ORDER BY consecutive_failures DESC, last_failure DESC
                         LIMIT :limit OFFSET :offset
                        """.formatted(table.name()))
                .param("limit", size)
                .param("offset", skip)
                .query(ListFailedJobs::map)
                .list();

        Long total = jdbc.sql("SELECT count(*) FROM %s WHERE consecutive_failures > 0"
                        .formatted(table.name()))
                .query(Long.class)
                .single();

        return new FailedJobPage(jobs, total == null ? jobs.size() : total, skip, size);
    }

    /**
     * One row, for the confirmation a retry or discard writes into the audit trail.
     *
     * <p>Read before the action rather than after, because the action is what makes the
     * row disappear, and an audit entry that says "discarded a job" without saying which
     * one or how badly it was failing is not worth writing.
     */
    @Transactional(readOnly = true)
    public FailedJob byId(String taskName, String instanceId) {
        return jdbc.sql("""
                        SELECT task_name, task_instance, execution_time, consecutive_failures,
                               last_failure, last_success, picked, picked_by, last_heartbeat
                          FROM %s
                         WHERE task_name = :taskName AND task_instance = :instanceId
                        """.formatted(table.name()))
                .param("taskName", taskName)
                .param("instanceId", instanceId)
                .query(ListFailedJobs::map)
                .optional()
                .orElse(null);
    }

    private static FailedJob map(ResultSet row, int rowNumber) throws SQLException {
        return new FailedJob(
                row.getString("task_name"),
                row.getString("task_instance"),
                instant(row, "execution_time"),
                row.getInt("consecutive_failures"),
                instant(row, "last_failure"),
                instant(row, "last_success"),
                row.getBoolean("picked"),
                row.getString("picked_by"),
                instant(row, "last_heartbeat"));
    }

    /**
     * PgJDBC maps {@code timestamptz} to {@link OffsetDateTime}; asking it for an
     * {@link Instant} directly throws.
     */
    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
