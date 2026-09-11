package lhqm.furimeo.wisper.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The read side of what a node reports, and the three comparisons every screen and the
 * placement filter make against it (schema.md §2).
 */
class NodeStatusTest {

    private final UUID nodeId = UUID.randomUUID();

    @Test
    void convergedIsAppliedEqualToPublished() {
        NodeStatus status = NodeStatusFixture.connected(nodeId, "198.51.100.7", 47L);

        assertThat(status.hasConverged(47L)).isTrue();
        assertThat(status.hasConverged(48L)).isFalse();
        assertThat(status.isAheadOf(47L)).isFalse();
    }

    @Test
    void aheadOfWhatThePanelPublishedIsTheImpossibleCase() {
        NodeStatus status = NodeStatusFixture.connected(nodeId, "198.51.100.7", 48L);

        // Two panels driving one node. Nothing else can produce it, and the answer is to
        // stop being one of the two.
        assertThat(status.isAheadOf(47L)).isTrue();
    }

    @Test
    void aNodeThatHasNeverReportedIsBehindEverything() {
        NodeStatus status = NodeStatusFixture.of(nodeId, NodeConnectionState.DISCONNECTED, null,
                NodeStatus.NEVER_REPORTED, null);

        assertThat(status.hasConverged(0L)).isFalse();
        assertThat(status.isAheadOf(0L)).isFalse();
        assertThat(status.isConnected()).isFalse();
    }

    @Test
    void degradedIsStillConnected() {
        NodeStatus status = NodeStatusFixture.of(nodeId, NodeConnectionState.DEGRADED,
                "198.51.100.7", 47L, Instant.now());

        // Something is wrong and nothing has been destroyed because of it. The stream is
        // open and the panel keeps talking to it (AGENTS.md §4.5).
        assertThat(status.isConnected()).isTrue();
    }

    @Test
    void headroomIsCapacityMinusWhatIsInUse() {
        NodeStatus status = NodeStatusFixture.connected(nodeId, "198.51.100.7", 47L);

        assertThat(status.freeMillicores()).isEqualTo(4000L - 500L);
        assertThat(status.freeMemoryBytes()).isEqualTo(8_589_934_592L - 2_147_483_648L);
        assertThat(status.freeDiskBytes()).isEqualTo(214_748_364_800L - 21_474_836_480L);
    }

    @Test
    void aNodeThatHasReportedNoCapacityHasNoHeadroomRatherThanInfiniteHeadroom() {
        NodeStatus blank = new NodeStatus(nodeId, NodeConnectionState.DISCONNECTED, null, null,
                null, null, null, null, NodeStatus.NEVER_REPORTED, null, null, null, null, null,
                null, null, null, null, 0, 0, null, null, null, null, null, null, null, null,
                null, null, Instant.now(), 1L);

        // Reading a missing number as "unlimited" is how a scheduler fills a machine it
        // knows nothing about.
        assertThat(blank.freeMillicores()).isZero();
        assertThat(blank.freeMemoryBytes()).isZero();
        assertThat(blank.freeDiskBytes()).isZero();
    }
}
