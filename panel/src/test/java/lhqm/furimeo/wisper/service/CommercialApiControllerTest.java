package lhqm.furimeo.wisper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import jakarta.servlet.http.HttpServletRequest;
import lhqm.furimeo.wisper.auth.AccountRepository;
import lhqm.furimeo.wisper.auth.ApiScope;
import lhqm.furimeo.wisper.auth.ApiTokenPrincipal;
import lhqm.furimeo.wisper.auth.PlatformRole;
import lhqm.furimeo.wisper.auth.ReactivateAccount;
import lhqm.furimeo.wisper.auth.SuspendAccount;
import lhqm.furimeo.wisper.org.MemberRole;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.ResolveMembership;
import lhqm.furimeo.wisper.project.ListProjects;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommercialApiControllerTest {

    private static final UUID SERVICE_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID TOKEN_ID = UUID.randomUUID();

    @Mock
    private FastProvisionServer fastProvisionServer;

    @Mock
    private ServiceRepository services;

    @Mock
    private VolumeRepository volumes;

    @Mock
    private LocateService locateService;

    @Mock
    private ListServicesInProject listServicesInProject;

    @Mock
    private ListProjects listProjects;

    @Mock
    private StartService startService;

    @Mock
    private StopService stopService;

    @Mock
    private RestartService restartService;

    @Mock
    private DeleteService deleteService;

    @Mock
    private ResolveMembership memberships;

    @Mock
    private AccountRepository accounts;

    @Mock
    private SuspendAccount suspendAccount;

    @Mock
    private ReactivateAccount reactivateAccount;

    private CommercialApiController controller;
    private HttpServletRequest httpRequest;

    private final ApiTokenPrincipal adminPrincipal = new ApiTokenPrincipal(
            TOKEN_ID, ACCOUNT_ID, null, "admin-billing-token", "admin@furimeo.com",
            PlatformRole.ADMIN, Set.of(ApiScope.SERVICES_READ, ApiScope.SERVICES_WRITE));

    private final ApiTokenPrincipal customerPrincipal = new ApiTokenPrincipal(
            TOKEN_ID, ACCOUNT_ID, null, "cust-token", "cust@example.com",
            PlatformRole.CUSTOMER, Set.of(ApiScope.SERVICES_READ));

    @BeforeEach
    void setUp() {
        httpRequest = mock(HttpServletRequest.class);
        given(httpRequest.getRemoteAddr()).willReturn("127.0.0.1");

        controller = new CommercialApiController(
                fastProvisionServer, services, volumes, locateService,
                listServicesInProject, listProjects, startService, stopService,
                restartService, deleteService, memberships, accounts,
                suspendAccount, reactivateAccount);
    }

    @Test
    void provisionInvokesFastProvisionerAndReturnsCreated() {
        ProvisionServerRequest req = new ProvisionServerRequest(
                "cust@example.com", "Cust", null, null, null, null, null,
                "bot", null, ServiceKind.APP, "python:3.12-slim", null,
                "/app", 8080, 500L, 268435456L, true, "data", "/app",
                5368709120L, null, true);

        FastProvisionServer.ProvisionResult mockResult = new FastProvisionServer.ProvisionResult(
                SERVICE_ID, "bot", "bot", ServiceKind.APP, "RUNNING",
                PROJECT_ID, ORG_ID, ACCOUNT_ID, "cust@example.com", true, "wsp_12345",
                500L, 268435456L, 5368709120L, "python:3.12-slim", 8080,
                Map.of("panel", "/services/" + SERVICE_ID));

        given(fastProvisionServer.provision(any(), eq(req))).willReturn(mockResult);

        ResponseEntity<?> response = controller.provision(adminPrincipal, req, httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(mockResult);
        verify(fastProvisionServer).provision(any(), eq(req));
    }

    @Test
    void getServiceReturnsDetailedHardwareAndOperationalMetrics() {
        Service service = new Service(
                SERVICE_ID, PROJECT_ID, "bot", "bot", ServiceKind.APP,
                DesiredState.RUNNING, RuntimeIsolation.RUNSC, null,
                "python:3.12-slim", null, new String[]{"python", "bot.py"}, new String[0],
                "/app", 8080, null, 30, RestartPolicy.ALWAYS,
                null, null, null, 5, null, null, null, false, "secret",
                1000L, 1073741824L, 5368709120L, 256, new String[0],
                null, Instant.now(), Instant.now(), 0L);
        given(services.findById(SERVICE_ID)).willReturn(Optional.of(service));

        ServiceLocation location = new ServiceLocation(
                SERVICE_ID, PROJECT_ID, ORG_ID, UUID.randomUUID(), "bot", "bot", ServiceKind.APP);
        given(locateService.byId(SERVICE_ID)).willReturn(location);

        ServiceSummary summary = new ServiceSummary(
                SERVICE_ID, PROJECT_ID, "bot", "bot", ServiceKind.APP,
                DesiredState.RUNNING, null, "python:3.12-slim", null,
                location.nodeId(), "RUNNING", "HEALTHY", Instant.now(), Instant.now());
        given(listServicesInProject.one(SERVICE_ID)).willReturn(Optional.of(summary));

        Volume volume = Volume.of(UUID.randomUUID(), SERVICE_ID, "data", "/app", 5368709120L, false, true);
        given(volumes.findByServiceIdOrderByName(SERVICE_ID)).willReturn(List.of(volume));

        ResponseEntity<?> response = controller.getService(adminPrincipal, SERVICE_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        CommercialServiceView view = (CommercialServiceView) response.getBody();
        assertThat(view).isNotNull();
        assertThat(view.status()).isEqualTo("RUNNING");
        assertThat(view.cpuMillicores()).isEqualTo(1000L);
        assertThat(view.memoryBytes()).isEqualTo(1073741824L);
        assertThat(view.diskBytes()).isEqualTo(5368709120L);
        assertThat(view.volumes()).hasSize(1);
        assertThat(view.urls().get("panel")).isEqualTo("/services/" + SERVICE_ID);
    }

    @Test
    void stopPausesServiceContainer() {
        ServiceLocation location = new ServiceLocation(
                SERVICE_ID, PROJECT_ID, ORG_ID, null, "bot", "bot", ServiceKind.APP);
        given(locateService.byId(SERVICE_ID)).willReturn(location);

        ResponseEntity<?> response = controller.stop(adminPrincipal, SERVICE_ID, httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(stopService).stop(any(), any(), eq(SERVICE_ID));
    }

    @Test
    void startResumesServiceContainer() {
        ServiceLocation location = new ServiceLocation(
                SERVICE_ID, PROJECT_ID, ORG_ID, null, "bot", "bot", ServiceKind.APP);
        given(locateService.byId(SERVICE_ID)).willReturn(location);

        ResponseEntity<?> response = controller.start(adminPrincipal, SERVICE_ID, httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(startService).start(any(), any(), eq(SERVICE_ID));
    }

    @Test
    void deletePermanentlyRemovesService() {
        ServiceLocation location = new ServiceLocation(
                SERVICE_ID, PROJECT_ID, ORG_ID, null, "bot", "bot", ServiceKind.APP);
        given(locateService.byId(SERVICE_ID)).willReturn(location);

        ResponseEntity<?> response = controller.delete(adminPrincipal, SERVICE_ID, httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(deleteService).delete(any(), any(), eq(SERVICE_ID));
    }

    @Test
    void customerTokenWithoutWriteScopeIsRefused() {
        ProvisionServerRequest req = new ProvisionServerRequest(
                "cust@example.com", "Cust", null, null, null, null, null,
                "bot", null, ServiceKind.APP, null, null,
                null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> controller.provision(customerPrincipal, req, httpRequest))
                .isInstanceOf(PermissionDenied.class);
    }
}
