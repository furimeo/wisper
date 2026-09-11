package lhqm.furimeo.wisper.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.org.AccountRef;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.QuotaExceeded;
import lhqm.furimeo.wisper.org.QuotaGuard;
import lhqm.furimeo.wisper.org.QuotaResource;
import lhqm.furimeo.wisper.org.RecordRefusal;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.org.ResolveCurrentAccount;
import lhqm.furimeo.wisper.org.ResolveMembership;
import lhqm.furimeo.wisper.web.InertiaFlash;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Scheduled commands inside a service's container.
 *
 * <p>The page shows, for each entry, when it is next due and how the last run went. Both
 * halves matter: a cron whose schedule is right and whose command exits 1 every night
 * looks perfectly healthy if the screen only draws the schedule.
 *
 * <p>Renders {@code features/service/ServiceTasksPage.tsx}.
 */
@Controller
public class ScheduledTaskController {

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final ServiceRepository services;
    private final CronTaskRepository tasks;
    private final QuotaGuard quotas;
    private final CreateScheduledTask createTask;
    private final UpdateScheduledTask updateTask;
    private final DeleteScheduledTask deleteTask;
    private final RecordRefusal refusals;

    public ScheduledTaskController(ResolveCurrentAccount currentAccount,
                                   ResolveMembership memberships, ServiceRepository services,
                                   CronTaskRepository tasks, QuotaGuard quotas,
                                   CreateScheduledTask createTask, UpdateScheduledTask updateTask,
                                   DeleteScheduledTask deleteTask, RecordRefusal refusals) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.services = services;
        this.tasks = tasks;
        this.quotas = quotas;
        this.createTask = createTask;
        this.updateTask = updateTask;
        this.deleteTask = deleteTask;
        this.refusals = refusals;
    }

    /**
     * Props: {@code service}, {@code tasks} ({@link CronTaskView}, with a due time that is
     * recomputed when the stored one has passed), {@code policies}, {@code allowance},
     * {@code viewerRole}.
     */
    @GetMapping("/services/{serviceId}/tasks")
    public String tasks(@PathVariable UUID serviceId, Model model) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forService(account.id(), serviceId);
        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));

        Instant now = Instant.now();
        List<CronTaskView> views = tasks.findByServiceIdOrderByName(serviceId).stream()
                .map(task -> CronTaskView.of(task, now))
                .toList();

        model.addAttribute("service", ServiceView.of(service));
        model.addAttribute("tasks", views);
        model.addAttribute("policies", ConcurrencyPolicy.values());
        model.addAttribute("allowance",
                quotas.allowanceFor(membership.organizationId(), QuotaResource.CRON_TASK));
        model.addAttribute("viewerRole", membership.role());
        return "service/ServiceTasks";
    }

    @PostMapping("/services/{serviceId}/tasks")
    public String create(@PathVariable UUID serviceId,
                         @RequestParam(name = "name", required = false) String name,
                         @RequestParam(name = "schedule", required = false) String schedule,
                         @RequestParam(name = "timezone", required = false) String timezone,
                         @RequestParam(name = "command", required = false) String command,
                         @RequestParam(name = "timeoutSeconds", defaultValue = "300")
                         int timeoutSeconds,
                         @RequestParam(name = "concurrencyPolicy", required = false)
                         ConcurrencyPolicy policy,
                         @RequestParam(name = "enabled", defaultValue = "true") boolean enabled,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forService(account.id(), serviceId);
        try {
            CronTask created = createTask.create(actor, membership, serviceId, name, schedule,
                    timezone, CommandLine.parse(command), timeoutSeconds, policy, enabled);
            InertiaFlash.success(flash, created.name() + " is scheduled.");
        } catch (RequestRejected | QuotaExceeded | PermissionDenied refused) {
            refusals.of(actor, "cron_task.create", target(serviceId), membership.organizationId(),
                    refused, flash);
        }
        return back(serviceId);
    }

    @PostMapping("/services/{serviceId}/tasks/update")
    public String update(@PathVariable UUID serviceId,
                         @RequestParam(name = "name") String name,
                         @RequestParam(name = "schedule", required = false) String schedule,
                         @RequestParam(name = "timezone", required = false) String timezone,
                         @RequestParam(name = "command", required = false) String command,
                         @RequestParam(name = "timeoutSeconds", defaultValue = "300")
                         int timeoutSeconds,
                         @RequestParam(name = "concurrencyPolicy", required = false)
                         ConcurrencyPolicy policy,
                         @RequestParam(name = "enabled", defaultValue = "false") boolean enabled,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forService(account.id(), serviceId);
        try {
            CronTask updated = updateTask.update(actor, membership, serviceId, name, schedule,
                    timezone, CommandLine.parse(command), timeoutSeconds, policy, enabled);
            InertiaFlash.success(flash, updated.enabled()
                    ? updated.name() + " saved."
                    : updated.name() + " is switched off; its schedule and history are kept.");
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "cron_task.update", target(serviceId), membership.organizationId(),
                    refused, flash);
        }
        return back(serviceId);
    }

    @PostMapping("/services/{serviceId}/tasks/delete")
    public String delete(@PathVariable UUID serviceId,
                         @RequestParam(name = "name") String name,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forService(account.id(), serviceId);
        try {
            deleteTask.delete(actor, membership, serviceId, name);
            InertiaFlash.success(flash, name + " removed.");
        } catch (PermissionDenied refused) {
            refusals.of(actor, "cron_task.delete", target(serviceId), membership.organizationId(),
                    refused, flash);
        }
        return back(serviceId);
    }

    private static String back(UUID serviceId) {
        return "redirect:/services/" + serviceId + "/tasks";
    }

    private AuditTarget target(UUID serviceId) {
        return services.findById(serviceId)
                .map(service -> AuditTarget.of("service", service.id(), service.name()))
                .orElseGet(() -> AuditTarget.of("service", serviceId, ""));
    }
}
