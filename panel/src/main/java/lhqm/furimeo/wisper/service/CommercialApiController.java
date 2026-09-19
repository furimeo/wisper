package lhqm.furimeo.wisper.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.auth.Account;
import lhqm.furimeo.wisper.auth.AccountRepository;
import lhqm.furimeo.wisper.auth.ApiScope;
import lhqm.furimeo.wisper.auth.ApiTokenPrincipal;
import lhqm.furimeo.wisper.auth.PlatformRole;
import lhqm.furimeo.wisper.auth.ReactivateAccount;
import lhqm.furimeo.wisper.auth.SuspendAccount;
import lhqm.furimeo.wisper.org.MemberRole;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.QuotaExceeded;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.org.ResolveMembership;
import lhqm.furimeo.wisper.project.ListProjects;
import lhqm.furimeo.wisper.project.ProjectSummary;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Commercial REST API for billing platforms, resellers, and coin-based SaaS dashboards.
 *
 * <p>Handles 1-click fast-provisioning, hardware spec inspection for hourly coin deductions,
 * automated pausing on depleted balances, resumes, and permanent evictions.
 */
@RestController
@RequestMapping("/api/v1/commercial")
public class CommercialApiController {

    private final FastProvisionServer fastProvisionServer;
    private final ServiceRepository services;
    private final VolumeRepository volumes;
    private final LocateService locateService;
    private final ListServicesInProject listServicesInProject;
    private final ListProjects listProjects;
    private final StartService startService;
    private final StopService stopService;
    private final RestartService restartService;
    private final DeleteService deleteService;
    private final ResolveMembership memberships;
    private final AccountRepository accounts;
    private final SuspendAccount suspendAccount;
    private final ReactivateAccount reactivateAccount;

    public CommercialApiController(
            FastProvisionServer fastProvisionServer,
            ServiceRepository services,
            VolumeRepository volumes,
            LocateService locateService,
            ListServicesInProject listServicesInProject,
            ListProjects listProjects,
            StartService startService,
            StopService stopService,
            RestartService restartService,
            DeleteService deleteService,
            ResolveMembership memberships,
            AccountRepository accounts,
            SuspendAccount suspendAccount,
            ReactivateAccount reactivateAccount) {
        this.fastProvisionServer = fastProvisionServer;
        this.services = services;
        this.volumes = volumes;
        this.locateService = locateService;
        this.listServicesInProject = listServicesInProject;
        this.listProjects = listProjects;
        this.startService = startService;
        this.stopService = stopService;
        this.restartService = restartService;
        this.deleteService = deleteService;
        this.memberships = memberships;
        this.accounts = accounts;
        this.suspendAccount = suspendAccount;
        this.reactivateAccount = reactivateAccount;
    }

