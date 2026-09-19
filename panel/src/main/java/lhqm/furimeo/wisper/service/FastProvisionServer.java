package lhqm.furimeo.wisper.service;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.auth.Account;
import lhqm.furimeo.wisper.auth.AccountRepository;
import lhqm.furimeo.wisper.auth.PlatformRole;
import lhqm.furimeo.wisper.auth.RegisterUser;
import lhqm.furimeo.wisper.org.CreateOrganization;
import lhqm.furimeo.wisper.org.MemberRole;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.Organization;
import lhqm.furimeo.wisper.org.OrganizationRepository;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.project.CreateProject;
import lhqm.furimeo.wisper.project.Project;
import lhqm.furimeo.wisper.project.ProjectRepository;
import lhqm.furimeo.wisper.project.Slug;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 1-click fast server provisioning for commercial reseller and billing dashboards.
 *
 * <p>Trips through the entire platform tree in one transaction:
 * Account -> Organization -> Project -> Service -> Volume -> Auto-Start.
 * Eliminates all manual friction for paying customers so they can use their machine
 * immediately.
 */
@Component
public class FastProvisionServer {

    private static final Logger log = LoggerFactory.getLogger(FastProvisionServer.class);
    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#%";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository accounts;
    private final RegisterUser registerUser;
    private final OrganizationRepository organizations;
    private final CreateOrganization createOrganization;
    private final ProjectRepository projects;
    private final CreateProject createProject;
    private final CreateService createService;
    private final CreateVolume createVolume;
    private final SetEnvVar setEnvVar;
    private final StartService startService;

    public FastProvisionServer(
            AccountRepository accounts, RegisterUser registerUser,
            OrganizationRepository organizations, CreateOrganization createOrganization,
            ProjectRepository projects, CreateProject createProject,
            CreateService createService, CreateVolume createVolume,
            SetEnvVar setEnvVar, StartService startService) {
        this.accounts = accounts;
        this.registerUser = registerUser;
        this.organizations = organizations;
        this.createOrganization = createOrganization;
        this.projects = projects;
        this.createProject = createProject;
        this.createService = createService;
        this.createVolume = createVolume;
        this.setEnvVar = setEnvVar;
        this.startService = startService;
    }

    public record ProvisionResult(
            UUID serviceId,
            String serviceName,
            String serviceSlug,
            ServiceKind kind,
            String status,
            UUID projectId,
            UUID organizationId,
            UUID accountId,
            String accountEmail,
            boolean isNewAccount,
            String initialPassword,
            long cpuMillicores,
            long memoryBytes,
            long diskBytes,
            String image,
            Integer containerPort,
            Map<String, String> urls) {}

    @Transactional
    public ProvisionResult provision(AuditActor actor, ProvisionServerRequest request) {
        String email = Account.normaliseEmail(request.email());
        boolean isNew = false;
        String clearPassword = null;

        Account account = accounts.findByEmail(email).orElse(null);
        if (account == null) {
            isNew = true;
            clearPassword = (request.password() != null && !request.password().isBlank())
                    ? request.password().strip()
                    : generateSecurePassword();
            String displayName = (request.displayName() != null && !request.displayName().isBlank())
                    ? request.displayName().strip()
                    : defaultDisplayName(email);
            account = registerUser.run(email, displayName, clearPassword, PlatformRole.CUSTOMER, actor);
        }

        Organization org = resolveOrganization(actor, account, request);
        Membership membership = new Membership(org.id(), account.id(), MemberRole.OWNER);

        Project project = resolveProject(actor, membership, org.id(), request);

        Service service = createServiceEntity(actor, membership, project.id(), request);

        long effectiveDisk = attachStorageIfRequested(actor, membership, service, request);

        injectEnvironment(actor, membership, service.id(), request.environment());

        String initialStatus = "STOPPED";
        if (Boolean.TRUE.equals(request.autoStart())) {
            try {
                startService.start(actor, membership, service.id());
                initialStatus = "RUNNING";
            } catch (RequestRejected noNode) {
                // No schedulable node was found. The service is created and stays STOPPED.
                // The customer can start it once a node becomes available.
                log.warn("autoStart skipped for service {} — placement rejected: {}",
                        service.id(), noNode.getMessage());
            }
        }

        Map<String, String> urls = Map.of(
                "panel", "/services/" + service.id(),
                "terminal", "/services/" + service.id() + "/terminal",
                "files", "/services/" + service.id() + "/files",
                "metrics", "/services/" + service.id() + "/metrics");

        return new ProvisionResult(
                service.id(),
                service.name(),
                service.slug(),
                service.kind(),
                initialStatus,
                project.id(),
                org.id(),
                account.id(),
                account.email(),
                isNew,
                clearPassword,
                service.cpuMillicores(),
                service.memoryBytes(),
                effectiveDisk,
                service.image(),
                service.containerPort(),
                urls);
    }

