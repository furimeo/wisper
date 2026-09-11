package lhqm.furimeo.wisper.service;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.crypto.SecretCipher;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.QuotaGuard;
import lhqm.furimeo.wisper.org.QuotaResource;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.project.Project;
import lhqm.furimeo.wisper.project.ProjectRepository;
import lhqm.furimeo.wisper.project.Slug;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Adds a service to a project.
 *
 * <p>It creates the row and stops. Nothing is published to a node, and that is not an
 * omission: a new service is {@link DesiredState#STOPPED} with no placement, so there is
 * no node whose spec would change. The publish happens the first time somebody presses
 * start, which is also the first moment there is something worth running - an app with no
 * deployment or a site with no release would come up, fail and be reported as broken by a
 * platform that broke it.
 *
 * <p>Three quota resources are charged, not one. A service costs a row, some memory and
 * some CPU, and a plan that limits only the count is a plan a customer can exhaust with
 * one 64 GiB service.
 */
@Component
public class CreateService {

    private final ServiceRepository services;
    private final ProjectRepository projects;
    private final QuotaGuard quotas;
    private final SecretCipher cipher;
    private final AuditTrail audit;

    public CreateService(ServiceRepository services, ProjectRepository projects, QuotaGuard quotas,
                         SecretCipher cipher, AuditTrail audit) {
        this.services = services;
        this.projects = projects;
        this.quotas = quotas;
        this.cipher = cipher;
        this.audit = audit;
    }

    /**
     * @param membership the caller's role in the tenant owning the project
     * @param submitted  what the form or the API sent, before the shape rules are applied
     * @throws lhqm.furimeo.wisper.org.PermissionDenied for a viewer
     * @throws NotFoundException                        when the project is not this
     *                                                  organization's
     * @throws RequestRejected                          for a shape the kind does not
     *                                                  allow, or a taken address
     * @throws lhqm.furimeo.wisper.org.QuotaExceeded    at the plan's service, memory or
     *                                                  CPU ceiling
     */
    @Transactional
    public Service create(AuditActor actor, Membership membership, UUID projectId,
                          ServiceDraft submitted) {
        membership.requireWrite("service.create");

        Project project = projects.findByIdAndOrganizationId(projectId,
                        membership.organizationId())
                .orElseThrow(() -> NotFoundException.of("project", projectId));
        if (project.isArchived()) {
            throw new RequestRejected(null,
                    "This project is archived. Restore it before adding services to it.");
        }

        ServiceDraft draft = ServiceShape.validated(submitted);
        String slug = Slug.of(draft.slug(), draft.name());
        if (!Slug.isValid(slug)) {
            throw new RequestRejected("slug", "That address will not work. " + Slug.rule(2));
        }

        UUID organizationId = membership.organizationId();
        quotas.require(organizationId, QuotaResource.SERVICE, 1);
        quotas.require(organizationId, QuotaResource.MEMORY_BYTES, draft.memoryBytes());
        quotas.require(organizationId, QuotaResource.CPU_MILLICORES, draft.cpuMillicores());

        if (services.existsByProjectIdAndSlug(projectId, slug)) {
            throw new RequestRejected("slug",
                    "Another service in this project already uses that address.");
        }

        ServiceDraft stored = ServiceShape.withCredential(draft,
                draft.repositoryCredential() == null
                        ? null
                        : cipher.encrypt(draft.repositoryCredential()));

        Service service = services.save(Service.from(UUID.randomUUID(), projectId, slug,
                cipher.encrypt(WebhookSecret.generate()), stored));

        audit.record(AuditEntry.succeeded(actor, "service.create",
                AuditTarget.of("service", service.id(), service.name()), organizationId,
                service.kind().label() + " created in project " + project.slug()
                        + ", stopped until started"));
        return service;
    }
}
