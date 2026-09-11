package lhqm.furimeo.wisper.jobs;

import org.springframework.stereotype.Component;

import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerProperties;

/**
 * The name of db-scheduler's own table, checked once so it can be written into SQL.
 *
 * <p>The admin screens read {@code scheduled_tasks} directly rather than through
 * {@code SchedulerClient.getScheduledExecutions}, for two reasons: the library's read path
 * deserialises every payload it returns, and it has no {@code LIMIT}. An operator looking
 * at a queue with fifty thousand rows in it wants the twenty that are failing, not all of
 * them in memory.
 *
 * <p>That means the table name reaches the SQL text, and a table name cannot be a bound
 * parameter. It comes from {@code db-scheduler.table-name}, which is configuration and not
 * user input - but "not user input today" is how injection holes are introduced, so it is
 * validated against the shape of an unquoted PostgreSQL identifier and the application
 * refuses to start if it is anything else.
 */
@Component
public class SchedulerTable {

    /** An unquoted identifier: a letter or underscore, then letters, digits, underscores. */
    private static final String IDENTIFIER = "[a-z_][a-z0-9_]{0,62}";

    private final String name;

    public SchedulerTable(DbSchedulerProperties properties) {
        this(properties.getTableName());
    }

    SchedulerTable(String name) {
        if (name == null || !name.matches(IDENTIFIER)) {
            throw new IllegalArgumentException("db-scheduler.table-name must be a plain lower-case "
                    + "identifier, because it is written into SQL and a table name cannot be a "
                    + "bound parameter. It was \"" + name + "\".");
        }
        this.name = name;
    }

    /** Safe to concatenate into a statement. */
    public String name() {
        return name;
    }
}
