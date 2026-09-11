package lhqm.furimeo.wisper.project;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.service.DeleteService;
import lhqm.furimeo.wisper.service.ListServicesInProject;
import lhqm.furimeo.wisper.service.ServiceSummary;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Deletes a project and everything under it.
 *
 * <p>The services are removed one at a time through {@link DeleteService} rather than
 * left to the {@code ON DELETE CASCADE} on {@code service.project_id}. The cascade would
 * be correct in the database and wrong everywhere else: it would drop the placements
 * without telling the nodes holding the containers, so a deleted project would keep
 * serving traffic until something else happened to republish. Going through the service
 * teardown means each node is handed a spec that no longer contains the workload, which
 * is what actually stops it.
 *
 * <p>It also means one audit entry per service. That is the right number - "who deleted
 * the api service" is a question somebody asks later, and "it was inside a project that
 * was deleted" is only an answer if the entry exists.
 *
 * <p>A row disappearing from this database does not delete bytes on a node (schema.md
 * §5). The node removes containers that are no longer in its spec; volume data is removed
 * only on an explicit purge. That gap is deliberate and it is why the confirmation below
 * is worth the friction rather than being a second click.
 */
@Component
public class DeleteProject {

    private final ProjectRepository projects;
    private final ListServicesInProject servicesInProject;
    private final DeleteService deleteService;
    private final AuditTrail audit;

    public DeleteProject(ProjectRepository projects, ListServicesInProject servicesInProject,
                         DeleteService deleteService, AuditTrail audit) {
        this.projects = projects;
        this.servicesInProject = servicesInProject;
        this.deleteService = deleteService;
        this.audit = audit;
    }

    /**
     * @param actor        who is asking
     * @param membership   the caller's role; deleting a project needs administration,
     *                     not merely write access - a developer may break a service, and
     *                     should not be able to remove the folder holding six of them
     * @param projectId    the project
     * @param confirmation what the customer typed to confirm; must equal the slug
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a developer or a viewer
     * @throws NotFoundException                        when the project is not this
     *                                                  organization's
     * @throws RequestRejected                          when the confirmation does not
     *                                                  match
     */
    @Transactional
    public void delete(AuditActor actor, Membership membership, UUID projectId,
                       String confirmation) {
        membership.requireAdministration("project.delete");

        Project project = projects.findByIdAndOrganizationId(projectId,
                        membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("project", projectId));

        if (!project.slug().equals(Slug.lower(confirmation))) {
            throw new RequestRejected("confirmation",
                    "Type " + project.slug() + " to confirm that this project and everything "
                            + "in it should be deleted.");
        }

        List<ServiceSummary> services = servicesInProject.all(project.id());
        for (ServiceSummary service : services) {
            deleteService.delete(actor, membership, service.id());
        }
        projects.delete(project);

        audit.record(AuditEntry.succeeded(actor, "project.delete",
                AuditTarget.of("project", project.id(), project.name()),
                membership.organizationId(),
                "Deleted with " + services.size() + " service(s)"));
    }
}
