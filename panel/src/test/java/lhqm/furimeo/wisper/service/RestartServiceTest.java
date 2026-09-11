package lhqm.furimeo.wisper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.MemberRole;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Restarting, which in a desired-state system is two generations rather than a command -
 * and which a static site cannot do at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RestartServiceTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();

    @Mock
    private ServiceRepository services;

    @Mock
    private PublishNodeSpec specs;

    @Mock
    private AuditTrail audit;

    @InjectMocks
    private RestartService restartService;

    private final AuditActor actor = AuditActor.system("test");

    private final Membership developer =
            new Membership(ORGANIZATION, UUID.randomUUID(), MemberRole.DEVELOPER);

    private final Membership viewer =
            new Membership(ORGANIZATION, UUID.randomUUID(), MemberRole.VIEWER);

    @BeforeEach
    void savesEcho() {
        given(services.save(any())).willAnswer(call -> call.getArgument(0));
    }

    @Test
    void aRunningAppIsPublishedStoppedAndThenRunningAgain() {
        given(services.findOwnedBy(SERVICE, ORGANIZATION))
                .willReturn(Optional.of(ServiceFixture.running(SERVICE, PROJECT,
                        ServiceKind.APP)));

        Service result = restartService.restart(actor, developer, SERVICE);

        ArgumentCaptor<Service> saved = ArgumentCaptor.forClass(Service.class);
        verify(services, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).desiredState()).isEqualTo(DesiredState.STOPPED);
        assertThat(saved.getAllValues().get(1).desiredState()).isEqualTo(DesiredState.RUNNING);

        // Two publishes, because two generations are what "stop then start" means when the
        // panel may not tell a node to do anything imperatively.
        verify(specs, times(2)).forService(eq(SERVICE), anyString());
        assertThat(result.desiredState()).isEqualTo(DesiredState.RUNNING);
    }

    @Test
    void aStaticSiteIsRefusedBecauseItHasNoProcess() {
        given(services.findOwnedBy(SERVICE, ORGANIZATION))
                .willReturn(Optional.of(ServiceFixture.running(SERVICE, PROJECT,
                        ServiceKind.SITE)));

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> restartService.restart(actor, developer, SERVICE))
                .withMessageContaining("no process to restart");
        verify(services, never()).save(any());
        verify(specs, never()).forService(any(), anyString());
    }

    @Test
    void aStoppedServiceIsRefusedRatherThanQuietlyStarted() {
        given(services.findOwnedBy(SERVICE, ORGANIZATION))
                .willReturn(Optional.of(ServiceFixture.app(SERVICE, PROJECT)));

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> restartService.restart(actor, developer, SERVICE))
                .withMessageContaining("Start it instead");
        verify(services, never()).save(any());
    }

    @Test
    void anArchivedServiceIsRefused() {
        Service archived = ServiceFixture.archived(SERVICE, PROJECT, ServiceKind.APP)
                .desiring(DesiredState.RUNNING);
        given(services.findOwnedBy(SERVICE, ORGANIZATION)).willReturn(Optional.of(archived));

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> restartService.restart(actor, developer, SERVICE))
                .withMessageContaining("archived");
    }

    @Test
    void aViewerIsRefusedBeforeAnythingIsLoaded() {
        assertThatExceptionOfType(PermissionDenied.class)
                .isThrownBy(() -> restartService.restart(actor, viewer, SERVICE));
        verify(services, never()).findOwnedBy(any(), any());
    }

    @Test
    void aServiceInAnotherTenantIsNotFound() {
        given(services.findOwnedBy(SERVICE, ORGANIZATION)).willReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> restartService.restart(actor, developer, SERVICE));
    }

    @Test
    void theTrailRecordsIt() {
        given(services.findOwnedBy(SERVICE, ORGANIZATION))
                .willReturn(Optional.of(ServiceFixture.running(SERVICE, PROJECT,
                        ServiceKind.APP)));

        restartService.restart(actor, developer, SERVICE);

        verify(audit).record(any());
    }
}
