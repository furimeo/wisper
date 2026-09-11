package lhqm.furimeo.wisper.backup;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Changes an existing policy's schedule, retention, destination or name.
 *
 * <p>The target is deliberately not editable. Pointing a policy at a different volume would
 * silently change what its existing snapshots are snapshots <em>of</em> - the retention
 * series would mix two subjects and a restore would offer a customer the wrong data with a
 * familiar name on it. Backing up something else is a new policy, which costs one form.
 *
 * <p>Editing always recomputes {@code next_run_at} from now. A customer who changes 03:00
 * to 04:00 at half past three expects the next run at four, not at three tomorrow because
 * the old due time was left in place.
 */
@Component
public class UpdateBackupSchedule {

    private static final int MAX_RETENTION_DAYS = 3650;

    private static final int MAX_RETENTION_COUNT = 1000;

    private final BackupRepository backups;
    private final BackupDestinationRepository destinations;
    private final ScheduleNextRun schedules;
    private final AuditTrail audit;

    public UpdateBackupSchedule(BackupRepository backups,
                                BackupDestinationRepository destinations,
                                ScheduleNextRun schedules, AuditTrail audit) {
        this.backups = backups;
        this.destinations = destinations;
        this.schedules = schedules;
        this.audit = audit;
    }

    /**
     * @throws NotFoundException if there is no such policy in this organization
     * @throws RequestRejected   naming the input at fault
     */
    @Transactional
    public Backup update(AuditActor actor, Membership membership, UUID backupId, String name,
                         UUID destinationId, String schedule, String timezone,
                         int retentionCount, int retentionDays, boolean enabled) {
        membership.requireWrite("backup.update");
        UUID organizationId = membership.organizationId();
        Backup policy = backups.findByIdAndOrganizationId(backupId, organizationId)
                .orElseThrow(() -> NotFoundException.of("backup", backupId));

        String newName = requireName(name, policy, organizationId);
        requireRetention(retentionCount, retentionDays);

        BackupDestination destination = destinations.findById(destinationId)
                .filter(candidate -> candidate.isUsableBy(organizationId))
                .orElseThrow(() -> new RequestRejected("destinationId",
                        "Choose a destination that is switched on and available to this "
                                + "organization."));

        String normalised = schedules.normalise(schedule);
        String zone = timezone == null || timezone.isBlank() ? "UTC" : timezone.strip();
        Instant nextRunAt = enabled
                ? schedules.firstRun(normalised, zone, Instant.now()).orElse(null)
                : null;
        if (enabled && normalised != null && nextRunAt == null) {
            throw new RequestRejected("schedule",
                    "That schedule never comes round - check the day and month fields.");
        }

        Backup updated = backups.save(policy
                .renamedTo(newName)
                .withDestination(destination.id())
                .withSchedule(normalised, zone, retentionCount, retentionDays, enabled,
                        nextRunAt));

        audit.record(AuditEntry.succeeded(actor, "backup.update",
                AuditTarget.of("backup", policy.id(), newName), organizationId,
                (enabled ? "enabled, " : "disabled, ")
                        + (normalised == null ? "on demand" : "schedule " + normalised)
                        + ", keeping " + retentionCount + " for " + retentionDays
                        + " days, destination \"" + destination.name() + "\""));
        return updated;
    }

    private String requireName(String name, Backup policy, UUID organizationId) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty()) {
            throw new RequestRejected("name", "Give this backup a name you will recognise "
                    + "in a hurry.");
        }
        if (trimmed.length() > 100) {
            throw new RequestRejected("name", "That name is longer than 100 characters.");
        }
        if (!trimmed.equals(policy.name())
                && backups.existsByOrganizationIdAndName(organizationId, trimmed)) {
            throw new RequestRejected("name", "There is already a backup called \"" + trimmed
                    + "\".");
        }
        return trimmed;
    }

    private static void requireRetention(int count, int days) {
        if (count < 1 || count > MAX_RETENTION_COUNT) {
            throw new RequestRejected("retentionCount",
                    "Keep between 1 and " + MAX_RETENTION_COUNT + " snapshots.");
        }
        if (days < 1 || days > MAX_RETENTION_DAYS) {
            throw new RequestRejected("retentionDays",
                    "Keep snapshots for between 1 and " + MAX_RETENTION_DAYS + " days.");
        }
    }
}
