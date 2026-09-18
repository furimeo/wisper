package lhqm.furimeo.wisper.placement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import lhqm.furimeo.wisper.service.DesiredState;
import lhqm.furimeo.wisper.service.Service;

/**
 * Choosing a node: refusing when nothing fits, keeping the headroom back, and packing
 * rather than spreading.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReserveCapacityTest {

    private static final UUID PROJECT = UUID.randomUUID();

    /** Ordered, so a test can say "the lower id" without hunting for which one that is. */
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Mock
    private LoadNodeCapacity capacities;

    @InjectMocks
    private ReserveCapacity reserve;

    private final Service service = PlacementFixture.app(UUID.randomUUID(), PROJECT,
            DesiredState.RUNNING);

    private final ResourceDemand demand = ResourceDemand.of(service, List.of());

    @Test
    void anEmptyFleetIsRefusedWithTheReasonBeingTheFilterAndNotTheCapacity() {
        given(capacities.candidates(anyList())).willReturn(List.of());

        NoNodeFits refused = catchNoNodeFits(() -> reserve.bestFit(service, demand, List.of()));

        assertThat(refused.nodesConsidered()).isZero();
        assertThat(refused.getMessage()).contains("no enrolled, schedulable node");
    }

    @Test
    void aTaggedServiceWithNoMatchingNodeSaysWhichTagIsMissing() {
        Service tagged = PlacementFixture.app(UUID.randomUUID(), PROJECT, DesiredState.RUNNING,
                "region=eu", "ssd");
        given(capacities.candidates(List.of("region=eu", "ssd"))).willReturn(List.of());

        NoNodeFits refused = catchNoNodeFits(
                () -> reserve.bestFit(tagged, demand, List.of()));

        assertThat(refused.getMessage()).contains("region=eu, ssd");
        assertThat(refused.requiredTags()).containsExactly("region=eu", "ssd");
    }

    @Test
    void everyNodeBeingFullIsRefusedWithOneLinePerNode() {
        given(capacities.candidates(anyList())).willReturn(List.of(
                PlacementFixture.capacity(FIRST, "node-a", 1_000L, 990L, 0),
                PlacementFixture.capacity(SECOND, "node-b", 1_000L, 980L, 0)));

        NoNodeFits refused = catchNoNodeFits(() -> reserve.bestFit(service, demand, List.of()));

        assertThat(refused.nodesConsidered()).isEqualTo(2);
        assertThat(refused.getMessage()).contains("node-a").contains("node-b");
        assertThat(refused.demand()).isEqualTo(demand);
    }

    @Test
    void headroomIsWhatDecidesBetweenAcceptingAndRefusing() {
        // 1000 millicores with 400 committed, and the service wants 500. Physically there
        // are 600 free; with fifteen per cent kept back there are 450, and 450 is not 500.
        given(capacities.candidates(anyList())).willReturn(
                List.of(PlacementFixture.capacity(FIRST, "node-a", 1_000L, 400L, 15)));
        assertThatExceptionOfType(NoNodeFits.class)
                .isThrownBy(() -> reserve.bestFit(service, demand, List.of()));

        given(capacities.candidates(anyList())).willReturn(
                List.of(PlacementFixture.capacity(FIRST, "node-a", 1_000L, 400L, 0)));
        assertThat(reserve.bestFit(service, demand, List.of())).isEqualTo(FIRST);
    }

    @Test
    void theLeastLoadedNodeThatStillWorksWins() {
        given(capacities.candidates(anyList())).willReturn(List.of(
                PlacementFixture.capacity(FIRST, "roomy", 4_000L, 0L, 0),
                PlacementFixture.capacity(SECOND, "snug", 4_000L, 3_000L, 0)));

        assertThat(reserve.bestFit(service, demand, List.of())).isEqualTo(FIRST);
    }

    @Test
    void anExcludedNodeIsNotChosenEvenWhenItIsTheOnlyGoodFit() {
        given(capacities.candidates(anyList())).willReturn(List.of(
                PlacementFixture.capacity(FIRST, "roomy", 4_000L, 0L, 0),
                PlacementFixture.capacity(SECOND, "snug", 4_000L, 3_000L, 0)));

        assertThat(reserve.bestFit(service, demand, List.of(FIRST))).isEqualTo(SECOND);
    }

    @Test
    void twoIdenticalNodesResolveTheSameWayEveryTime() {
        given(capacities.candidates(anyList())).willReturn(List.of(
                PlacementFixture.capacity(SECOND, "node-b", 4_000L, 100L, 0),
                PlacementFixture.capacity(FIRST, "node-a", 4_000L, 100L, 0)));

        // Listed with the higher id first, so a scheduler that kept whatever arrived first
        // would answer SECOND and would answer differently on a replica.
        assertThat(reserve.bestFit(service, demand, List.of())).isEqualTo(FIRST);
        assertThat(reserve.bestFit(service, demand, List.of())).isEqualTo(FIRST);
    }

    @Test
    void aNamedTargetIsCheckedRatherThanQuietlySwappedForAnother() {
        given(capacities.candidates(anyList())).willReturn(List.of(
                PlacementFixture.capacity(FIRST, "roomy", 4_000L, 0L, 0),
                PlacementFixture.capacity(SECOND, "full", 1_000L, 995L, 0)));

        assertThatExceptionOfType(NoNodeFits.class)
                .isThrownBy(() -> reserve.requireRoomOn(SECOND, service, demand))
                .withMessageContaining("full");
        reserve.requireRoomOn(FIRST, service, demand);
    }

    @Test
    void volumesCountTowardsTheDiskADemandNeeds() {
        ResourceDemand withStorage = new ResourceDemand(service.cpuMillicores(),
                service.memoryBytes(), service.diskBytes() + 8 * PlacementFixture.GIGABYTE);

        assertThat(withStorage.diskBytes()).isGreaterThan(demand.diskBytes());
        assertThat(withStorage.describe()).contains("of disk");
    }

    private static NoNodeFits catchNoNodeFits(Runnable call) {
        try {
            call.run();
        } catch (NoNodeFits refused) {
            return refused;
        }
        throw new AssertionError("Expected the fleet to refuse this placement");
    }
}
