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
 * Writes a backup policy: what to snapshot, where to put it, how often and how long to
 * keep it.
 *
 * <p>Everything a customer can get wrong is checked here, before a row exists, so the
 * answer is a sentence under the input that caused it rather than a policy that quietly
 * fails at three in the morning. In particular the target is resolved through
 * {@link ResolveBackupTarget} and its organization compared with the caller's: a volume id
 * from another tenant is a 404, the same as a volume id that never existed.
 *
 * <p>A blank schedule is not an error. It is a policy that runs when somebody presses the
 * button, which is a real and common shape - "keep a copy of this before I try something".
 * The row is still what carries the destination and the retention, so the button has
 * somewhere to read them from.
 */
@Component
public class CreateBackupSchedule {

    /** Above this, a customer is asking for something the tiering cannot express. */
    private static final int MAX_RETENTION_DAYS = 3650;

    private static final int MAX_RETENTION_COUNT = 1000;

    private final BackupRepository backups;
    private final BackupDestinationRepository destinations;
    private final ResolveBackupTarget targets;
    private final ScheduleNextRun schedules;
    private final AuditTrail audit;

    public CreateBackupSchedule(BackupRepository backups,
                                BackupDestinationRepository destinations,
                                ResolveBackupTarget targets, ScheduleNextRun schedules,
                                AuditTrail audit) {
        this.backups = backups;
        this.destinations = destinations;
        this.targets = targets;
        this.schedules = schedules;
        this.audit = audit;
    }

    /**
     * @param schedule five-field cron, or blank for a policy that only runs on demand
     * @throws NotFoundException  if the target or the destination is not this tenant's
     * @throws RequestRejected    naming the input at fault
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     */
    @Transactional
    public Backup create(AuditActor actor, Membership membership, String name,
                         UUID destinationId, BackupTargetKind targetKind, UUID subjectId,
                         String schedule, String timezone, int retentionCount,
                         int retentionDays) {
        membership.requireWrite("backup.create");
        UUID organizationId = membership.organizationId();
        String policyName = requireName(name, organizationId);
        requireRetention(retentionCount, retentionDays);

        BackupDestination destination = destinations.findById(destinationId)
                .filter(candidate -> candidate.isUsableBy(organizationId))
                .orElseThrow(() -> new RequestRejected("destinationId",
                        "Choose a destination that is switched on and available to this "
                                + "organization."));

        BackupTargetRef target = targets.of(targetKind, subjectId)
                .filter(candidate -> organizationId.equals(candidate.organizationId()))
                .orElseThrow(() -> NotFoundException.of(
                        targetKind == BackupTargetKind.VOLUME ? "volume" : "database", subjectId));

        String normalised = schedules.normalise(schedule);
        String zone = timezone == null || timezone.isBlank() ? "UTC" : timezone.strip();
        Instant nextRunAt = schedules.firstRun(normalised, zone, Instant.now()).orElse(null);
        if (normalised != null && nextRunAt == null) {
            throw new RequestRejected("schedule",
                    "That schedule never comes round - check the day and month fields.");
        }

        Backup policy = backups.save(targetKind == BackupTargetKind.VOLUME
                ? Backup.forVolume(UUID.randomUUID(), organizationId, destination.id(),
                        policyName, subjectId, normalised, zone, retentionCount, retentionDays,
                        nextRunAt)
                : Backup.forDatabase(UUID.randomUUID(), organizationId, destination.id(),
                        policyName, subjectId, normalised, zone, retentionCount, retentionDays,
                        nextRunAt));

        audit.record(AuditEntry.succeeded(actor, "backup.create",
                AuditTarget.of("backup", policy.id(), policyName), organizationId,
                describe(target, destination, normalised, retentionCount, retentionDays)));
        return policy;
    }

    private String requireName(String name, UUID organizationId) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty()) {
            throw new RequestRejected("name", "Give this backup a name you will recognise "
                    + "in a hurry.");
        }
        if (trimmed.length() > 100) {
            throw new RequestRejected("name", "That name is longer than 100 characters.");
        }
        if (backups.existsByOrganizationIdAndName(organizationId, trimmed)) {
            throw new RequestRejected("name", "There is already a backup called \"" + trimmed
                    + "\".");
        }
        return trimmed;
    }

    /** Mirrors {@code backup_retention_positive}, with a message instead of a violation. */
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

    /** The audit detail. Names the destination; never its credentials. */
    private static String describe(BackupTargetRef target, BackupDestination destination,
                                   String schedule, int retentionCount, int retentionDays) {
        return target.label() + " to \"" + destination.name() + "\" ("
                + destination.kind() + "), "
                + (schedule == null ? "on demand" : "schedule " + schedule)
                + ", keeping " + retentionCount + " for " + retentionDays + " days";
    }
}
