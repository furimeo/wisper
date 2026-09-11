package lhqm.furimeo.wisper.service;

import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.org.AccountRef;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.RecordRefusal;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.org.ResolveCurrentAccount;
import lhqm.furimeo.wisper.org.ResolveMembership;
import lhqm.furimeo.wisper.web.InertiaFlash;

/**
 * The three buttons at the top of a service: start, stop, restart.
 *
 * <p>A controller of their own because they share one shape - no form, one path variable,
 * redirect back to where the customer was - and because they are the paths most likely to
 * be hit twice by an impatient thumb on a phone. Each use-case is idempotent for exactly
 * that reason.
 *
 * <p>Every one of them ends where it started, {@code /services/{id}}, with a flash
 * message. There is no page of their own to render.
 */
@Controller
public class ServiceLifecycleController {

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final ServiceRepository services;
    private final StartService startService;
    private final StopService stopService;
    private final RestartService restartService;
    private final RecordRefusal refusals;

    public ServiceLifecycleController(ResolveCurrentAccount currentAccount,
                                      ResolveMembership memberships, ServiceRepository services,
                                      StartService startService, StopService stopService,
                                      RestartService restartService, RecordRefusal refusals) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.services = services;
        this.startService = startService;
        this.stopService = stopService;
        this.restartService = restartService;
        this.refusals = refusals;
    }

    @PostMapping("/services/{serviceId}/start")
    public String start(@PathVariable UUID serviceId, HttpServletRequest request,
                        RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forService(account.id(), serviceId);
        try {
            Service started = startService.start(actor, membership, serviceId);
            InertiaFlash.success(flash, started.name() + " is starting. The node converges "
                    + "within a few seconds.");
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "service.start", target(serviceId), membership.organizationId(),
                    refused, flash);
        }
        return "redirect:/services/" + serviceId;
    }

    @PostMapping("/services/{serviceId}/stop")
    public String stop(@PathVariable UUID serviceId, HttpServletRequest request,
                       RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forService(account.id(), serviceId);
        try {
            Service stopped = stopService.stop(actor, membership, serviceId);
            InertiaFlash.success(flash, stopped.name()
                    + " is stopping. Its volumes and logs stay where they are.");
        } catch (PermissionDenied refused) {
            refusals.of(actor, "service.stop", target(serviceId), membership.organizationId(),
                    refused, flash);
        }
        return "redirect:/services/" + serviceId;
    }

    @PostMapping("/services/{serviceId}/restart")
    public String restart(@PathVariable UUID serviceId, HttpServletRequest request,
                          RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forService(account.id(), serviceId);
        try {
            Service restarted = restartService.restart(actor, membership, serviceId);
            InertiaFlash.success(flash, restarted.name() + " is restarting.");
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "service.restart", target(serviceId), membership.organizationId(),
                    refused, flash);
        }
        return "redirect:/services/" + serviceId;
    }

    /**
     * The audit target, named where possible.
     *
     * <p>A refusal is recorded whether or not the row is still readable, so this falls
     * back to the bare id rather than throwing on the way to writing a {@code DENIED}
     * entry - the entry somebody comes looking for is exactly the one about something that
     * went wrong.
     */
    private AuditTarget target(UUID serviceId) {
        return services.findById(serviceId)
                .map(service -> AuditTarget.of("service", service.id(), service.name()))
                .orElseGet(() -> AuditTarget.of("service", serviceId, ""));
    }
}
