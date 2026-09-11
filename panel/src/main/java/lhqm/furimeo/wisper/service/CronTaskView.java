package lhqm.furimeo.wisper.service;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * One row of the scheduled-commands screen.
 *
 * <p>Two things are different from the stored {@link CronTask}. The command comes back as
 * the line the customer typed rather than as argv, because that is what the edit form
 * needs and rendering {@code ["php","artisan","queue:work"]} at somebody is not an answer.
 * And {@link #nextRunAt} is recomputed when the stored one has already passed.
 *
 * <p>That recomputation matters. {@code cron_task.next_run_at} is written when the entry is
 * created or edited and never touched again - the node does its own scheduling and does
 * not report back a due time the panel is allowed to store (schema.md §2). A column
 * written once is a column that is stale by tomorrow, and a screen that says a nightly job
 * is next due last Tuesday is a screen that makes a working system look broken. The stored
 * value is used while it is still in the future, and derived from the expression when it
 * is not.
 */
public record CronTaskView(
        UUID id,
        String name,
        String schedule,
        String timezone,
        String command,
        boolean enabled,
        int timeoutSeconds,
        ConcurrencyPolicy concurrencyPolicy,
        Instant lastRunAt,
        Instant lastFinishedAt,
        Integer lastExitCode,
        Long lastDurationMs,
        String lastError,
        Instant nextRunAt,
        boolean running) {

    /** Builds the view, refreshing the due time against {@code now} if it has passed. */
    public static CronTaskView of(CronTask task, Instant now) {
        return new CronTaskView(task.id(), task.name(), task.schedule(), task.timezone(),
                CommandLine.format(task.command()), task.enabled(), task.timeoutSeconds(),
                task.concurrencyPolicy(), task.lastRunAt(), task.lastFinishedAt(),
                task.lastExitCode(), task.lastDurationMs(), task.lastError(),
                dueTime(task, now), task.isRunning());
    }

    /** Whether the last completed run ended badly, which is what the screen colours red. */
    public boolean lastRunFailed() {
        return lastExitCode != null && lastExitCode != 0;
    }

    private static Instant dueTime(CronTask task, Instant now) {
        if (!task.enabled()) {
            return null;
        }
        if (task.nextRunAt() != null && task.nextRunAt().isAfter(now)) {
            return task.nextRunAt();
        }
        try {
            return CronSchedule.parse(task.schedule())
                    .nextRunAfter(now, ZoneId.of(task.timezone()))
                    .orElse(null);
        } catch (RequestRejected | DateTimeException unparseable) {
            // Stored expressions are validated on the way in, so this is a row written
            // before a rule tightened. Showing no due time is honest; refusing to render
            // the page the customer needs in order to fix it is not.
            return null;
        }
    }
}
