package lhqm.furimeo.wisper.project;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.QuotaGuard;
import lhqm.furimeo.wisper.org.QuotaResource;
import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * Opens a project inside an organization.
 *
 * <p>Nothing is published to a node here. A project holds no workload of its own, so
 * there is no spec to change until a service goes into it - which is the one write in
 * this package that does not end in a republish.
 */
@Component
public class CreateProject {

    private final ProjectRepository projects;
    private final QuotaGuard quotas;
    private final AuditTrail audit;

    public CreateProject(ProjectRepository projects, QuotaGuard quotas, AuditTrail audit) {
        this.projects = projects;
        this.quotas = quotas;
        this.audit = audit;
    }

    /**
     * @param actor       who is asking, for the trail
     * @param membership  the caller's role in the organization the project goes into
     * @param name        the display name
     * @param slug        the URL fragment; derived from the name when left empty
     * @param description free text, may be empty
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws lhqm.furimeo.wisper.org.QuotaExceeded    at the plan's project limit, or
     *                                                  when the organization is suspended
     * @throws RequestRejected                          for a malformed or taken slug
     */
    @Transactional
    public Project create(AuditActor actor, Membership membership, String name, String slug,
                          String description) {
        membership.requireWrite("project.create");

        String displayName = Slug.tidyName(name);
        if (displayName.isEmpty()) {
            throw new RequestRejected("name", "Give the project a name.");
        }
        String address = Slug.of(slug, displayName);
        if (!Slug.isValid(address)) {
            throw new RequestRejected("slug", "That address will not work. " + Slug.rule(2));
        }

        // Before the insert and inside the transaction, so two requests racing for the
        // last project on a plan cannot both find room.
        quotas.require(membership.organizationId(), QuotaResource.PROJECT, 1);

        if (projects.existsByOrganizationIdAndSlug(membership.organizationId(), address)) {
            throw new RequestRejected("slug", "Another project here already uses that address.");
        }

        Project project = projects.save(Project.opened(UUID.randomUUID(),
                membership.organizationId(), displayName, address,
                description == null ? "" : description.strip()));

        audit.record(AuditEntry.succeeded(actor, "project.create",
                AuditTarget.of("project", project.id(), project.name()),
                membership.organizationId(), "Created as /" + project.slug()));
        return project;
    }
}
