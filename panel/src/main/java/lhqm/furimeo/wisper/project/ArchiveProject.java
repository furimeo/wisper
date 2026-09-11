package lhqm.furimeo.wisper.project;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.service.ArchiveProjectServices;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Puts a project away, and takes it out again.
 *
 * <p>Both directions live here because they are one fact - {@code archived_at} is set or
 * it is not - and splitting them into two files would be two chances to disagree about
 * what archiving means to the services inside.
 *
 * <p>What it means is this: <strong>archiving stops the work and keeps the bytes.</strong>
 * Every service in the project is marked archived and asked to stop, so an archived
 * project costs nothing in memory, in CPU or against the plan's service limit. Nothing is
 * deleted: the volumes, the databases, the deployment history and the customer's files
 * stay exactly where they are, and the placement stays too, so a restore puts the service
 * back on the node that already holds its disk rather than on whichever node the
 * scheduler likes today.
 *
 * <p>Restoring is deliberately not symmetrical. The services come back archived-no-longer,
 * but they stay stopped: starting a dozen containers because somebody clicked "restore" to
 * look at a description is a surprise nobody wants to pay for. The customer starts what
 * they meant to start.
 */
@Component
public class ArchiveProject {

    private final ProjectRepository projects;
    private final ArchiveProjectServices projectServices;
    private final AuditTrail audit;

    public ArchiveProject(ProjectRepository projects, ArchiveProjectServices projectServices,
                          AuditTrail audit) {
        this.projects = projects;
        this.projectServices = projectServices;
        this.audit = audit;
    }

    /**
     * Archives the project and stops everything in it.
     *
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        when the project is not this
     *                                                  organization's
     */
    @Transactional
    public Project archive(AuditActor actor, Membership membership, UUID projectId) {
        membership.requireWrite("project.archive");
        Project project = load(membership, projectId);
        if (project.isArchived()) {
            return project;
        }

        int stopped = projectServices.archiveAll(project.id(),
                "project " + project.slug() + " archived");
        Project archived = projects.save(project.archived(Instant.now()));

        audit.record(AuditEntry.succeeded(actor, "project.archive",
                AuditTarget.of("project", archived.id(), archived.name()),
                membership.organizationId(),
                "Archived; " + stopped + " service(s) stopped and kept"));
        return archived;
    }

    /**
     * Brings the project back. Its services stop being archived and stay stopped.
     *
     * <p>Recorded as {@code project.archive} as well, with a detail saying which way it
     * went. The audit vocabulary is a fixed list that is filtered on (panel-ports.md
     * §2.4), and one action covering both directions of one flag reads better in a trail
     * than a second verb that means "the first one, undone".
     */
    @Transactional
    public Project restore(AuditActor actor, Membership membership, UUID projectId) {
        membership.requireWrite("project.archive");
        Project project = load(membership, projectId);
        if (!project.isArchived()) {
            return project;
        }

        int restored = projectServices.restoreAll(project.id(),
                "project " + project.slug() + " restored");
        Project live = projects.save(project.restored());

        audit.record(AuditEntry.succeeded(actor, "project.archive",
                AuditTarget.of("project", live.id(), live.name()), membership.organizationId(),
                "Restored; " + restored + " service(s) available again, still stopped"));
        return live;
    }

    private Project load(Membership membership, UUID projectId) {
        return projects.findByIdAndOrganizationId(projectId, membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("project", projectId));
    }
}
