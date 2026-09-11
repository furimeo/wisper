package lhqm.furimeo.wisper.project;

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
import lhqm.furimeo.wisper.service.ListServicesInProject;
import lhqm.furimeo.wisper.web.InertiaFlash;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * One project: what is in it, and the two writes that change the project itself.
 *
 * <p>Owns {@code /projects} and {@code /projects/{projectId}} (panel-http.md). Archiving,
 * restoring and deleting live in {@link ProjectSettingsController}, and creating a service
 * inside a project lives in {@link ProjectServicesController}; all three are the same URL
 * prefix and different screens.
 *
 * <p>Every path resolves a {@link Membership} first. {@link ResolveMembership#forProject}
 * answers 404 for a project in a tenant the visitor is not in - the same answer a project
 * that does not exist gets - so the URL space cannot be used to find out which ids are
 * real.
 */
@Controller
public class ProjectController {

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final ProjectRepository projects;
    private final ListServicesInProject servicesInProject;
    private final QuotaGuard quotas;
    private final CreateProject createProject;
    private final RenameProject renameProject;
    private final RecordRefusal refusals;

    public ProjectController(ResolveCurrentAccount currentAccount, ResolveMembership memberships,
                             ProjectRepository projects, ListServicesInProject servicesInProject,
                             QuotaGuard quotas, CreateProject createProject,
                             RenameProject renameProject, RecordRefusal refusals) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.projects = projects;
        this.servicesInProject = servicesInProject;
        this.quotas = quotas;
        this.createProject = createProject;
        this.renameProject = renameProject;
        this.refusals = refusals;
    }

    /**
     * The project overview.
     *
     * <p>Props: {@code project}, {@code services} (a {@code ServiceSummary} each),
     * {@code viewerRole}, {@code serviceAllowance} - the last so the page can grey out
     * "add a service" with the reason rather than let the customer fill in a form that is
     * going to be refused.
     *
     * <p>Renders {@code features/project/ProjectOverviewPage.tsx}.
     */
    @GetMapping("/projects/{projectId}")
    public String overview(@PathVariable UUID projectId, Model model) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forProject(account.id(), projectId);
        Project project = load(membership, projectId);

        model.addAttribute("project", project);
        model.addAttribute("services", servicesInProject.all(projectId));
        model.addAttribute("viewerRole", membership.role());
        model.addAttribute("serviceAllowance",
                quotas.allowanceFor(membership.organizationId(), QuotaResource.SERVICE));
        return "project/ProjectOverview";
    }

    /**
     * Opens a project. Posted from the dashboard, which supplies the organization from
     * the shared {@code organizations} prop.
     */
    @PostMapping("/projects")
    public String create(@Valid @ModelAttribute("form") CreateForm form, BindingResult errors,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        if (errors.hasErrors()) {
            InertiaFlash.errors(flash, errors);
            return "redirect:/";
        }
        Membership membership = memberships.forAccount(account.id(), form.organizationId());
        try {
            Project created = createProject.create(actor, membership, form.name(), form.slug(),
                    form.description());
            InertiaFlash.success(flash, created.name() + " is ready.");
            return "redirect:/projects/" + created.id();
        } catch (RequestRejected | QuotaExceeded | PermissionDenied refused) {
            refusals.of(actor, "project.create",
                    AuditTarget.unidentified("project", Slug.tidyName(form.name())),
                    membership.organizationId(), refused, flash);
            return "redirect:/";
        }
    }

    /** Renames a project, or rewrites its description. */
    @PostMapping("/projects/{projectId}")
    public String rename(@PathVariable UUID projectId,
                         @Valid @ModelAttribute("form") RenameForm form, BindingResult errors,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forProject(account.id(), projectId);
        String back = "redirect:/projects/" + projectId + "/settings";
        if (errors.hasErrors()) {
            InertiaFlash.errors(flash, errors);
            return back;
        }
        try {
            renameProject.rename(actor, membership, projectId, form.name(), form.description());
            InertiaFlash.success(flash, "Saved.");
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "project.update",
                    AuditTarget.of("project", projectId, Slug.tidyName(form.name())),
                    membership.organizationId(), refused, flash);
        }
        return back;
    }

    private Project load(Membership membership, UUID projectId) {
        return projects.findByIdAndOrganizationId(projectId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("project", projectId));
    }

    /**
     * The new-project form.
     *
     * @param organizationId which tenant it goes in; the caller's membership of it is
     *                       resolved before anything is written
     * @param slug           optional - left empty, it is derived from the name
     */
    public record CreateForm(
            @NotNull(message = "Choose an organization.") UUID organizationId,
            @NotBlank(message = "Give the project a name.")
            @Size(max = 120, message = "That name is too long.") String name,
            @Size(max = 63, message = "An address is at most 63 characters.") String slug,
            @Size(max = 500, message = "Keep the description under 500 characters.")
            String description) {
    }

    /** The rename form. The slug is deliberately absent: URLs do not move. */
    public record RenameForm(
            @NotBlank(message = "Give the project a name.")
            @Size(max = 120, message = "That name is too long.") String name,
            @Size(max = 500, message = "Keep the description under 500 characters.")
            String description) {
    }
}
