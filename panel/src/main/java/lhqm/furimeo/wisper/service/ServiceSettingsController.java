package lhqm.furimeo.wisper.service;

import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.org.AccountRef;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.QuotaExceeded;
import lhqm.furimeo.wisper.org.RecordRefusal;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.org.ResolveCurrentAccount;
import lhqm.furimeo.wisper.org.ResolveMembership;
import lhqm.furimeo.wisper.web.InertiaFlash;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Everything about a service that is not "is it running": its image or its build, its
 * limits, its isolation - and the button that removes it.
 *
 * <p>Renders {@code features/service/ServiceSettingsPage.tsx}.
 */
@Controller
public class ServiceSettingsController {

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final ServiceRepository services;
    private final UpdateServiceRuntime updateRuntime;
    private final DeleteService deleteService;
    private final RecordRefusal refusals;

    public ServiceSettingsController(ResolveCurrentAccount currentAccount,
                                     ResolveMembership memberships, ServiceRepository services,
                                     UpdateServiceRuntime updateRuntime,
                                     DeleteService deleteService, RecordRefusal refusals) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.services = services;
        this.updateRuntime = updateRuntime;
        this.deleteService = deleteService;
        this.refusals = refusals;
    }

    /**
     * Props: {@code service}, {@code viewerRole}, and the vocabularies the form offers -
     * {@code presets}, {@code isolations}, {@code restartPolicies}. The lists come from
     * the server so the page cannot offer a value the CHECK constraints will refuse.
     */
    @GetMapping("/services/{serviceId}/settings")
    public String settings(@PathVariable UUID serviceId, Model model) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forService(account.id(), serviceId);
        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));

        model.addAttribute("service", ServiceView.of(service));
        model.addAttribute("viewerRole", membership.role());
        model.addAttribute("presets", BuildPreset.values());
        model.addAttribute("isolations", RuntimeIsolation.values());
        model.addAttribute("restartPolicies", RestartPolicy.values());
        return "service/ServiceSettings";
    }

    @PostMapping("/services/{serviceId}/settings")
    public String save(@PathVariable UUID serviceId,
                       @Valid @ModelAttribute("form") SettingsForm form, BindingResult errors,
                       HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forService(account.id(), serviceId);
        String back = "redirect:/services/" + serviceId + "/settings";
        if (errors.hasErrors()) {
            InertiaFlash.errors(flash, errors);
            return back;
        }
        try {
            updateRuntime.update(actor, membership, serviceId, form.toDraft());
            InertiaFlash.success(flash, "Saved. The node has the new spec.");
        } catch (RequestRejected | QuotaExceeded | PermissionDenied refused) {
            refusals.of(actor, "service.update", target(serviceId), membership.organizationId(),
                    refused, flash);
        }
        return back;
    }

    /**
     * Deletes the service.
     *
     * <p>{@code confirmation} is the service's address, typed by hand. Checked here rather
     * than in {@link DeleteService} because the use-case is also what a project deletion
     * calls, where the customer has already confirmed once at the level above and asking
     * again per service would be a form with eleven boxes on it.
     */
    @PostMapping("/services/{serviceId}/delete")
    public String delete(@PathVariable UUID serviceId,
                         @RequestParam(name = "confirmation", required = false) String confirmation,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forService(account.id(), serviceId);

        Service service = services.findOwnedBy(serviceId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("service", serviceId));
        UUID projectId = service.projectId();

        if (!service.slug().equals(confirmation == null ? null : confirmation.strip())) {
            refusals.of(actor, "service.delete", target(serviceId), membership.organizationId(),
                    new RequestRejected("confirmation", "Type " + service.slug()
                            + " to confirm that this service should be deleted."), flash);
            return "redirect:/services/" + serviceId + "/settings";
        }
        try {
            deleteService.delete(actor, membership, serviceId);
            InertiaFlash.success(flash, service.name()
                    + " is deleted. Volume data on the node is kept until it is purged.");
            return "redirect:/projects/" + projectId;
        } catch (PermissionDenied refused) {
            refusals.of(actor, "service.delete", target(serviceId), membership.organizationId(),
                    refused, flash);
            return "redirect:/services/" + serviceId + "/settings";
        }
    }

    private AuditTarget target(UUID serviceId) {
        return services.findById(serviceId)
                .map(service -> AuditTarget.of("service", service.id(), service.name()))
                .orElseGet(() -> AuditTarget.of("service", serviceId, ""));
    }

    /**
     * The settings form.
     *
     * <p>No {@code kind} and no {@code slug}: neither is editable, and a form field for
     * something the use-case ignores is a promise the page cannot keep.
     *
     * <p>{@code repositoryCredential} is a password box that renders empty even when one
     * is stored. Blank means "leave it alone" - the only field in this form where an empty
     * input does not mean an empty value, and the page says so next to it.
     */
    public record SettingsForm(
            @NotBlank(message = "Give the service a name.")
            @Size(max = 120, message = "That name is too long.") String name,

            @Size(max = 500) String image,
            @Size(max = 2000) String command,
            @Size(max = 2000) String entrypoint,
            @Size(max = 500) String workingDir,
            Integer containerPort,
            @Size(max = 500) String healthCheckPath,
            Integer healthCheckIntervalSeconds,
            RestartPolicy restartPolicy,

            BuildPreset buildPreset,
            @Size(max = 2000) String buildCommand,
            @Size(max = 500) String buildOutputDir,
            Integer keepReleases,

            @Size(max = 1000) String repositoryUrl,
            @Size(max = 250) String repositoryBranch,
            @Size(max = 4000) String repositoryCredential,
            Boolean autoDeploy,

            Long cpuMillicores,
            Long memoryBytes,
            Long diskBytes,
            Integer pidsLimit,
            @Size(max = 500) String requiredTags,

            RuntimeIsolation runtimeIsolation,
            @Size(max = 500) String isolationReason) {

        ServiceDraft toDraft() {
            return new ServiceDraft(name, null, null, image, CommandLine.parse(command),
                    CommandLine.parse(entrypoint), workingDir, containerPort, healthCheckPath,
                    healthCheckIntervalSeconds, restartPolicy, buildPreset, buildCommand,
                    buildOutputDir, keepReleases, repositoryUrl, repositoryBranch,
                    repositoryCredential, autoDeploy == null || autoDeploy, cpuMillicores,
                    memoryBytes, diskBytes, pidsLimit, CommandLine.tags(requiredTags),
                    runtimeIsolation, isolationReason);
        }
    }
}
