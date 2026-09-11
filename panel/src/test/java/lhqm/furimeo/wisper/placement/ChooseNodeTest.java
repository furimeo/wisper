package lhqm.furimeo.wisper.placement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import lhqm.furimeo.wisper.service.DesiredState;
import lhqm.furimeo.wisper.service.Service;
import lhqm.furimeo.wisper.service.ServiceRepository;
import lhqm.furimeo.wisper.service.Volume;
import lhqm.furimeo.wisper.service.VolumeRepository;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The published answer to "where does this run", which every start and every deployment
 * asks and which must give the same answer every time.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChooseNodeTest {

    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID NODE_A = UUID.randomUUID();
    private static final UUID NODE_B = UUID.randomUUID();

    @Mock
    private ServiceRepository services;

    @Mock
    private VolumeRepository volumes;

    @Mock
    private PlacementRepository placements;

    @Mock
    private ReserveCapacity reserve;

    @Mock
    private PlaceService placeService;

    @InjectMocks
    private ChooseNode chooseNode;

    private final Service service = PlacementFixture.app(SERVICE, PROJECT, DesiredState.RUNNING);

    @Test
    void anUnknownServiceIsNotFound() {
        given(services.findById(SERVICE)).willReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> chooseNode.forService(SERVICE));
        verifyNoInteractions(reserve, placeService);
    }

    @Test
    void anExistingBindingIsReturnedWithoutRescheduling() {
        given(services.findById(SERVICE)).willReturn(Optional.of(service));
        given(placements.findLiveFor(SERVICE)).willReturn(List.of(
                PlacementFixture.placement(SERVICE, NODE_A, PlacementState.ACTIVE, false)));

        assertThat(chooseNode.forService(SERVICE)).isEqualTo(NODE_A);
        verifyNoInteractions(reserve, placeService);
    }

    @Test
    void aServiceWithNothingHoldingItIsPlacedOnTheChosenNode() {
        given(services.findById(SERVICE)).willReturn(Optional.of(service));
        given(placements.findLiveFor(SERVICE)).willReturn(List.of());
        given(volumes.findByServiceIdOrderByName(SERVICE)).willReturn(List.of());
        given(reserve.bestFit(eq(service), any(), any())).willReturn(NODE_B);

        assertThat(chooseNode.forService(SERVICE)).isEqualTo(NODE_B);
        verify(placeService).onNode(eq(SERVICE), eq(NODE_B), eq(false), anyString());
    }

    @Test
    void storageMakesTheNewBindingPinned() {
        Volume data = Volume.of(UUID.randomUUID(), SERVICE, "data", "/data",
                4 * PlacementFixture.GIGABYTE, false, true);
        given(services.findById(SERVICE)).willReturn(Optional.of(service));
        given(placements.findLiveFor(SERVICE)).willReturn(List.of());
        given(volumes.findByServiceIdOrderByName(SERVICE)).willReturn(List.of(data));
        given(reserve.bestFit(eq(service), any(), any())).willReturn(NODE_B);

        chooseNode.forService(SERVICE);

        ArgumentCaptor<Boolean> pinned = ArgumentCaptor.forClass(Boolean.class);
        verify(placeService).onNode(eq(SERVICE), eq(NODE_B), pinned.capture(), anyString());
        assertThat(pinned.getValue()).isTrue();
    }

    @Test
    void aVolumeIsCountedAgainstTheDiskTheNewNodeHasToHave() {
        Volume data = Volume.of(UUID.randomUUID(), SERVICE, "data", "/data",
                4 * PlacementFixture.GIGABYTE, false, true);
        given(services.findById(SERVICE)).willReturn(Optional.of(service));
        given(placements.findLiveFor(SERVICE)).willReturn(List.of());
        given(volumes.findByServiceIdOrderByName(SERVICE)).willReturn(List.of(data));
        given(reserve.bestFit(eq(service), any(), any())).willReturn(NODE_B);

        chooseNode.forService(SERVICE);

        ArgumentCaptor<ResourceDemand> demand = ArgumentCaptor.forClass(ResourceDemand.class);
        verify(reserve).bestFit(eq(service), demand.capture(), any());
        assertThat(demand.getValue().diskBytes())
                .isEqualTo(service.diskBytes() + 4 * PlacementFixture.GIGABYTE);
    }

    @Test
    void theMachineAServiceIsBeingDrainedOffIsNotOfferedBackToIt() {
        given(services.findById(SERVICE)).willReturn(Optional.of(service));
        given(placements.findLiveFor(SERVICE)).willReturn(List.of(
                PlacementFixture.placement(SERVICE, NODE_A, PlacementState.DRAINING, false)));
        given(volumes.findByServiceIdOrderByName(SERVICE)).willReturn(List.of());
        given(reserve.bestFit(eq(service), any(), any())).willReturn(NODE_B);

        assertThat(chooseNode.forService(SERVICE)).isEqualTo(NODE_B);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> excluded = ArgumentCaptor.forClass(List.class);
        verify(reserve).bestFit(eq(service), any(), excluded.capture());
        assertThat(excluded.getValue()).containsExactly(NODE_A);
    }

    @Test
    void nothingIsPlacedWhenTheFleetRefuses() {
        given(services.findById(SERVICE)).willReturn(Optional.of(service));
        given(placements.findLiveFor(SERVICE)).willReturn(List.of());
        given(volumes.findByServiceIdOrderByName(SERVICE)).willReturn(List.of());
        given(reserve.bestFit(eq(service), any(), any()))
                .willThrow(NoNodeFits.noCandidates("Api", ResourceDemand.NONE, List.of()));

        assertThatExceptionOfType(NoNodeFits.class)
                .isThrownBy(() -> chooseNode.forService(SERVICE));
        verify(placeService, never()).onNode(any(), any(), anyBoolean(), anyString());
    }
}
