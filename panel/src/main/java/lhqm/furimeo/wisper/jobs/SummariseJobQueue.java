package lhqm.furimeo.wisper.jobs;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counts the queue, in one pass.
 *
 * <p>Four counts from one scan with {@code FILTER} clauses rather than four statements.
 * On a queue small enough for this to be irrelevant it costs nothing; on one large enough
 * to matter, four separate {@code count(*)}s over the same table is four scans and four
 * chances to show numbers taken at four different moments, which is how a summary ends up
 * claiming there are more running jobs than queued ones.
 */
@Component
public class SummariseJobQueue {

    private final JdbcClient jdbc;
    private final SchedulerTable table;

    public SummariseJobQueue(JdbcClient jdbc, SchedulerTable table) {
        this.jdbc = jdbc;
        this.table = table;
    }

    @Transactional(readOnly = true)
    public JobQueueSummary now() {
        return jdbc.sql("""
                        SELECT count(*)                                                AS queued,
                               count(*) FILTER (WHERE execution_time <= now()
                                                  AND NOT picked)                      AS due,
                               count(*) FILTER (WHERE picked)                          AS running,
                               count(*) FILTER (WHERE consecutive_failures > 0)        AS failing
                          FROM %s
                        """.formatted(table.name()))
                .query(SummariseJobQueue::map)
                .single();
    }

    private static JobQueueSummary map(ResultSet row, int rowNumber) throws SQLException {
        return new JobQueueSummary(
                row.getLong("queued"),
                row.getLong("due"),
                row.getLong("running"),
                row.getLong("failing"));
    }
}
