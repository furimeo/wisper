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
import lhqm.furimeo.wisper.node.NodeRepository;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.service.Service;
import lhqm.furimeo.wisper.service.ServiceRepository;
import lhqm.furimeo.wisper.service.Volume;
import lhqm.furimeo.wisper.service.VolumeRepository;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Draining: what moves, what is left for a person to decide about, and what a survey is
 * allowed to touch (nothing).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DrainNodePlacementsTest {

    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID OLD_NODE = UUID.randomUUID();
    private static final UUID NEW_NODE = UUID.randomUUID();

    @Mock
    private NodeRepository nodes;

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

    @Mock
    private PublishNodeSpec specs;

    @Mock
    private AuditTrail audit;

    @InjectMocks
    private DrainNodePlacements drain;

    private final AuditActor actor = AuditActor.system("test");
    private final Service stateless = PlacementFixture.runningApp(UUID.randomUUID(), PROJECT);
    private final Service stateful = PlacementFixture.runningApp(UUID.randomUUID(), PROJECT);

    @BeforeEach
    void aNodeWithOneStatelessAndOneStatefulService() {
        given(nodes.findById(OLD_NODE))
                .willReturn(Optional.of(PlacementFixture.drainingNode(OLD_NODE, "node-old")));
        given(placements.findLiveOn(OLD_NODE)).willReturn(List.of(
                PlacementFixture.placement(stateless.id(), OLD_NODE, PlacementState.ACTIVE, false),
                PlacementFixture.placement(stateful.id(), OLD_NODE, PlacementState.ACTIVE, true)));
        given(services.findById(stateless.id())).willReturn(Optional.of(stateless));
        given(services.findById(stateful.id())).willReturn(Optional.of(stateful));
        given(volumes.findByServiceIdOrderByName(stateless.id())).willReturn(List.of());
        given(volumes.findByServiceIdOrderByName(stateful.id())).willReturn(List.of(
                Volume.of(UUID.randomUUID(), stateful.id(), "data", "/data",
                        PlacementFixture.GIGABYTE, false, true)));
        given(reserve.bestFit(any(), any(), any())).willReturn(NEW_NODE);
    }

    @Test
    void anUnknownNodeIsNotFound() {
        given(nodes.findById(OLD_NODE)).willReturn(Optional.empty());

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> drain.forNode(actor, OLD_NODE, true, "test"));
    }

    @Test
    void aStatelessServiceMovesAndBothMachinesAreRepublished() {
        DrainOutcome outcome = drain.forNode(actor, OLD_NODE, true, "test");

        assertThat(outcome.evacuated()).containsExactly(stateless.id());
        verify(placeService).onNode(eq(stateless.id()), eq(NEW_NODE), eq(false), anyString());
        verify(specs).toNode(eq(NEW_NODE), anyString());
        verify(specs).toNode(eq(OLD_NODE), anyString());
    }

    @Test
    void aServiceWithStorageIsListedAndNeverMoved() {
        DrainOutcome outcome = drain.forNode(actor, OLD_NODE, true, "test");

        assertThat(outcome.pinned()).containsExactly(stateful.id());
        verify(placeService, never()).onNode(eq(stateful.id()), any(), anyBoolean(), anyString());
        assertThat(outcome.remaining()).isEqualTo(1);
        assertThat(outcome.describe()).contains("pinned by storage");
    }

    @Test
    void aSurveyAnswersTheSameQuestionAndChangesNothing() {
        DrainOutcome outcome = drain.forNode(actor, OLD_NODE, false, "test");

        assertThat(outcome.surveyOnly()).isTrue();
        assertThat(outcome.evacuated()).containsExactly(stateless.id());
        assertThat(outcome.pinned()).containsExactly(stateful.id());
        verify(placements, never()).markDraining(any(), anyString(), any(Instant.class));
        verify(placements, never()).refreshPinningOn(any(), any(Instant.class));
        verify(placeService, never()).onNode(any(), any(), anyBoolean(), anyString());
        verify(specs, never()).toNode(any(), anyString());
    }

    @Test
    void aServiceWithNowhereToGoIsStrandedRatherThanAbandoningTheWholeDrain() {
        given(reserve.bestFit(any(), any(), any()))
                .willThrow(NoNodeFits.noCandidates("Api", ResourceDemand.NONE, List.of()));

        DrainOutcome outcome = drain.forNode(actor, OLD_NODE, true, "test");

        assertThat(outcome.stranded()).containsExactly(stateless.id());
        assertThat(outcome.complete()).isFalse();
        // The pinned one is still reported, so the operator gets the whole picture.
        assertThat(outcome.pinned()).containsExactly(stateful.id());
    }

    @Test
    void aNodeHoldingOnlyPinnedServicesReportsCompleteBecauseNothingIsEvacuable() {
        given(placements.findLiveOn(OLD_NODE)).willReturn(List.of(
                PlacementFixture.placement(stateful.id(), OLD_NODE, PlacementState.ACTIVE, true)));

        DrainOutcome outcome = drain.forNode(actor, OLD_NODE, true, "test");

        assertThat(outcome.complete()).isTrue();
        assertThat(outcome.remaining()).isEqualTo(1);
    }

    @Test
    void aServiceBeingDeletedUnderneathTheDrainIsSteppedOver() {
        given(services.findById(stateless.id())).willReturn(Optional.empty());

        DrainOutcome outcome = drain.forNode(actor, OLD_NODE, true, "test");

        assertThat(outcome.evacuated()).isEmpty();
        assertThat(outcome.stranded()).isEmpty();
    }
}
