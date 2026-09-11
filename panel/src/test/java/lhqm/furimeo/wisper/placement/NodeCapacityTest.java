package lhqm.furimeo.wisper.placement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The headroom arithmetic, which is the one piece of the scheduler that decides whether a
 * node is allowed to die.
 */
class NodeCapacityTest {

    private static final UUID NODE = UUID.randomUUID();

    @Test
    void headroomIsSubtractedBeforeAnythingIsHandedOut() {
        NodeCapacity node = NodeCapacity.of(NODE, "node-a",
                1_000L, 800L, 1_000L, 0L, 1_000L, 0L, 15, 15, 20);

        assertThat(node.cpuMillicoresReserved()).isEqualTo(150L);
        assertThat(node.usableCpuMillicores()).isEqualTo(850L);
        // 200 millicores are physically free and only 50 of them are on offer.
        assertThat(node.freeCpuMillicores()).isEqualTo(50L);
        assertThat(node.diskBytesReserved()).isEqualTo(200L);
    }

    @Test
    void aDemandThatFitsTheMachineButNotTheHeadroomIsRefused() {
        NodeCapacity guarded = NodeCapacity.of(NODE, "node-a",
                1_000L, 800L, 1_000L, 0L, 1_000L, 0L, 15, 15, 20);
        NodeCapacity unguarded = NodeCapacity.of(NODE, "node-a",
                1_000L, 800L, 1_000L, 0L, 1_000L, 0L, 0, 0, 0);
        ResourceDemand demand = new ResourceDemand(100L, 1L, 1L);

        assertThat(guarded.fits(demand)).isFalse();
        assertThat(unguarded.fits(demand)).isTrue();
    }

    @Test
    void aNodeThatHasNotReportedItsSizeTakesNothing() {
        NodeCapacity unreported = NodeCapacity.of(NODE, "node-new",
                0L, 0L, 0L, 0L, 0L, 0L, 15, 15, 20);

        assertThat(unreported.isSized()).isFalse();
        assertThat(unreported.fits(ResourceDemand.NONE)).isFalse();
        assertThat(unreported.shortfallAgainst(ResourceDemand.NONE))
                .contains("has not reported its capacity");
    }

    @Test
    void everyDimensionCanBeTheOneThatRefuses() {
        NodeCapacity node = NodeCapacity.of(NODE, "node-a",
                1_000L, 0L, 1_000L, 0L, 1_000L, 0L, 0, 0, 0);

        assertThat(node.fits(new ResourceDemand(2_000L, 1L, 1L))).isFalse();
        assertThat(node.shortfallAgainst(new ResourceDemand(2_000L, 1L, 1L)))
                .contains("millicores free");
        assertThat(node.fits(new ResourceDemand(1L, 2_000L, 1L))).isFalse();
        assertThat(node.shortfallAgainst(new ResourceDemand(1L, 2_000L, 1L)))
                .contains("of memory needed");
        assertThat(node.fits(new ResourceDemand(1L, 1L, 2_000L))).isFalse();
        assertThat(node.shortfallAgainst(new ResourceDemand(1L, 1L, 2_000L)))
                .contains("of disk needed");
    }

    @Test
    void theFullerNodeIsUnderMorePressureAndIsThereforeTheBetterFit() {
        NodeCapacity nearlyFull = NodeCapacity.of(NODE, "full",
                1_000L, 700L, 1_000L, 700L, 1_000L, 700L, 0, 0, 0);
        NodeCapacity empty = NodeCapacity.of(UUID.randomUUID(), "empty",
                1_000L, 0L, 1_000L, 0L, 1_000L, 0L, 0, 0, 0);
        ResourceDemand demand = new ResourceDemand(100L, 100L, 100L);

        assertThat(nearlyFull.pressureWith(demand)).isGreaterThan(empty.pressureWith(demand));
    }
}
