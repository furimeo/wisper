package lhqm.furimeo.wisper.project;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Changes a project's display name and description.
 *
 * <p>The slug is not touched and there is no route that changes it. Every URL under
 * {@code /projects/{id}} is keyed by the id rather than by the slug, so a rename costs
 * nothing - but a customer who renames a project and finds their webhook, their
 * bookmark and their colleague's link all broken has been charged for it anyway. The
 * address is chosen once, at creation.
 *
 * <p>No spec is republished. A project's name does not appear in a {@code NodeSpec}: the
 * node knows workloads by id and names them from the service, so renaming the folder they
 * sit in changes nothing a node has to converge to.
 */
@Component
public class RenameProject {

    private final ProjectRepository projects;
    private final AuditTrail audit;

    public RenameProject(ProjectRepository projects, AuditTrail audit) {
        this.projects = projects;
        this.audit = audit;
    }

    /**
     * @param actor       who is asking
     * @param membership  the caller's role in the owning organization
     * @param projectId   the project to rename
     * @param name        the new display name
     * @param description the new description; empty clears it
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        when the project is not this
     *                                                  organization's
     * @throws RequestRejected                          for a blank name
     */
    @Transactional
    public Project rename(AuditActor actor, Membership membership, UUID projectId, String name,
                          String description) {
        membership.requireWrite("project.update");

        String displayName = Slug.tidyName(name);
        if (displayName.isEmpty()) {
            throw new RequestRejected("name", "Give the project a name.");
        }

        Project project = projects.findByIdAndOrganizationId(projectId,
                        membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("project", projectId));

        String previousName = project.name();
        Project renamed = projects.save(project.renamedTo(displayName,
                description == null ? "" : description.strip()));

        audit.record(AuditEntry.succeeded(actor, "project.update",
                AuditTarget.of("project", renamed.id(), renamed.name()),
                membership.organizationId(),
                previousName.equals(displayName)
                        ? "Description changed"
                        : "Renamed from " + previousName));
        return renamed;
    }
}
