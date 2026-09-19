package lhqm.furimeo.wisper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.auth.Account;
import lhqm.furimeo.wisper.auth.AccountRepository;
import lhqm.furimeo.wisper.auth.AccountStatus;
import lhqm.furimeo.wisper.auth.PlatformRole;
import lhqm.furimeo.wisper.auth.RegisterUser;
import lhqm.furimeo.wisper.org.CreateOrganization;
import lhqm.furimeo.wisper.org.Organization;
import lhqm.furimeo.wisper.org.OrganizationRepository;
import lhqm.furimeo.wisper.org.OrganizationStatus;
import lhqm.furimeo.wisper.project.CreateProject;
import lhqm.furimeo.wisper.project.Project;
import lhqm.furimeo.wisper.project.ProjectRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FastProvisionServerTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID SERVICE_ID = UUID.randomUUID();

    @Mock
    private AccountRepository accounts;

    @Mock
    private RegisterUser registerUser;

    @Mock
    private OrganizationRepository organizations;

    @Mock
    private CreateOrganization createOrganization;

    @Mock
    private ProjectRepository projects;

    @Mock
    private CreateProject createProject;

    @Mock
    private CreateService createService;

    @Mock
    private CreateVolume createVolume;

    @Mock
    private SetEnvVar setEnvVar;

    @Mock
    private StartService startService;

    private FastProvisionServer fastProvisionServer;
    private final AuditActor actor = AuditActor.system("commercial-test");

    @BeforeEach
    void setUp() {
        fastProvisionServer = new FastProvisionServer(
                accounts, registerUser, organizations, createOrganization,
                projects, createProject, createService, createVolume,
                setEnvVar, startService);
    }

    @Test
    void provisionsNewCustomerEndToEndWithStorageAndStarts() {
        String email = "buyer@example.com";
        given(accounts.findByEmail(email)).willReturn(Optional.empty());

        Account newAccount = new Account(
                ACCOUNT_ID, email, "Buyer", "hash", Instant.now(), PlatformRole.CUSTOMER,
                AccountStatus.ACTIVE, "en", null, null, null, null, 0,
                null, Instant.now(), Instant.now(), null);
        given(registerUser.run(eq(email), eq("Buyer"), anyString(), eq(PlatformRole.CUSTOMER), any()))
                .willReturn(newAccount);

        Organization newOrg = Organization.opened(ORG_ID, "Buyer's Org", "org-buyer", UUID.randomUUID());
        given(organizations.findAcceptedFor(ACCOUNT_ID)).willReturn(List.of());
        given(organizations.existsBySlug(anyString())).willReturn(false);
        given(createOrganization.create(any(), eq(ACCOUNT_ID), anyString(), anyString(), any()))
                .willReturn(newOrg);

        Project newProject = new Project(
                PROJECT_ID, ORG_ID, "Default", "default", "Desc",
                null, Instant.now(), Instant.now(), 0L);
        given(projects.findByOrganizationIdAndSlug(ORG_ID, "default")).willReturn(Optional.empty());
        given(projects.findActiveInOrganization(ORG_ID)).willReturn(List.of());
        given(createProject.create(any(), any(), eq("Default"), eq("default"), anyString()))
                .willReturn(newProject);

        Service createdService = new Service(
                SERVICE_ID, PROJECT_ID, "discord-bot", "discord-bot", ServiceKind.APP,
                DesiredState.STOPPED, RuntimeIsolation.RUNSC, null,
                "python:3.12-slim", null, new String[]{"sleep", "infinity"}, new String[0],
                "/app", 8080, null, 30, RestartPolicy.ALWAYS,
                null, null, null, 5, null, null, null, false, "encSecret",
                1000L, 1073741824L, 5368709120L, 256, new String[0],
                null, Instant.now(), Instant.now(), 0L);
        given(createService.create(any(), any(), eq(PROJECT_ID), any())).willReturn(createdService);

        ProvisionServerRequest request = new ProvisionServerRequest(
                email, "Buyer", null, null, null, null, null,
                "discord-bot", null, ServiceKind.APP, "python:3.12-slim", null,
                "/app", 8080, 1000L, 1073741824L, true, "data", "/app",
                5368709120L, null, true);

        FastProvisionServer.ProvisionResult result = fastProvisionServer.provision(actor, request);

        assertThat(result.serviceId()).isEqualTo(SERVICE_ID);
        assertThat(result.serviceName()).isEqualTo("discord-bot");
        assertThat(result.status()).isEqualTo("RUNNING");
        assertThat(result.isNewAccount()).isTrue();
        assertThat(result.initialPassword()).isNotBlank().startsWith("wsp_");
        assertThat(result.urls()).containsKey("panel");
        assertThat(result.urls().get("panel")).isEqualTo("/services/" + SERVICE_ID);

        verify(registerUser).run(eq(email), eq("Buyer"), anyString(), eq(PlatformRole.CUSTOMER), any());
        verify(createOrganization).create(any(), eq(ACCOUNT_ID), anyString(), anyString(), any());
        verify(createProject).create(any(), any(), eq("Default"), eq("default"), anyString());
        verify(createService).create(any(), any(), eq(PROJECT_ID), any());
        verify(createVolume).create(any(), any(), eq(SERVICE_ID), eq("data"), eq("/app"), eq(5368709120L), eq(false), eq(true));
        verify(startService).start(any(), any(), eq(SERVICE_ID));
    }

    @Test
    void reusesExistingAccountAndOrgWithoutCreatingDuplicates() {
        String email = "vip@example.com";
        Account existingAccount = new Account(
                ACCOUNT_ID, email, "VIP", "hash", Instant.now(), PlatformRole.CUSTOMER,
                AccountStatus.ACTIVE, "en", null, null, null, null, 0,
                null, Instant.now(), Instant.now(), null);
        given(accounts.findByEmail(email)).willReturn(Optional.of(existingAccount));

        Organization existingOrg = Organization.opened(ORG_ID, "VIP Org", "vip-org", UUID.randomUUID());
        given(organizations.findAcceptedFor(ACCOUNT_ID)).willReturn(List.of(existingOrg));

        Project existingProject = new Project(
                PROJECT_ID, ORG_ID, "Default", "default", "Desc",
                null, Instant.now(), Instant.now(), 0L);
        given(projects.findByOrganizationIdAndSlug(ORG_ID, "default")).willReturn(Optional.of(existingProject));

        Service createdService = new Service(
                SERVICE_ID, PROJECT_ID, "api-server", "api-server", ServiceKind.APP,
                DesiredState.STOPPED, RuntimeIsolation.RUNSC, null,
                "node:22-alpine", null, new String[]{"node", "index.js"}, new String[0],
                "/app", 3000, null, 30, RestartPolicy.ALWAYS,
                null, null, null, 5, null, null, null, false, "encSecret",
                500L, 536870912L, 5368709120L, 256, new String[0],
                null, Instant.now(), Instant.now(), 0L);
        given(createService.create(any(), any(), eq(PROJECT_ID), any())).willReturn(createdService);

        ProvisionServerRequest request = new ProvisionServerRequest(
                email, null, null, null, null, null, null,
                "api-server", null, ServiceKind.APP, "node:22-alpine", "node index.js",
                "/app", 3000, 500L, 536870912L, false, null, null,
                null, null, false);

        FastProvisionServer.ProvisionResult result = fastProvisionServer.provision(actor, request);

        assertThat(result.serviceId()).isEqualTo(SERVICE_ID);
        assertThat(result.isNewAccount()).isFalse();
        assertThat(result.initialPassword()).isNull();
        assertThat(result.status()).isEqualTo("STOPPED");

        verify(registerUser, never()).run(any(), any(), any(), any(), any());
        verify(createOrganization, never()).create(any(), any(), any(), any(), any());
        verify(createProject, never()).create(any(), any(), any(), any(), any());
        verify(createVolume, never()).create(any(), any(), any(), any(), any(), anyLong(), anyBoolean(), anyBoolean());
        verify(startService, never()).start(any(), any(), any());
    }
}
