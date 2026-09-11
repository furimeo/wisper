package lhqm.furimeo.wisper.service;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Removes a scheduled command from a service.
 *
 * <p>The entry leaves the next spec, and a cron entry absent from the spec is one the node
 * unschedules. A run already in flight is not interrupted: the node was told what should
 * exist from now on, not what to kill, and a half-finished import that is killed mid-write
 * is worse than one that finishes and is never repeated.
 *
 * <p>Switching an entry off is {@link UpdateScheduledTask}, and it is what the panel
 * suggests first. Deleting loses the schedule, the command and the history of what it did.
 */
@Component
public class DeleteScheduledTask {

    private final ServiceRepository services;
    private final CronTaskRepository tasks;
    private final PublishNodeSpec specs;
    private final AuditTrail audit;

    public DeleteScheduledTask(ServiceRepository services, CronTaskRepository tasks,
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
     */
    @Transactional
    public void delete(AuditActor actor, Membership membership, UUID serviceId, String name) {
        membership.requireWrite("cron_task.delete");

        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));
        CronTask task = tasks.findByServiceIdAndName(serviceId, name == null ? "" : name.strip())
                .orElseThrow(() -> new NotFoundException(
                        "No scheduled command called " + name + " on this service"));

        tasks.delete(task);
        specs.forService(serviceId, "scheduled command " + task.name() + " removed from "
                + service.slug());

        audit.record(AuditEntry.succeeded(actor, "cron_task.delete",
                AuditTarget.of("cron_task", task.id(), task.name()), membership.organizationId(),
                "Removed from " + service.slug() + "; a run already in flight is left to finish"));
    }
}
