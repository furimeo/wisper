package lhqm.furimeo.wisper.backup;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * A backup <strong>policy</strong>: what to snapshot, how often, where to push it and how
 * long to keep it.
 *
 * <p>This is the rule and not the artefact. The artefacts are {@link RestorePoint} rows,
 * and they outlive this one deliberately: deleting a policy must never delete the
 * snapshots taken under it, because the moment somebody deletes a policy is exactly the
 * moment they are reorganising and about to need one of them.
 *
 * <p>Exactly one of {@link #volumeId} and {@link #managedDatabaseId} is set, matching
 * {@link #targetKind}. Both foreign keys CASCADE: a policy whose target is gone has
 * nothing to run against, and {@code SET NULL} could not satisfy the exactly-one CHECK, so
 * deleting a volume would fail outright instead.
 *
 * <p>A null {@link #schedule} is a policy that only runs when somebody presses the button.
 * That is a real and common shape - "keep a snapshot of this before I try something" -
 * rather than an unfinished row.
 *
 * @param schedule       five-field cron, evaluated by the panel (not the node: unlike
 *                       {@code cron_task}, a backup needs the destination credentials and
 *                       the retention view across every node, both of which only the panel
 *                       has)
 * @param retentionCount keep at least this many of the most recent, whatever their age
 * @param retentionDays  the age ceiling the daily/weekly/monthly tiers are derived from
 * @param nextRunAt      when the sweep should pick it up; null while disabled or manual
 */
public record Backup(
        @Id UUID id,
        UUID organizationId,
        UUID destinationId,

        String name,
        BackupTargetKind targetKind,
        UUID volumeId,
        UUID managedDatabaseId,

        String schedule,
        String timezone,
        boolean enabled,

        int retentionCount,
        int retentionDays,

        Instant lastRunAt,
        BackupRunStatus lastStatus,
        String lastError,
        Instant nextRunAt,

        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    public Backup {
        timezone = timezone == null || timezone.isBlank() ? "UTC" : timezone;
    }

    /** A new policy over a volume. */
    public static Backup forVolume(UUID id, UUID organizationId, UUID destinationId, String name,
                                   UUID volumeId, String schedule, String timezone,
                                   int retentionCount, int retentionDays, Instant nextRunAt) {
        return new Backup(id, organizationId, destinationId, name, BackupTargetKind.VOLUME,
                volumeId, null, schedule, timezone, true, retentionCount, retentionDays,
                null, null, null, nextRunAt, null, null, null);
    }

    /** A new policy over a managed database. */
    public static Backup forDatabase(UUID id, UUID organizationId, UUID destinationId, String name,
                                     UUID managedDatabaseId, String schedule, String timezone,
                                     int retentionCount, int retentionDays, Instant nextRunAt) {
        return new Backup(id, organizationId, destinationId, name, BackupTargetKind.DATABASE,
                null, managedDatabaseId, schedule, timezone, true, retentionCount, retentionDays,
                null, null, null, nextRunAt, null, null, null);
    }

    /** The id of whichever target {@link #targetKind} names. Never null on a stored row. */
    public UUID subjectId() {
        return targetKind == BackupTargetKind.VOLUME ? volumeId : managedDatabaseId;
    }

    /** Whether a schedule fires this policy on its own. */
    public boolean isScheduled() {
        return enabled && schedule != null && !schedule.isBlank();
    }

    /** The schedule, retention and timezone changed together, as one form submits them. */
    public Backup withSchedule(String newSchedule, String newTimezone, int newRetentionCount,
                               int newRetentionDays, boolean nowEnabled, Instant newNextRunAt) {
        return new Backup(id, organizationId, destinationId, name, targetKind, volumeId,
                managedDatabaseId, newSchedule, newTimezone, nowEnabled, newRetentionCount,
                newRetentionDays, lastRunAt, lastStatus, lastError, newNextRunAt,
                createdAt, updatedAt, version);
    }

    /** Pointed at a different destination. Existing snapshots stay where they are. */
    public Backup withDestination(UUID newDestinationId) {
        return new Backup(id, organizationId, newDestinationId, name, targetKind, volumeId,
                managedDatabaseId, schedule, timezone, enabled, retentionCount, retentionDays,
                lastRunAt, lastStatus, lastError, nextRunAt, createdAt, updatedAt, version);
    }

    public Backup renamedTo(String newName) {
        return new Backup(id, organizationId, destinationId, newName, targetKind, volumeId,
                managedDatabaseId, schedule, timezone, enabled, retentionCount, retentionDays,
                lastRunAt, lastStatus, lastError, nextRunAt, createdAt, updatedAt, version);
    }

    /** A run has been handed to a node. */
    public Backup started(Instant at, Instant followedBy) {
        return new Backup(id, organizationId, destinationId, name, targetKind, volumeId,
                managedDatabaseId, schedule, timezone, enabled, retentionCount, retentionDays,
                at, BackupRunStatus.RUNNING, null, followedBy, createdAt, updatedAt, version);
    }

    /** The run finished. {@code error} is null when it worked. */
    public Backup finished(BackupRunStatus status, String error) {
        return new Backup(id, organizationId, destinationId, name, targetKind, volumeId,
                managedDatabaseId, schedule, timezone, enabled, retentionCount, retentionDays,
                lastRunAt, status, error, nextRunAt, createdAt, updatedAt, version);
    }

    /**
     * A new due time without touching anything else.
     *
     * <p>Used by the sweep, which advances the clock in the same transaction that enqueues
     * the run - so a policy cannot be picked up twice for the same due time even if two
     * panels sweep at once.
     */
    public Backup dueAt(Instant when) {
        return new Backup(id, organizationId, destinationId, name, targetKind, volumeId,
                managedDatabaseId, schedule, timezone, enabled, retentionCount, retentionDays,
                lastRunAt, lastStatus, lastError, when, createdAt, updatedAt, version);
    }
}
