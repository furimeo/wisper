package lhqm.furimeo.wisper.service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Changes a scheduled command: its expression, its zone, what it runs, whether it runs at
 * all.
 *
 * <p>Switching one off is an update rather than a delete, and that distinction is the
 * reason this file exists. A customer whose nightly import is misbehaving wants it to stop
 * happening tonight and to still be there on Monday, with its schedule, its command and
 * the record of what it did last. Deleting and retyping loses all three.
 *
 * <p>A disabled entry is left out of the spec entirely - {@code findEnabledIn} is what the
 * spec builder reads - so the node stops scheduling it rather than running it and throwing
 * the result away.
 *
 * <p>The name is not editable, like every other address in this panel. It identifies the
 * entry in the spec and in the node's own state.
 */
@Component
public class UpdateScheduledTask {

    private static final int MAX_TIMEOUT_SECONDS = 86_400;

    private final ServiceRepository services;
    private final CronTaskRepository tasks;
    private final PublishNodeSpec specs;
    private final AuditTrail audit;

    public UpdateScheduledTask(ServiceRepository services, CronTaskRepository tasks,
                               PublishNodeSpec specs, AuditTrail audit) {
        this.services = services;
        this.tasks = tasks;
        this.specs = specs;
        this.audit = audit;
    }

    /**
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        when the service is not this
     *                                                  organization's, or has no entry by
     *                                                  that name
     * @throws RequestRejected                          for a bad schedule, zone, command or
     *                                                  timeout
     */
    @Transactional
    public CronTask update(AuditActor actor, Membership membership, UUID serviceId, String name,
                           String schedule, String timezone, List<String> command,
                           int timeoutSeconds, ConcurrencyPolicy policy, boolean enabled) {
        membership.requireWrite("cron_task.update");

        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));
        CronTask task = tasks.findByServiceIdAndName(serviceId, name == null ? "" : name.strip())
                .orElseThrow(() -> new NotFoundException(
                        "No scheduled command called " + name + " on this service"));

        if (command == null || command.isEmpty()) {
            throw new RequestRejected("command", "Say what to run.");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new RequestRejected("timeoutSeconds",
                    "A timeout is between 1 second and 24 hours.");
        }

        CronSchedule parsed = CronSchedule.parse(schedule);
        String zone = CronSchedule.requireZone(timezone);
        Instant nextRun = enabled
                ? parsed.nextRunAfter(Instant.now(), ZoneId.of(zone)).orElse(null)
                : null;

        CronTask updated = tasks.save(task.rescheduled(parsed.expression(), zone,
                command.toArray(String[]::new), enabled, timeoutSeconds,
                policy == null ? ConcurrencyPolicy.FORBID : policy, nextRun));

        specs.forService(serviceId, "scheduled command " + task.name() + " changed on "
                + service.slug());

        audit.record(AuditEntry.succeeded(actor, "cron_task.update",
                AuditTarget.of("cron_task", updated.id(), updated.name()),
                membership.organizationId(),
                describe(task, updated)));
        return updated;
    }

    /** What moved, for the trail. The command is named as changed, never quoted. */
    private static String describe(CronTask before, CronTask after) {
        StringBuilder detail = new StringBuilder();
        if (before.enabled() != after.enabled()) {
            detail.append(after.enabled() ? "Enabled" : "Disabled");
        }
        if (!before.schedule().equals(after.schedule())
                || !before.timezone().equals(after.timezone())) {
            append(detail, "schedule now " + after.schedule() + " " + after.timezone());
        }
        if (!CommandLine.format(before.command()).equals(CommandLine.format(after.command()))) {
            append(detail, "command changed");
        }
        if (before.timeoutSeconds() != after.timeoutSeconds()) {
            append(detail, "timeout now " + after.timeoutSeconds() + "s");
        }
        if (before.concurrencyPolicy() != after.concurrencyPolicy()) {
            append(detail, "overlap policy now " + after.concurrencyPolicy());
        }
        return detail.isEmpty() ? "Saved with no field changed" : detail.toString();
    }

    private static void append(StringBuilder detail, String phrase) {
        if (detail.isEmpty()) {
            detail.append(Character.toUpperCase(phrase.charAt(0))).append(phrase.substring(1));
        } else {
            detail.append(", ").append(phrase);
        }
    }
}
