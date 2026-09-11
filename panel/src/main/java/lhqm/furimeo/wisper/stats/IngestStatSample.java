package lhqm.furimeo.wisper.stats;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.google.protobuf.Timestamp;

import lhqm.furimeo.wisper.proto.v1.NodeSample;
import lhqm.furimeo.wisper.proto.v1.StatSample;
import lhqm.furimeo.wisper.proto.v1.WorkloadSample;

/**
 * Takes one sample off the {@code PushStats} stream and stores it.
 *
 * <p>Called by {@code grpc.NodeServiceEndpoint} for every message on that stream and by
 * nothing else (panel-ports.md §3). It is on the hot path - one sample per workload per
 * node every fifteen seconds - so it does exactly three things: convert, store, publish.
 *
 * <h2>Converting</h2>
 *
 * <p>The wire carries CPU as nanoseconds burned during the interval and the interval's
 * length; the column is millicores, which is that division. It carries network and block
 * counters as running totals; the columns are amounts in the window, which is
 * {@link CounterDeltas}. Doing both here means the rollup can sum the columns and a chart
 * can draw them, without either knowing what a cgroup looks like.
 *
 * <p>{@code workload_id} is the service id as a string - the node knows a workload by the
 * id of the service it belongs to (see {@code placement.PlacedService}) - so a sample about
 * a workload becomes a row with a {@code service_id} and a sample about the machine becomes
 * one without.
 *
 * <h2>Losing a sample is not an incident</h2>
 *
 * <p>A metric that is dropped is a gap in a chart; a metric that throws out of this method
 * kills the stream that carries every other node's readings too. Everything that can go
 * wrong with one sample - an unparseable workload id, a subject that has been deleted, a
 * duplicate after a reconnect - is logged at debug and the next sample is processed.
 */
@Component
public class IngestStatSample {

    private static final Logger log = LoggerFactory.getLogger(IngestStatSample.class);

    /** Nanoseconds in a second, and millicores in a core. */
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long MILLICORES_PER_CORE = 1000L;

    private final StoreSampleReading store;
    private final CounterDeltas deltas;
    private final LiveMetricFeed feed;
    private final StatsSettings settings;
    private final Clock clock;

    public IngestStatSample(StoreSampleReading store, CounterDeltas deltas, LiveMetricFeed feed,
                            StatsSettings settings) {
        this(store, deltas, feed, settings, Clock.systemUTC());
    }

    IngestStatSample(StoreSampleReading store, CounterDeltas deltas, LiveMetricFeed feed,
                     StatsSettings settings, Clock clock) {
        this.store = store;
        this.deltas = deltas;
        this.feed = feed;
        this.settings = settings;
        this.clock = clock;
    }

    /** Stores one sample from one node, and shows it to anybody watching. */
    public void accept(UUID nodeId, StatSample sample) {
        SampleReading reading = readingOf(nodeId, sample);
        if (reading == null) {
            return;
        }
        if (store.store(reading)) {
            feed.publish(reading.subjectId(), MetricPoint.of(reading));
        }
    }

    /**
     * The row this sample becomes, or null when it describes nothing the panel can store.
     *
     * <p>Package-private so the conversion can be tested without a database: the arithmetic
     * here is the part that is worth being sure about.
     */
    SampleReading readingOf(UUID nodeId, StatSample sample) {
        if (nodeId == null || sample == null) {
            return null;
        }
        long intervalNanos = Math.max(1, sample.getIntervalNanos());
        Instant takenAt = takenAt(sample);

        return switch (sample.getSubjectCase()) {
            case NODE -> nodeReading(nodeId, takenAt, intervalNanos, sample.getNode());
            case WORKLOAD -> workloadReading(nodeId, takenAt, intervalNanos, sample.getWorkload());
            case SUBJECT_NOT_SET -> {
                log.debug("Node {} sent a sample about nothing", nodeId);
                yield null;
            }
        };
    }

