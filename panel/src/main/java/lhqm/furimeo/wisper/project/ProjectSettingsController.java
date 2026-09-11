package lhqm.furimeo.wisper.project;

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
import lhqm.furimeo.wisper.service.ListServicesInProject;
import lhqm.furimeo.wisper.web.InertiaFlash;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The dangerous half of a project: renaming lives next door, this is where a project is
 * put away or destroyed.
 *
 * <p>Separate from {@link ProjectController} because the screen is separate. Archive,
 * restore and delete are three buttons whose consequences a customer has to read before
 * pressing, and putting them on the overview - between "add a service" and a list of
 * running things - is how one gets pressed by accident on a phone.
 *
 * <p>Renders {@code features/project/ProjectSettingsPage.tsx}.
 */
@Controller
public class ProjectSettingsController {

    private final ResolveCurrentAccount currentAccount;
    private final ResolveMembership memberships;
    private final ProjectRepository projects;
    private final ListServicesInProject servicesInProject;
    private final ArchiveProject archiveProject;
    private final DeleteProject deleteProject;
    private final RecordRefusal refusals;

    public ProjectSettingsController(ResolveCurrentAccount currentAccount,
                                     ResolveMembership memberships, ProjectRepository projects,
                                     ListServicesInProject servicesInProject,
                                     ArchiveProject archiveProject, DeleteProject deleteProject,
                                     RecordRefusal refusals) {
        this.currentAccount = currentAccount;
        this.memberships = memberships;
        this.projects = projects;
        this.servicesInProject = servicesInProject;
        this.archiveProject = archiveProject;
        this.deleteProject = deleteProject;
        this.refusals = refusals;
    }

    /**
     * Props: {@code project}, {@code serviceCount}, {@code viewerRole}.
     *
     * <p>{@code serviceCount} is what the delete confirmation counts down at the
     * customer: "this deletes 3 services and everything in them" is a sentence somebody
     * reads, where "are you sure?" is a sentence somebody clicks past.
     */
    @GetMapping("/projects/{projectId}/settings")
    public String settings(@PathVariable UUID projectId, Model model) {
        AccountRef account = currentAccount.require();
        Membership membership = memberships.forProject(account.id(), projectId);
        Project project = load(membership, projectId);

        model.addAttribute("project", project);
        model.addAttribute("serviceCount", servicesInProject.all(projectId).size());
        model.addAttribute("viewerRole", membership.role());
        return "project/ProjectSettings";
    }

    /** Stops everything in the project and takes it off the dashboard. */
    @PostMapping("/projects/{projectId}/archive")
    public String archive(@PathVariable UUID projectId, HttpServletRequest request,
                          RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forProject(account.id(), projectId);
        try {
            Project archived = archiveProject.archive(actor, membership, projectId);
            InertiaFlash.success(flash, archived.name()
                    + " is archived. Its services are stopped and nothing was deleted.");
        } catch (PermissionDenied refused) {
            refusals.of(actor, "project.archive", target(projectId),
                    membership.organizationId(), refused, flash);
        }
        return "redirect:/projects/" + projectId + "/settings";
    }

    /** Brings it back. The services come back stopped. */
    @PostMapping("/projects/{projectId}/restore")
    public String restore(@PathVariable UUID projectId, HttpServletRequest request,
                          RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forProject(account.id(), projectId);
        try {
            Project live = archiveProject.restore(actor, membership, projectId);
            InertiaFlash.success(flash, live.name()
                    + " is back. Its services are still stopped - start the ones you need.");
        } catch (PermissionDenied refused) {
            refusals.of(actor, "project.archive", target(projectId),
                    membership.organizationId(), refused, flash);
        }
        return "redirect:/projects/" + projectId + "/settings";
    }

    /**
     * Deletes the project and everything under it.
     *
     * <p>{@code confirmation} is the project's slug, typed by hand. The use-case checks
     * it rather than the form: a confirmation enforced only in the browser is a
     * confirmation that is not enforced.
     */
    @PostMapping("/projects/{projectId}/delete")
    public String delete(@PathVariable UUID projectId,
                         @RequestParam(name = "confirmation", required = false) String confirmation,
                         HttpServletRequest request, RedirectAttributes flash) {
        AccountRef account = currentAccount.require();
        AuditActor actor = AuditActor.account(account.id(), account.email(), request);
        Membership membership = memberships.forProject(account.id(), projectId);
        try {
            deleteProject.delete(actor, membership, projectId, confirmation);
            InertiaFlash.success(flash, "Project deleted.");
            return "redirect:/";
        } catch (RequestRejected | PermissionDenied refused) {
            refusals.of(actor, "project.delete", target(projectId), membership.organizationId(),
                    refused, flash);
            return "redirect:/projects/" + projectId + "/settings";
        }
    }

    private AuditTarget target(UUID projectId) {
        return projects.findById(projectId)
                .map(project -> AuditTarget.of("project", project.id(), project.name()))
                .orElseGet(() -> AuditTarget.of("project", projectId, ""));
    }

    private Project load(Membership membership, UUID projectId) {
        return projects.findByIdAndOrganizationId(projectId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("project", projectId));
    }
}
