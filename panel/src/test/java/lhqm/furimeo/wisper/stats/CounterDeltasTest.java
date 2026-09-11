package lhqm.furimeo.wisper.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Cumulative counters in, per-window amounts out.
 *
 * <p>The three cases here are the three ways a network chart goes wrong: a first reading
 * drawn as a spike the height of everything the container has ever transferred, a counter
 * reset drawn as a negative that the CHECK constraint then rejects, and an ordinary
 * difference that is simply subtracted the wrong way round.
 */
class CounterDeltasTest {

    private static final UUID NODE = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-03-10T10:00:00Z");

    private final CounterDeltas deltas = new CounterDeltas();

    @Test
    void theFirstReadingForASubjectIsABaselineAndNotASpike() {
        CounterDeltas.Totals first = deltas.since(NODE, SERVICE, AT,
                CounterDeltas.Totals.of(9_000_000_000L, 4_000_000_000L, 10, 20));

        assertThat(first.networkRxBytes()).isZero();
        assertThat(first.networkTxBytes()).isZero();
        assertThat(first.diskReadBytes()).isZero();
        assertThat(first.diskWriteBytes()).isZero();
        assertThat(deltas.knows(NODE, SERVICE)).isTrue();
    }

    @Test
    void theSecondReadingIsTheDifference() {
        deltas.since(NODE, SERVICE, AT, CounterDeltas.Totals.of(1000, 500, 40, 60));

        CounterDeltas.Totals since = deltas.since(NODE, SERVICE, AT.plusSeconds(15),
                CounterDeltas.Totals.of(1750, 900, 45, 61));

        assertThat(since.networkRxBytes()).isEqualTo(750);
        assertThat(since.networkTxBytes()).isEqualTo(400);
        assertThat(since.diskReadBytes()).isEqualTo(5);
        assertThat(since.diskWriteBytes()).isEqualTo(1);
    }

    @Test
    void aCounterGoingBackwardsMeansTheContainerRestartedAndTheNewTotalIsTheAmount() {
        deltas.since(NODE, SERVICE, AT, CounterDeltas.Totals.of(5_000, 5_000, 5_000, 5_000));

        CounterDeltas.Totals since = deltas.since(NODE, SERVICE, AT.plusSeconds(15),
                CounterDeltas.Totals.of(120, 80, 0, 0));

        // Not negative, which the stat_sample CHECK would reject, and not zero, which would
        // lose the traffic that happened after the restart.
        assertThat(since.networkRxBytes()).isEqualTo(120);
        assertThat(since.networkTxBytes()).isEqualTo(80);
        assertThat(since.diskReadBytes()).isZero();
    }

    @Test
    void aNodeReadingAndAWorkloadReadingOnTheSameNodeAreDifferentSubjects() {
        deltas.since(NODE, null, AT, CounterDeltas.Totals.of(1_000_000, 0, 0, 0));
        deltas.since(NODE, SERVICE, AT, CounterDeltas.Totals.of(10, 0, 0, 0));

        CounterDeltas.Totals node = deltas.since(NODE, null, AT.plusSeconds(15),
                CounterDeltas.Totals.of(1_000_500, 0, 0, 0));
        CounterDeltas.Totals service = deltas.since(NODE, SERVICE, AT.plusSeconds(15),
                CounterDeltas.Totals.of(30, 0, 0, 0));

        assertThat(node.networkRxBytes()).isEqualTo(500);
        assertThat(service.networkRxBytes()).isEqualTo(20);
        assertThat(deltas.trackedSubjects()).isEqualTo(2);
    }

    @Test
    void subjectsThatStoppedReportingAreForgottenSoTheMapIsNotUnbounded() {
        deltas.since(NODE, SERVICE, AT, CounterDeltas.Totals.of(1, 1, 1, 1));
        deltas.since(NODE, null, AT.plusSeconds(7200), CounterDeltas.Totals.of(1, 1, 1, 1));

        int forgotten = deltas.forget(AT.plusSeconds(3600));

        assertThat(forgotten).isEqualTo(1);
        assertThat(deltas.knows(NODE, SERVICE)).isFalse();
        assertThat(deltas.knows(NODE, null)).isTrue();
    }

    @Test
    void aForgottenSubjectThatComesBackStartsFromABaselineAgain() {
        deltas.since(NODE, SERVICE, AT, CounterDeltas.Totals.of(9_999, 0, 0, 0));
        deltas.forget(AT.plusSeconds(1));

        CounterDeltas.Totals since = deltas.since(NODE, SERVICE, AT.plusSeconds(60),
                CounterDeltas.Totals.of(10_500, 0, 0, 0));

        assertThat(since.networkRxBytes()).isZero();
    }
}
