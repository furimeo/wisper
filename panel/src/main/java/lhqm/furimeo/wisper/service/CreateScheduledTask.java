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
import lhqm.furimeo.wisper.org.QuotaGuard;
import lhqm.furimeo.wisper.org.QuotaResource;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.project.Slug;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Adds a scheduled command to a service.
 *
 * <p>Apps only. {@code CronEntry.workload_id} names the container the command runs in, and
 * a static site has none - there is no process, no shell and nothing to exec. A customer
 * who wants a site rebuilt nightly wants a deployment on a schedule, which is a different
 * feature with a different owner.
 *
 * <p>The command is argv, split from the typed line by {@link CommandLine}. The node execs
 * an argument slice, so nothing here can become two commands however it is punctuated.
 *
 * <p>{@code next_run_at} is computed now and stored for display. It is the panel's value:
 * the node schedules from the expression itself and never reads this column, which is why
 * a panel that is down for a day costs nothing but a stale number on a screen.
 */
@Component
public class CreateScheduledTask {

    /** A day. Longer than this is not a cron job, it is a service. */
    private static final int MAX_TIMEOUT_SECONDS = 86_400;

    private final ServiceRepository services;
    private final CronTaskRepository tasks;
    private final QuotaGuard quotas;
    private final PublishNodeSpec specs;
    private final AuditTrail audit;

    public CreateScheduledTask(ServiceRepository services, CronTaskRepository tasks,
                               QuotaGuard quotas, PublishNodeSpec specs, AuditTrail audit) {
        this.services = services;
        this.tasks = tasks;
        this.quotas = quotas;
        this.specs = specs;
        this.audit = audit;
    }

    /**
     * @param rawName  lower-case, unique within the service
     * @param schedule a five-field cron expression
     * @param timezone an IANA zone name, or blank for UTC
     * @param command  argv; must not be empty
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        when the service is not this
     *                                                  organization's
     * @throws RequestRejected                          for a site, a bad name, schedule,
     *                                                  zone, command or timeout, or a name
     *                                                  already in use
     * @throws lhqm.furimeo.wisper.org.QuotaExceeded    at the plan's cron limit
     */
    @Transactional
    public CronTask create(AuditActor actor, Membership membership, UUID serviceId, String rawName,
                           String schedule, String timezone, List<String> command,
                           int timeoutSeconds, ConcurrencyPolicy policy, boolean enabled) {
        membership.requireWrite("cron_task.create");

        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));
        if (service.isSite()) {
            throw new RequestRejected(null,
                    "A static site has no container to run a command in. Scheduled commands "
                            + "belong to an app.");
        }

        String name = Slug.normalise(rawName);
        if (!Slug.isShortValid(name)) {
            throw new RequestRejected("name", "That name will not work. " + Slug.rule(1));
        }
        if (command == null || command.isEmpty()) {
            throw new RequestRejected("command",
                    "Say what to run, for example \"php artisan schedule:run\".");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new RequestRejected("timeoutSeconds",
                    "A timeout is between 1 second and 24 hours. A job with no limit is how a "
                            + "node collects stuck copies of the same script.");
        }

        CronSchedule parsed = CronSchedule.parse(schedule);
        String zone = CronSchedule.requireZone(timezone);

        quotas.require(membership.organizationId(), QuotaResource.CRON_TASK, 1);

        if (tasks.existsByServiceIdAndName(serviceId, name)) {
            throw new RequestRejected("name",
                    "This service already has a scheduled command called " + name + ".");
        }

        Instant nextRun = parsed.nextRunAfter(Instant.now(), ZoneId.of(zone)).orElse(null);
        CronTask task = tasks.save(CronTask.of(UUID.randomUUID(), serviceId, name,
                parsed.expression(), zone, command.toArray(String[]::new), enabled,
                timeoutSeconds, policy == null ? ConcurrencyPolicy.FORBID : policy, nextRun));

        specs.forService(serviceId, "scheduled command " + name + " added to " + service.slug());

        audit.record(AuditEntry.succeeded(actor, "cron_task.create",
                AuditTarget.of("cron_task", task.id(), task.name()), membership.organizationId(),
                "On " + service.slug() + ", " + parsed.expression() + " " + zone
                        + (enabled ? "" : ", disabled")));
        return task;
    }
}