    @PostMapping(path = {"/provision", "/orders"})
    public ResponseEntity<?> provision(
            @AuthenticationPrincipal ApiTokenPrincipal principal,
            @RequestBody ProvisionServerRequest request,
            HttpServletRequest httpRequest) {
        AuditActor actor = resolveActor(principal, httpRequest);
        requireScopeOrAdmin(principal, ApiScope.SERVICES_WRITE);
        FastProvisionServer.ProvisionResult result = fastProvisionServer.provision(actor, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/services/{serviceId}")
    public ResponseEntity<?> getService(
            @AuthenticationPrincipal ApiTokenPrincipal principal,
            @PathVariable UUID serviceId) {
        requireScopeOrAdmin(principal, ApiScope.SERVICES_READ);
        Service service = services.findById(serviceId)
                .orElseThrow(() -> NotFoundException.of("service", serviceId));
        ServiceLocation location = locateService.byId(serviceId);
        resolveCallerMembership(principal, serviceId, location.organizationId());

        ServiceSummary summary = listServicesInProject.one(serviceId).orElse(null);
        List<Volume> attachedVolumes = volumes.findByServiceIdOrderByName(serviceId);
        return ResponseEntity.ok(CommercialServiceView.from(service, summary, location.organizationId(), attachedVolumes));
    }

    @PostMapping("/services/{serviceId}/start")
    public ResponseEntity<?> start(
            @AuthenticationPrincipal ApiTokenPrincipal principal,
            @PathVariable UUID serviceId,
            HttpServletRequest httpRequest) {
        AuditActor actor = resolveActor(principal, httpRequest);
        requireScopeOrAdmin(principal, ApiScope.SERVICES_WRITE);
        ServiceLocation location = locateService.byId(serviceId);
        Membership membership = resolveCallerMembership(principal, serviceId, location.organizationId());
        startService.start(actor, membership, serviceId);
        return ResponseEntity.ok(Map.of("status", "RUNNING", "message", "Service started successfully."));
    }

    @PostMapping("/services/{serviceId}/stop")
    public ResponseEntity<?> stop(
            @AuthenticationPrincipal ApiTokenPrincipal principal,
            @PathVariable UUID serviceId,
            HttpServletRequest httpRequest) {
        AuditActor actor = resolveActor(principal, httpRequest);
        requireScopeOrAdmin(principal, ApiScope.SERVICES_WRITE);
        ServiceLocation location = locateService.byId(serviceId);
        Membership membership = resolveCallerMembership(principal, serviceId, location.organizationId());
        stopService.stop(actor, membership, serviceId);
        return ResponseEntity.ok(Map.of("status", "STOPPED", "message", "Service stopped. Volumes preserved."));
    }

    @PostMapping("/services/{serviceId}/restart")
    public ResponseEntity<?> restart(
            @AuthenticationPrincipal ApiTokenPrincipal principal,
            @PathVariable UUID serviceId,
            HttpServletRequest httpRequest) {
        AuditActor actor = resolveActor(principal, httpRequest);
        requireScopeOrAdmin(principal, ApiScope.SERVICES_WRITE);
        ServiceLocation location = locateService.byId(serviceId);
        Membership membership = resolveCallerMembership(principal, serviceId, location.organizationId());
        Service service = services.findById(serviceId)
                .orElseThrow(() -> NotFoundException.of("service", serviceId));
        if (service.desiredState() == DesiredState.STOPPED) {
            startService.start(actor, membership, serviceId);
        } else {
            restartService.restart(actor, membership, serviceId);
        }
        return ResponseEntity.ok(Map.of("status", "RUNNING", "message", "Service restart initiated."));
    }

    @DeleteMapping("/services/{serviceId}")
    public ResponseEntity<?> delete(
            @AuthenticationPrincipal ApiTokenPrincipal principal,
            @PathVariable UUID serviceId,
            HttpServletRequest httpRequest) {
        AuditActor actor = resolveActor(principal, httpRequest);
        requireScopeOrAdmin(principal, ApiScope.SERVICES_WRITE);
        ServiceLocation location = locateService.byId(serviceId);
        Membership membership = resolveCallerMembership(principal, serviceId, location.organizationId());
        deleteService.delete(actor, membership, serviceId);
        return ResponseEntity.ok(Map.of("deleted", true, "message", "Service permanently deleted."));
    }

    @GetMapping("/services")
    public ResponseEntity<?> list(
            @AuthenticationPrincipal ApiTokenPrincipal principal,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) UUID accountId) {
        requireScopeOrAdmin(principal, ApiScope.SERVICES_READ);
        UUID targetAccountId = principal.accountId();
        if (principal.role() == PlatformRole.ADMIN) {
            if (email != null && !email.isBlank()) {
                targetAccountId = accounts.findByEmail(Account.normaliseEmail(email))
                        .map(Account::id)
                        .orElse(null);
            } else if (accountId != null) {
                targetAccountId = accountId;
            }
        }

        if (targetAccountId == null) {
            return ResponseEntity.ok(List.of());
        }

        List<ProjectSummary> userProjects = listProjects.visibleTo(targetAccountId);
        List<CommercialServiceView> result = new ArrayList<>();
        for (ProjectSummary proj : userProjects) {
            List<Service> liveServices = services.findLiveIn(proj.id());
            for (Service s : liveServices) {
                ServiceSummary summary = listServicesInProject.one(s.id()).orElse(null);
                List<Volume> attached = volumes.findByServiceIdOrderByName(s.id());
                result.add(CommercialServiceView.from(s, summary, proj.organizationId(), attached));
            }
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/accounts/{accountId}/suspend")
    public ResponseEntity<?> suspendAccount(
            @AuthenticationPrincipal ApiTokenPrincipal principal,
            @PathVariable UUID accountId,
            HttpServletRequest httpRequest) {
        requireAdmin(principal);
        AuditActor actor = resolveActor(principal, httpRequest);
        Account suspended = suspendAccount.run(accountId, actor);
        return ResponseEntity.ok(Map.of("accountId", suspended.id(), "status", suspended.status().name()));
    }

    @PostMapping("/accounts/{accountId}/reactivate")
    public ResponseEntity<?> reactivateAccount(
            @AuthenticationPrincipal ApiTokenPrincipal principal,
            @PathVariable UUID accountId,
            HttpServletRequest httpRequest) {
        requireAdmin(principal);
        AuditActor actor = resolveActor(principal, httpRequest);
        Account reactivated = reactivateAccount.run(accountId, actor);
        return ResponseEntity.ok(Map.of("accountId", reactivated.id(), "status", reactivated.status().name()));
    }

    private AuditActor resolveActor(ApiTokenPrincipal principal, HttpServletRequest request) {
        if (principal == null) {
            throw new PermissionDenied("commercial.api", MemberRole.VIEWER, "API Token authentication required");
        }
        return AuditActor.apiToken(principal.accountId(), principal.tokenId(), principal.tokenName(), request);
    }

    private Membership resolveCallerMembership(ApiTokenPrincipal principal, UUID serviceId, UUID orgId) {
        if (principal.role() == PlatformRole.ADMIN) {
            return new Membership(orgId, principal.accountId(), MemberRole.OWNER);
        }
        return memberships.forService(principal.accountId(), serviceId);
    }

    private void requireScopeOrAdmin(ApiTokenPrincipal principal, ApiScope scope) {
        if (principal == null) {
            throw new PermissionDenied("commercial.api", MemberRole.VIEWER, "API Token authentication required");
        }
        if (principal.role() == PlatformRole.ADMIN || principal.hasScope(scope)) {
            return;
        }
        throw new PermissionDenied(scope.wireName(), MemberRole.VIEWER, "Scope " + scope.wireName() + " or admin role");
    }

    private void requireAdmin(ApiTokenPrincipal principal) {
        if (principal == null || principal.role() != PlatformRole.ADMIN) {
            throw new PermissionDenied("admin.operation", MemberRole.VIEWER, "Platform administrator privileges");
        }
    }

    // -------------------------------------------------------------------------
    // Domain-exception → clean JSON responses for API callers
    // -------------------------------------------------------------------------

    @ExceptionHandler(RequestRejected.class)
    public ResponseEntity<?> onRequestRejected(RequestRejected ex) {
        Map<String, String> body = ex.field() != null
                ? Map.of("message", ex.getMessage(), "field", ex.field())
                : Map.of("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(QuotaExceeded.class)
    public ResponseEntity<?> onQuotaExceeded(QuotaExceeded ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> onIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<?> onNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", ex.getMessage()));
    }
}
