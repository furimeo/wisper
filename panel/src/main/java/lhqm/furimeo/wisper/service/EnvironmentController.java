package lhqm.furimeo.wisper.service;

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
import lhqm.furimeo.wisper.org.RecordRefusal;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.org.ResolveCurrentAccount;
import lhqm.furimeo.wisper.org.ResolveMembership;
import lhqm.furimeo.wisper.web.InertiaFlash;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Environment variables and secrets on one service.
 *
 * <p>One screen for both, because they are one environment as far as the workload is
 * concerned and a customer looking for {@code DATABASE_URL} should not have to remember
 * which list they put it in. Two lists on it, because the difference is real: one shows
 * values, the other shows only when each was last set.
 *
 * <p>Nothing on this page can reveal a secret. The secrets list is built by
 * {@link ListSecrets}, whose query does not select the value column, so there is no path
 * from here to a plaintext - not through the model, not through a props dump, not through
 * a stack trace.
 *
 * <p>Renders {@code features/service/ServiceEnvironmentPage.tsx}.
 */
@Controller
public class EnvironmentController {

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final ServiceRepository services;
    private final EnvVarRepository envVars;
    private final ListSecrets secretList;
    private final SetEnvVar setEnvVar;
    private final DeleteEnvVar deleteEnvVar;
    private final SetSecret setSecret;
    private final DeleteSecret deleteSecret;
    private final RecordRefusal refusals;

    public EnvironmentController(ResolveCurrentAccount currentAccount,
                                 ResolveMembership memberships, ServiceRepository services,
                                 EnvVarRepository envVars, ListSecrets secretList,
                                 SetEnvVar setEnvVar, DeleteEnvVar deleteEnvVar,
                                 SetSecret setSecret, DeleteSecret deleteSecret,
                                 RecordRefusal refusals) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.services = services;
        this.envVars = envVars;
        this.secretList = secretList;
        this.setEnvVar = setEnvVar;
        this.deleteEnvVar = deleteEnvVar;
        this.setSecret = setSecret;
        this.deleteSecret = deleteSecret;
        this.refusals = refusals;
    }

    /**
     * Props: {@code service}, {@code variables} (name, value, build-time flag),
     * {@code secrets} ({@link SecretView}, no values), {@code viewerRole}.
     */
    @GetMapping("/services/{serviceId}/environment")
    public String environment(@PathVariable UUID serviceId, Model model) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forService(account.id(), serviceId);
        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));

        model.addAttribute("service", ServiceView.of(service));
        model.addAttribute("variables", envVars.findByServiceIdOrderByName(serviceId));
        model.addAttribute("secrets", secretList.of(serviceId));
        model.addAttribute("viewerRole", membership.role());
        return "service/ServiceEnvironment";
    }

    /** Adds a plain variable or replaces its value. */
    @PostMapping("/services/{serviceId}/environment/variables")
    public String setVariable(@PathVariable UUID serviceId,
                              @RequestParam(name = "name", required = false) String name,
                              @RequestParam(name = "value", required = false) String value,
                              @RequestParam(name = "buildTime", defaultValue = "false")
                              boolean buildTime,
                              HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forService(account.id(), serviceId);
        try {
            EnvVar saved = setEnvVar.set(actor, membership, serviceId, name, value, buildTime);
            InertiaFlash.success(flash, saved.name()
                    + " saved. It reaches the container when it next starts.");
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "env_var.set", target(serviceId), membership.organizationId(),
                    refused, flash);
        }
        return back(serviceId);
    }

    @PostMapping("/services/{serviceId}/environment/variables/delete")
    public String deleteVariable(@PathVariable UUID serviceId,
                                 @RequestParam(name = "name") String name,
                                 HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forService(account.id(), serviceId);
        try {
            deleteEnvVar.delete(actor, membership, serviceId, name);
            InertiaFlash.success(flash, name + " removed.");
        } catch (PermissionDenied refused) {
            refusals.of(actor, "env_var.delete", target(serviceId), membership.organizationId(),
                    refused, flash);
        }
        return back(serviceId);
    }

    /** Adds a secret or rotates it. The value is encrypted before it is stored. */
    @PostMapping("/services/{serviceId}/environment/secrets")
    public String setSecretValue(@PathVariable UUID serviceId,
                                 @RequestParam(name = "name", required = false) String name,
                                 @RequestParam(name = "value", required = false) String value,
                                 @RequestParam(name = "buildTime", defaultValue = "false")
                                 boolean buildTime,
                                 HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forService(account.id(), serviceId);
        try {
            SecretView saved = setSecret.set(actor, membership, serviceId, name, value, buildTime);
            InertiaFlash.success(flash, saved.name()
                    + " saved. The panel cannot show it back to you - set a new one if you "
                    + "forget it.");
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "secret.set", target(serviceId), membership.organizationId(),
                    refused, flash);
        }
        return back(serviceId);
    }

    @PostMapping("/services/{serviceId}/environment/secrets/delete")
    public String deleteSecretValue(@PathVariable UUID serviceId,
                                    @RequestParam(name = "name") String name,
                                    HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forService(account.id(), serviceId);
        try {
            deleteSecret.delete(actor, membership, serviceId, name);
            InertiaFlash.success(flash, name + " removed.");
        } catch (PermissionDenied refused) {
            refusals.of(actor, "secret.delete", target(serviceId), membership.organizationId(),
                    refused, flash);
        }
        return back(serviceId);
    }

    private static String back(UUID serviceId) {
        return "redirect:/services/" + serviceId + "/environment";
    }

    private AuditTarget target(UUID serviceId) {
        return services.findById(serviceId)
                .map(service -> AuditTarget.of("service", service.id(), service.name()))
                .orElseGet(() -> AuditTarget.of("service", serviceId, ""));
    }
}