    private SampleReading nodeReading(UUID nodeId, Instant takenAt, long intervalNanos,
                                      NodeSample node) {
        CounterDeltas.Totals since = deltas.since(nodeId, null, takenAt,
                // A node has no block-IO counters of its own on this message; the pair is
                // carried anyway so one subject has one shape in the delta map.
                CounterDeltas.Totals.of(node.getNetworkRxBytes(), node.getNetworkTxBytes(), 0, 0));
        return new SampleReading(
                nodeId,
                null,
                takenAt,
                secondsIn(intervalNanos),
                millicores(node.getCpuNanos(), intervalNanos),
                node.getMemoryUsedBytes(),
                node.getMemoryTotalBytes() > 0 ? node.getMemoryTotalBytes() : null,
                node.getDiskUsedBytes(),
                since.networkRxBytes(),
                since.networkTxBytes(),
                0,
                0,
                0);
    }

    private SampleReading workloadReading(UUID nodeId, Instant takenAt, long intervalNanos,
                                          WorkloadSample workload) {
        UUID serviceId = serviceIdOf(nodeId, workload.getWorkloadId());
        if (serviceId == null) {
            return null;
        }
        CounterDeltas.Totals since = deltas.since(nodeId, serviceId, takenAt,
                CounterDeltas.Totals.of(workload.getNetworkRxBytes(), workload.getNetworkTxBytes(),
                        workload.getBlockReadBytes(), workload.getBlockWriteBytes()));
        return new SampleReading(
                nodeId,
                serviceId,
                takenAt,
                secondsIn(intervalNanos),
                millicores(workload.getCpuNanos(), intervalNanos),
                workload.getMemoryUsedBytes(),
                workload.getMemoryLimitBytes() > 0 ? workload.getMemoryLimitBytes() : null,
                workload.getDiskUsedBytes(),
                since.networkRxBytes(),
                since.networkTxBytes(),
                since.diskReadBytes(),
                since.diskWriteBytes(),
                0);
    }

    /**
     * The workload id as a service id.
     *
     * <p>A value that is not a UUID is a workload the panel did not put in that node's
     * spec. Dropping it is right: there is no row it could belong to, and inventing one
     * would let a node write metrics about anything it liked.
     */
    private static UUID serviceIdOf(UUID nodeId, String workloadId) {
        if (workloadId == null || workloadId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(workloadId);
        } catch (IllegalArgumentException notAWorkloadWeKnow) {
            log.debug("Node {} reported metrics for workload \"{}\", which is not one of ours",
                    nodeId, workloadId);
            return null;
        }
    }

    /**
     * When the node measured.
     *
     * <p>Two corrections, both of which exist because the alternative is unreadable charts:
     *
     * <ul>
     * <li>A sample with no timestamp gets the panel's clock. A node that does not set one
     *     is broken, and one imprecise point beats a blind spot that nobody notices until
     *     they go looking for a graph that was never drawn.</li>
     * <li>A sample from the future is pulled back to now. A node whose clock is wrong would
     *     otherwise write buckets ahead of the present that no chart window reaches and the
     *     retention sweep never catches up with (design §7.2 calls clock skew out for
     *     exactly this class of symptom).</li>
     * </ul>
     */
    private Instant takenAt(StatSample sample) {
        Instant now = Instant.now(clock);
        if (!sample.hasTakenAt()) {
            return now;
        }
        Timestamp stamp = sample.getTakenAt();
        Instant claimed = Instant.ofEpochSecond(stamp.getSeconds(), stamp.getNanos());
        Instant ceiling = now.plus(settings.maxFutureSkew());
        if (claimed.isAfter(ceiling)) {
            log.debug("A sample claimed to be taken at {}, which is past {}; storing it as now",
                    claimed, ceiling);
            return now;
        }
        return claimed;
    }

    /** Nanoseconds of CPU during the interval, as thousandths of a core. */
    private static long millicores(long cpuNanos, long intervalNanos) {
        if (cpuNanos <= 0) {
            return 0;
        }
        return Math.max(0, cpuNanos / intervalNanos * MILLICORES_PER_CORE
                + cpuNanos % intervalNanos * MILLICORES_PER_CORE / intervalNanos);
    }

    /** The interval in whole seconds, at least one. */
    private static int secondsIn(long intervalNanos) {
        return (int) Math.max(1, Math.round((double) intervalNanos / NANOS_PER_SECOND));
    }
}
