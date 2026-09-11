package lhqm.furimeo.wisper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import lhqm.furimeo.wisper.org.QuotaGuard;
import lhqm.furimeo.wisper.org.QuotaResource;
import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * Attaching a disk, which an app can have and a site cannot.
 *
 * <p>The site case is the divergence worth pinning down: {@code Mount} in the spec names a
 * path inside a workload, and a static site has no workload. Storing a volume for one
 * would produce a row nothing in the spec could ever reference.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateVolumeTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final long ONE_GIB = 1_073_741_824L;

    @Mock
    private ServiceRepository services;

    @Mock
    private VolumeRepository volumes;

    @Mock
    private QuotaGuard quotas;

    @Mock
    private PublishNodeSpec specs;

    @Mock
    private AuditTrail audit;

    @InjectMocks
    private CreateVolume createVolume;

    private final AuditActor actor = AuditActor.system("test");

    private final Membership developer =
            new Membership(ORGANIZATION, UUID.randomUUID(), MemberRole.DEVELOPER);

    private final Membership viewer =
            new Membership(ORGANIZATION, UUID.randomUUID(), MemberRole.VIEWER);

    @BeforeEach
    void anAppWithNoVolumesYet() {
        given(services.findOwnedBy(SERVICE, ORGANIZATION))
                .willReturn(Optional.of(ServiceFixture.app(SERVICE, PROJECT)));
        given(volumes.existsByServiceIdAndName(any(), any())).willReturn(false);
        given(volumes.existsByServiceIdAndMountPath(any(), any())).willReturn(false);
        given(volumes.save(any())).willAnswer(call -> call.getArgument(0));
    }

    @Test
    void anAppGetsItsDiskAndTheNodeIsTold() {
        Volume created = createVolume.create(actor, developer, SERVICE, "Data", "/data/",
                ONE_GIB, false, true);

        assertThat(created.name()).isEqualTo("data");
        assertThat(created.mountPath()).isEqualTo("/data");
        assertThat(created.sizeBytes()).isEqualTo(ONE_GIB);
        assertThat(created.usedBytes()).isNull();
        assertThat(created.percentUsed()).isEqualTo(-1);
        verify(specs).forService(eq(SERVICE), anyString());
        verify(audit).record(any());
    }

    @Test
    void aStaticSiteCannotHaveOneBecauseThereIsNoContainerToMountItIn() {
        given(services.findOwnedBy(SERVICE, ORGANIZATION))
                .willReturn(Optional.of(ServiceFixture.site(SERVICE, PROJECT)));

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> createVolume.create(actor, developer, SERVICE, "data", "/data",
                        ONE_GIB, false, true))
                .withMessageContaining("no container to mount");
        verify(volumes, never()).save(any());
        verify(specs, never()).forService(any(), anyString());
    }

    @Test
    void theSpacePromisedIsChargedNotTheSpaceUsed() {
        createVolume.create(actor, developer, SERVICE, "data", "/data", ONE_GIB, false, true);

        verify(quotas).require(ORGANIZATION, QuotaResource.VOLUME_BYTES, ONE_GIB);
    }

    @Test
    void anEmptyNameFallsBackToSomethingUsableRatherThanFailing() {
        assertThat(createVolume.create(actor, developer, SERVICE, "  ", "/data", ONE_GIB, false,
                true).name()).isEqualTo("data");
    }

    @Test
    void aNameAlreadyInUseIsRefused() {
        given(volumes.existsByServiceIdAndName(SERVICE, "data")).willReturn(true);

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> createVolume.create(actor, developer, SERVICE, "data", "/data",
                        ONE_GIB, false, true))
                .matches(rejected -> "name".equals(rejected.field()));
    }

    @Test
    void aMountPointAlreadyTakenIsRefusedBecauseTheSpecWouldBeAmbiguous() {
        given(volumes.existsByServiceIdAndMountPath(SERVICE, "/data")).willReturn(true);

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> createVolume.create(actor, developer, SERVICE, "other", "/data",
                        ONE_GIB, false, true))
                .matches(rejected -> "mountPath".equals(rejected.field()));
    }

    @Test
    void aPathThatCouldClimbOutOfTheContainerIsRefusedBeforeAnyQuotaIsSpent() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> createVolume.create(actor, developer, SERVICE, "data",
                        "/data/../../etc", ONE_GIB, false, true));
        verify(quotas, never()).require(any(), any(), anyLong());
        verify(volumes, never()).save(any());
    }

    @Test
    void aSizeOutsideWhatANodeCanEnforceIsRefused() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> createVolume.create(actor, developer, SERVICE, "data", "/data",
                        1024, false, true))
                .matches(rejected -> "sizeMebibytes".equals(rejected.field()));
    }

    @Test
    void aViewerIsRefusedBeforeTheServiceIsLoaded() {
        assertThatExceptionOfType(PermissionDenied.class)
                .isThrownBy(() -> createVolume.create(actor, viewer, SERVICE, "data", "/data",
                        ONE_GIB, false, true));
        verify(services, never()).findOwnedBy(any(), any());
    }
}
