package lhqm.furimeo.wisper.project;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
import lhqm.furimeo.wisper.service.BuildPreset;
import lhqm.furimeo.wisper.service.CommandLine;
import lhqm.furimeo.wisper.service.CreateService;
import lhqm.furimeo.wisper.service.CreateVolume;
import lhqm.furimeo.wisper.service.RestartPolicy;
import lhqm.furimeo.wisper.service.RuntimeIsolation;
import lhqm.furimeo.wisper.service.ServiceDraft;
import lhqm.furimeo.wisper.service.ServiceKind;
import lhqm.furimeo.wisper.web.InertiaFlash;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Adding a service to a project.
 *
 * <p>It lives in {@code project} rather than in {@code service} because the URL does:
 * {@code /projects/{projectId}/services} is under the {@code /projects/**} prefix, and
 * panel-http.md gives one prefix to exactly one package so that two controllers can never
 * claim the same path. Everything after the service exists is {@code /services/**} and
 * belongs to {@code service}.
 *
 * <p>The nesting is not decoration either. A service cannot be created without naming the
 * project it goes into, so the authorization check has something to check - which is the
 * failure mode a flat {@code POST /services?projectId=…} invites.
 *
 * <p>Renders {@code features/project/NewServicePage.tsx}.
 */
@Controller
public class ProjectServicesController {

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final ProjectRepository projects;
    private final QuotaGuard quotas;
    private final CreateService createService;
    private final CreateVolume createVolume;
    private final RecordRefusal refusals;

    public ProjectServicesController(ResolveCurrentAccount currentAccount,
                                     ResolveMembership memberships, ProjectRepository projects,
                                     QuotaGuard quotas, CreateService createService,
                                     CreateVolume createVolume,
                                     RecordRefusal refusals) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.projects = projects;
        this.quotas = quotas;
        this.createService = createService;
        this.createVolume = createVolume;
        this.refusals = refusals;
    }

    /**
     * The new-service form.
     *
     * <p>Props: {@code project}, {@code viewerRole}, {@code kinds}, {@code presets},
     * {@code isolations}, {@code restartPolicies}, {@code defaults} and
     * {@code allowances}. The vocabularies come from the server so the page cannot offer
     * a build preset the CHECK constraint will refuse, and {@code defaults} is the same
     * {@link ServiceDraft} the use-case would have applied - what the form shows and what
     * an empty submission produces are then the same numbers.
     */
    @GetMapping("/projects/{projectId}/services/new")
    public String form(@PathVariable UUID projectId, Model model) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forProject(account.id(), projectId);
        Project project = projects.findByIdAndOrganizationId(projectId,
                        membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("project", projectId));

        model.addAttribute("project", project);
        model.addAttribute("viewerRole", membership.role());
        model.addAttribute("kinds", ServiceKind.values());
        model.addAttribute("presets", BuildPreset.values());
        model.addAttribute("isolations", RuntimeIsolation.values());
        model.addAttribute("restartPolicies", RestartPolicy.values());
        model.addAttribute("defaults", ServiceDraft.defaults());
        model.addAttribute("allowances", List.of(
                quotas.allowanceFor(membership.organizationId(), QuotaResource.SERVICE),
                quotas.allowanceFor(membership.organizationId(), QuotaResource.MEMORY_BYTES),
                quotas.allowanceFor(membership.organizationId(), QuotaResource.CPU_MILLICORES)));
        return "project/NewService";
    }

    /** Creates it, and lands the customer on the service that now exists. */
    @PostMapping("/projects/{projectId}/services")
    public String create(@PathVariable UUID projectId,
                         @Valid @ModelAttribute("form") NewServiceForm form, BindingResult errors,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forProject(account.id(), projectId);
        String back = "redirect:/projects/" + projectId + "/services/new";
        if (errors.hasErrors()) {
            InertiaFlash.errors(flash, errors);
            return back;
        }
        try {
            var created = createService.create(actor, membership, projectId, form.toDraft());
            if (Boolean.TRUE.equals(form.createVolume()) && created.isApp()) {
                String vName = form.volumeName() == null || form.volumeName().isBlank()
                        ? "data"
                        : form.volumeName().strip();
                String vMount = form.volumeMountPath() == null || form.volumeMountPath().isBlank()
                        ? (form.workingDir() == null || form.workingDir().isBlank() ? "/app" : form.workingDir().strip())
                        : form.volumeMountPath().strip();
                long vSizeMib = form.volumeSizeMib() == null || form.volumeSizeMib() <= 0
                        ? 5120L
                        : form.volumeSizeMib();
                createVolume.create(actor, membership, created.id(), vName, vMount,
                        vSizeMib * 1_048_576L, false, true);
            }
            InertiaFlash.success(flash, created.name() + " is ready. Start it when you are.");
            return "redirect:/services/" + created.id();
        } catch (RequestRejected | QuotaExceeded | PermissionDenied refused) {
            refusals.of(actor, "service.create",
                    AuditTarget.unidentified("service", Slug.tidyName(form.name())),
                    membership.organizationId(), refused, flash);
            return back;
        }
    }

    /**
     * What the new-service form submits.
     *
     * <p>Flat and stringly-typed on purpose: this is what a multipart form actually
     * sends. The shape checks that belong to the domain - an app needs an image, a site
     * needs a preset and must not have a port - are in {@link CreateService}, where the
     * API route enforces them too.
     *
     * <p>{@code command} and {@code entrypoint} arrive as one line each and are split
     * into argv by {@link CommandLine}. The panel never hands a shell string to a node.
     */
    public record NewServiceForm(
            @NotBlank(message = "Give the service a name.")
            @Size(max = 120, message = "That name is too long.") String name,
            @Size(max = 63, message = "An address is at most 63 characters.") String slug,
            @NotNull(message = "Choose an app or a static site.") ServiceKind kind,

            @Size(max = 500) String image,
            @Size(max = 2000) String command,
            @Size(max = 2000) String entrypoint,
            @Size(max = 500) String workingDir,
            Integer containerPort,
            @Size(max = 500) String healthCheckPath,
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
            @Size(max = 500) String isolationReason,

            Boolean createVolume,
            @Size(max = 63) String volumeName,
            @Size(max = 500) String volumeMountPath,
            Long volumeSizeMib) {

        /** Turns the flat form into the value the use-case validates and stores. */
        ServiceDraft toDraft() {
            return new ServiceDraft(name, slug, kind, image, CommandLine.parse(command),
                    CommandLine.parse(entrypoint), workingDir, containerPort, healthCheckPath,
                    // The probe interval is not on the new-service form; the settings
                    // screen offers it once there is something to tune.
                    null,
                    restartPolicy, buildPreset, buildCommand, buildOutputDir, keepReleases,
                    repositoryUrl, repositoryBranch, repositoryCredential,
                    autoDeploy == null || autoDeploy, cpuMillicores, memoryBytes, diskBytes,
                    pidsLimit, CommandLine.tags(requiredTags), runtimeIsolation, isolationReason);
        }
    }
}
