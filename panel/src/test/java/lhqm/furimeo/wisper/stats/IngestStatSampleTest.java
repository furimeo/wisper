package lhqm.furimeo.wisper.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.google.protobuf.Timestamp;

import lhqm.furimeo.wisper.proto.v1.NodeSample;
import lhqm.furimeo.wisper.proto.v1.StatSample;
import lhqm.furimeo.wisper.proto.v1.WorkloadSample;

/**
 * The arithmetic between the wire and the column.
 *
 * <p>The wire carries CPU nanoseconds and an interval; the column carries millicores.
 * Getting the division wrong by a factor of a thousand produces a chart that is plausible
 * and completely false, which is the kind of bug that is only found when somebody compares
 * it to {@code top} on the node months later.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IngestStatSampleTest {

    private static final Instant NOW = Instant.parse("2026-03-10T10:00:00Z");
    private static final UUID NODE = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final long FIFTEEN_SECONDS_IN_NANOS = 15_000_000_000L;

    private static final StatsSettings SETTINGS = new StatsSettings(
            Duration.ofHours(48), Duration.ofDays(30), Duration.ofDays(400),
            Duration.ofMinutes(5), Duration.ofHours(3), Duration.ofDays(7),
            Duration.ofHours(1), 5000, Duration.ofMinutes(5), 720,
            Duration.ofMinutes(30), Duration.ofSeconds(20), Duration.ofMinutes(30));

    @Mock
    private StoreSampleReading store;

    @Mock
    private LiveMetricFeed feed;

    private IngestStatSample ingest() {
        return new IngestStatSample(store, new CounterDeltas(), feed, SETTINGS,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void halfACoreOverFifteenSecondsIsFiveHundredMillicores() {
        SampleReading reading = ingest().readingOf(NODE, workload(builder -> builder
                .setWorkloadId(SERVICE.toString())
                .setCpuNanos(FIFTEEN_SECONDS_IN_NANOS / 2)
                .setMemoryUsedBytes(256L * 1024 * 1024)
                .setMemoryLimitBytes(512L * 1024 * 1024)));

        assertThat(reading).isNotNull();
        assertThat(reading.cpuMillicores()).isEqualTo(500);
        assertThat(reading.serviceId()).isEqualTo(SERVICE);
        assertThat(reading.windowSeconds()).isEqualTo(15);
        assertThat(reading.memoryLimitBytes()).isEqualTo(512L * 1024 * 1024);
    }

    @Test
    void twoWholeCoresIsTwoThousandMillicores() {
        SampleReading reading = ingest().readingOf(NODE, workload(builder -> builder
                .setWorkloadId(SERVICE.toString())
                .setCpuNanos(FIFTEEN_SECONDS_IN_NANOS * 2)));

        assertThat(reading.cpuMillicores()).isEqualTo(2000);
    }

    @Test
    void aFractionSmallerThanAMillicoreDoesNotOverflowIntoOne() {
        SampleReading reading = ingest().readingOf(NODE, workload(builder -> builder
                .setWorkloadId(SERVICE.toString())
                .setCpuNanos(1_000)));

        assertThat(reading.cpuMillicores()).isZero();
    }

    @Test
    void aNodeSampleHasNoServiceAndItsLimitIsTheMachinesMemory() {
        StatSample sample = StatSample.newBuilder()
                .setNodeId(NODE.toString())
                .setTakenAt(stamp(NOW))
                .setIntervalNanos(FIFTEEN_SECONDS_IN_NANOS)
                .setNode(NodeSample.newBuilder()
                        .setCpuNanos(FIFTEEN_SECONDS_IN_NANOS * 3)
                        .setMemoryUsedBytes(6L * 1024 * 1024 * 1024)
                        .setMemoryTotalBytes(16L * 1024 * 1024 * 1024)
                        .setDiskUsedBytes(120L * 1024 * 1024 * 1024))
                .build();

        SampleReading reading = ingest().readingOf(NODE, sample);

        assertThat(reading.isNodeLevel()).isTrue();
        assertThat(reading.serviceId()).isNull();
        assertThat(reading.subjectId()).isEqualTo(NODE);
        assertThat(reading.cpuMillicores()).isEqualTo(3000);
        assertThat(reading.memoryLimitBytes()).isEqualTo(16L * 1024 * 1024 * 1024);
    }

    @Test
    void aWorkloadIdThatIsNotOneOfOursIsDroppedRatherThanInvented() {
        SampleReading reading = ingest().readingOf(NODE, workload(builder -> builder
                .setWorkloadId("some-container-we-did-not-schedule")
                .setCpuNanos(1)));

        assertThat(reading).isNull();
    }

    @Test
    void aSampleAboutNothingIsDropped() {
        StatSample empty = StatSample.newBuilder()
                .setNodeId(NODE.toString())
                .setIntervalNanos(FIFTEEN_SECONDS_IN_NANOS)
                .build();

        assertThat(ingest().readingOf(NODE, empty)).isNull();
    }

    @Test
    void aNodeWithAClockAnHourFastHasItsSamplePulledBackToNow() {
        StatSample fromTheFuture = StatSample.newBuilder()
                .setNodeId(NODE.toString())
                .setTakenAt(stamp(NOW.plus(Duration.ofHours(1))))
                .setIntervalNanos(FIFTEEN_SECONDS_IN_NANOS)
                .setWorkload(WorkloadSample.newBuilder().setWorkloadId(SERVICE.toString()))
                .build();

        SampleReading reading = ingest().readingOf(NODE, fromTheFuture);

        assertThat(reading.sampledAt()).isEqualTo(NOW);
    }

    @Test
    void aSampleFromTheRecentPastKeepsItsOwnTimestampBecauseThatIsWhenItHappened() {
        Instant measured = NOW.minus(Duration.ofMinutes(40));
        StatSample backfilled = StatSample.newBuilder()
                .setNodeId(NODE.toString())
                .setTakenAt(stamp(measured))
                .setIntervalNanos(FIFTEEN_SECONDS_IN_NANOS)
                .setWorkload(WorkloadSample.newBuilder().setWorkloadId(SERVICE.toString()))
                .build();

        assertThat(ingest().readingOf(NODE, backfilled).sampledAt()).isEqualTo(measured);
    }

    @Test
    void countersAreStoredAsTheAmountInTheWindowAndNotAsTheRunningTotal() {
        IngestStatSample ingest = ingest();
        ingest.readingOf(NODE, workload(builder -> builder
                .setWorkloadId(SERVICE.toString())
                .setNetworkRxBytes(1_000_000)
                .setBlockWriteBytes(4_096)));

        SampleReading second = ingest.readingOf(NODE, workload(builder -> builder
                .setWorkloadId(SERVICE.toString())
                .setNetworkRxBytes(1_250_000)
                .setBlockWriteBytes(8_192)));

        assertThat(second.networkRxBytes()).isEqualTo(250_000);
        assertThat(second.diskWriteBytes()).isEqualTo(4_096);
    }

    private static StatSample workload(Consumer<WorkloadSample.Builder> shape) {
        WorkloadSample.Builder workload = WorkloadSample.newBuilder();
        shape.accept(workload);
        return StatSample.newBuilder()
                .setNodeId(NODE.toString())
                .setTakenAt(stamp(NOW))
                .setIntervalNanos(FIFTEEN_SECONDS_IN_NANOS)
                .setWorkload(workload)
                .build();
    }

    private static Timestamp stamp(Instant at) {
        return Timestamp.newBuilder().setSeconds(at.getEpochSecond()).setNanos(at.getNano())
                .build();
    }
}
