package lhqm.furimeo.wisper.placement;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.service.LocateService;
import lhqm.furimeo.wisper.service.Service;
import lhqm.furimeo.wisper.service.ServiceKind;
import lhqm.furimeo.wisper.service.ServiceLocation;
import lhqm.furimeo.wisper.service.ServiceRepository;
import lhqm.furimeo.wisper.service.Volume;
import lhqm.furimeo.wisper.service.VolumeRepository;

/** Moving a service on purpose: what it refuses, and what it does when it agrees. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MigrateServiceTest {

    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID OLD_NODE = UUID.randomUUID();
    private static final UUID NEW_NODE = UUID.randomUUID();

    @Mock
    private ServiceRepository services;

    @Mock
    private VolumeRepository volumes;

    @Mock
    private LocateService locations;

    @Mock
    private PlacementRepository placements;

    @Mock
    private ReserveCapacity reserve;

    @Mock
    private PlaceService placeService;

    @Mock
    private PublishNodeSpec specs;

    @Mock
    private AuditTrail audit;

    @InjectMocks
    private MigrateService migrate;

    private final AuditActor actor = AuditActor.system("test");
    private final Service service = PlacementFixture.runningApp(UUID.randomUUID(), PROJECT);

    @BeforeEach
    void aServiceRunningOnTheOldNode() {
        given(services.findById(service.id())).willReturn(Optional.of(service));
        given(locations.byId(service.id())).willReturn(new ServiceLocation(service.id(), PROJECT,
                ORGANIZATION, OLD_NODE, "api", "Api", ServiceKind.APP));
        given(placements.findLiveFor(service.id())).willReturn(List.of(
                PlacementFixture.placement(service.id(), OLD_NODE, PlacementState.ACTIVE, false)));
        given(volumes.findByServiceIdOrderByName(service.id())).willReturn(List.of());
    }

    @Test
    void movingToTheMachineItIsAlreadyOnIsRefused() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> migrate.toNode(actor, service.id(), OLD_NODE, false, "test"))
                .withMessageContaining("already on that node");
        verify(placeService, never()).onNode(any(), any(), anyBoolean(), anyString());
    }

    @Test
    void aServiceThatIsNotPlacedHasNothingToMove() {
        given(placements.findLiveFor(service.id())).willReturn(List.of());

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> migrate.toNode(actor, service.id(), NEW_NODE, false, "test"))
                .withMessageContaining("Start it instead");
    }

    @Test
    void aSecondMoveIsRefusedWhileTheFirstIsStillDraining() {
        given(placements.findLiveFor(service.id())).willReturn(List.of(
                PlacementFixture.placement(service.id(), NEW_NODE, PlacementState.ACTIVE, false),
                PlacementFixture.placement(service.id(), OLD_NODE, PlacementState.DRAINING,
                        false)));

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> migrate.toNode(actor, service.id(), UUID.randomUUID(), false,
                        "test"))
                .withMessageContaining("already being moved");
    }

    @Test
    void aServiceWithStorageIsRefusedUntilTheCallerAcceptsThatTheDataStaysBehind() {
        givenTheServiceHasAVolume();

        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> migrate.toNode(actor, service.id(), NEW_NODE, false, "test"))
                .withMessageContaining("restored from a snapshot");
        verify(placeService, never()).onNode(any(), any(), anyBoolean(), anyString());
    }

    @Test
    void withThatAcceptedTheMoveGoesAheadAndTheNewBindingStaysPinned() {
        givenTheServiceHasAVolume();

        migrate.toNode(actor, service.id(), NEW_NODE, true, "moving to bigger disk");

        verify(placeService).onNode(eq(service.id()), eq(NEW_NODE), eq(true), anyString());
    }

    @Test
    void theOldBindingDrainsTheNewOneIsPlacedAndBothMachinesAreRepublished() {
        migrate.toNode(actor, service.id(), NEW_NODE, false, "rebalancing");

        verify(placements).refreshPinningFor(eq(service.id()), any(Instant.class));
        verify(placements).markDraining(any(), anyString(), any(Instant.class));
        verify(placeService).onNode(eq(service.id()), eq(NEW_NODE), eq(false), anyString());
        verify(specs).toNode(eq(OLD_NODE), anyString());
        verify(specs).toNode(eq(NEW_NODE), anyString());
        verify(audit).record(any());
    }

    @Test
    void aTargetWithNoRoomIsRefusedRatherThanSwappedForAnotherMachine() {
        org.mockito.BDDMockito.willThrow(
                        NoNodeFits.allFull("Api", ResourceDemand.NONE, List.of(), List.of("full")))
                .given(reserve).requireRoomOn(eq(NEW_NODE), any(), any());

        assertThatExceptionOfType(NoNodeFits.class)
                .isThrownBy(() -> migrate.toNode(actor, service.id(), NEW_NODE, false, "test"));
        verify(placements, never()).markDraining(any(), anyString(), any(Instant.class));
    }

    @Test
    void aMoveWithNoTargetNamedIsRefusedBeforeAnythingIsRead() {
        assertThatExceptionOfType(RequestRejected.class)
                .isThrownBy(() -> migrate.toNode(actor, service.id(), null, false, "test"))
                .withMessageContaining("Choose a node");
    }

    private void givenTheServiceHasAVolume() {
        given(placements.findLiveFor(service.id())).willReturn(List.of(
                PlacementFixture.placement(service.id(), OLD_NODE, PlacementState.ACTIVE, true)));
        given(volumes.findByServiceIdOrderByName(service.id())).willReturn(List.of(
                Volume.of(UUID.randomUUID(), service.id(), "data", "/data",
                        PlacementFixture.GIGABYTE, false, true)));
    }
}
