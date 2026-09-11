package lhqm.furimeo.wisper.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * A command the customer wants run on a schedule inside their service.
 *
 * <p>Named {@code cron_task} and not {@code scheduled_task} because {@code scheduled_tasks}
 * is db-scheduler's own table (V1). Two tables one letter apart, one holding customer
 * intent and one holding the panel's internal jobs, is a query somebody eventually writes
 * against the wrong one at two in the morning.
 *
 * <p>The schedule is evaluated <strong>on the node</strong>. The panel is not in the path:
 * a cron that has to fire while the panel is unreachable cannot depend on it being up
 * (design §5.1). {@link #nextRunAt} is a panel-side display value computed from
 * {@code schedule}; the node does not read it and never will.
 *
 * <p>Like {@code volume}, this row has two owners. The panel writes the schedule, the
 * command, {@code enabled}, the timeout, the policy and {@code next_run_at}; everything
 * from {@link #lastRunAt} to {@link #lastError} is written by the node from a status
 * report, through {@link RecordCronStatus} and nothing else (schema.md §2).
 *
 * @param command argv, never a shell string. The node execs an argument slice, so a
 *                filename with a semicolon in it stays a filename.
 */
public record CronTask(
        @Id UUID id,
        UUID serviceId,
        String name,
        String schedule,
        String timezone,
        String[] command,
        boolean enabled,
        int timeoutSeconds,
        ConcurrencyPolicy concurrencyPolicy,

        Instant lastRunAt,
        Instant lastFinishedAt,
        Integer lastExitCode,
        Long lastDurationMs,
        String lastError,
        Instant nextRunAt,

        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    /** A new entry, with its first computed due time and nothing reported yet. */
    public static CronTask of(UUID id, UUID serviceId, String name, String schedule,
                              String timezone, String[] command, boolean enabled,
                              int timeoutSeconds, ConcurrencyPolicy policy, Instant nextRunAt) {
        return new CronTask(id, serviceId, name, schedule, timezone, command, enabled,
                timeoutSeconds, policy, null, null, null, null, null, nextRunAt, null, null, null);
    }

    /**
     * The panel's half, rewritten. Everything the node reported survives, because an edit
     * to a schedule does not undo the fact that the job ran at four this morning.
     */
    public CronTask rescheduled(String newSchedule, String newTimezone, String[] newCommand,
                                boolean nowEnabled, int newTimeoutSeconds,
                                ConcurrencyPolicy newPolicy, Instant newNextRunAt) {
        return new CronTask(id, serviceId, name, newSchedule, newTimezone, newCommand, nowEnabled,
                newTimeoutSeconds, newPolicy, lastRunAt, lastFinishedAt, lastExitCode,
                lastDurationMs, lastError, newNextRunAt, createdAt, updatedAt, version);
    }

    /** Whether a run is in flight: the node started one and has not reported it finished. */
    public boolean isRunning() {
        return lastRunAt != null && lastFinishedAt == null;
    }

    /** Whether the last completed run failed. Null exit code means it has never finished one. */
    public boolean lastRunFailed() {
        return lastExitCode != null && lastExitCode != 0;
    }
}