    private Organization resolveOrganization(AuditActor actor, Account account, ProvisionServerRequest request) {
        List<Organization> accepted = organizations.findAcceptedFor(account.id());
        if (!accepted.isEmpty()) {
            if (request.organizationSlug() != null && !request.organizationSlug().isBlank()) {
                String targetSlug = Slug.normalise(request.organizationSlug());
                for (Organization org : accepted) {
                    if (org.slug().equalsIgnoreCase(targetSlug)) {
                        return org;
                    }
                }
            }
            return accepted.get(0);
        }

        String orgName = (request.organizationName() != null && !request.organizationName().isBlank())
                ? request.organizationName().strip()
                : account.displayName() + "'s Org";
        String baseSlug = (request.organizationSlug() != null && !request.organizationSlug().isBlank())
                ? Slug.normalise(request.organizationSlug())
                : "org-" + account.id().toString().replace("-", "").substring(0, 8);

        String slug = baseSlug;
        int attempt = 1;
        while (organizations.existsBySlug(slug)) {
            slug = baseSlug + "-" + attempt++;
        }
        return createOrganization.create(actor, account.id(), orgName, slug, null);
    }

    private Project resolveProject(AuditActor actor, Membership membership, UUID orgId, ProvisionServerRequest req) {
        String slug = (req.projectSlug() != null && !req.projectSlug().isBlank())
                ? Slug.normalise(req.projectSlug())
                : "default";
        var existing = projects.findByOrganizationIdAndSlug(orgId, slug);
        if (existing.isPresent()) {
            return existing.get();
        }

        var active = projects.findActiveInOrganization(orgId);
        if (!active.isEmpty() && (req.projectSlug() == null || req.projectSlug().isBlank())) {
            return active.get(0);
        }

        String name = (req.projectName() != null && !req.projectName().isBlank())
                ? req.projectName().strip()
                : "Default";
        return createProject.create(actor, membership, name, slug, "Auto-created by commercial provisioning");
    }

    private Service createServiceEntity(AuditActor actor, Membership membership, UUID projId, ProvisionServerRequest req) {
        List<String> cmd = resolveCommand(req);
        String image = req.image() != null && !req.image().isBlank()
                ? req.image().strip()
                : (req.kind() == ServiceKind.APP ? "python:3.12-slim" : null);

        String workingDir = req.workingDir() != null && !req.workingDir().isBlank()
                ? req.workingDir().strip()
                : "/app";

        ServiceDraft draft = new ServiceDraft(
                req.serviceName(), req.serviceSlug(), req.kind(), image, cmd, List.of(),
                workingDir, req.port(), null, null, RestartPolicy.ALWAYS, null, null, null, null,
                null, null, null, true, req.cpuMillicores() != null ? req.cpuMillicores() : 500L,
                req.memoryBytes() != null ? req.memoryBytes() : 268_435_456L,
                req.volumeSizeBytes() != null ? req.volumeSizeBytes() : 5_368_709_120L,
                null, List.of(), RuntimeIsolation.RUNSC, null);

        return createService.create(actor, membership, projId, draft);
    }

    private static List<String> resolveCommand(ProvisionServerRequest req) {
        if (req.command() != null && !req.command().isBlank()) {
            return CommandLine.parse(req.command());
        }
        if (req.kind() == ServiceKind.APP) {
            return List.of("sleep", "infinity");
        }
        return List.of();
    }

    private long attachStorageIfRequested(AuditActor actor, Membership membership, Service service, ProvisionServerRequest req) {
        long defaultSize = 5_368_709_120L;
        if (!Boolean.TRUE.equals(req.attachVolume()) || !service.isApp()) {
            return service.diskBytes();
        }

        String vName = req.volumeName() != null && !req.volumeName().isBlank()
                ? req.volumeName().strip()
                : "data";
        String vMount = req.volumeMountPath() != null && !req.volumeMountPath().isBlank()
                ? req.volumeMountPath().strip()
                : (req.workingDir() != null && !req.workingDir().isBlank() ? req.workingDir().strip() : "/app");
        long vSize = req.volumeSizeBytes() != null && req.volumeSizeBytes() > 0
                ? req.volumeSizeBytes()
                : defaultSize;

        createVolume.create(actor, membership, service.id(), vName, vMount, vSize, false, true);
        return vSize;
    }

    private void injectEnvironment(AuditActor actor, Membership membership, UUID serviceId, Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank()) {
                setEnvVar.set(actor, membership, serviceId, entry.getKey(), entry.getValue(), false);
            }
        }
    }

    private static String generateSecurePassword() {
        StringBuilder sb = new StringBuilder("wsp_");
        for (int i = 0; i < 14; i++) {
            sb.append(PASSWORD_CHARS.charAt(RANDOM.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }

    private static String defaultDisplayName(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
