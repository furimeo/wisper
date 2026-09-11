package lhqm.furimeo.wisper.stats;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code stat_sample}, in the shape the panel stores it.
 *
 * <p>Not the protobuf message. {@code StatSample} on the wire carries <em>cumulative</em>
 * counters and the length of the interval they cover, because that is what a node can read
 * out of a cgroup without keeping state. The column
 * {@code stat_sample.network_rx_bytes} is bytes <em>in this window</em>, because that is
 * what a rollup can sum and what a chart can draw. {@link CounterDeltas} is the one place
 * that converts, and this record is what comes out of it.
 *
 * <p>Everything is a long in its smallest unit. CPU is millicores rather than a
 * percentage: a percentage of what is a question with a different answer on every node.
 *
 * @param serviceId         null for a reading about the machine itself
 * @param sampledAt         when the node measured, never when the panel stored. A chart
 *                          drawn on arrival time shows spikes that never happened, and a
 *                          node that reconnects and backfills would produce a wall of them
 * @param windowSeconds     what the counters below cover, and the weight this reading
 *                          carries in an average
 * @param memoryLimitBytes  the ceiling in effect, so a chart can draw the limit line
 *                          without joining against the spec; null for a node reading, whose
 *                          ceiling is its total memory
 */
public record SampleReading(
        UUID nodeId,
        UUID serviceId,
        Instant sampledAt,
        int windowSeconds,
        long cpuMillicores,
        long memoryBytes,
        Long memoryLimitBytes,
        long diskBytes,
        long networkRxBytes,
        long networkTxBytes,
        long diskReadBytes,
        long diskWriteBytes,
        int restartCount) {

    public SampleReading {
        if (nodeId == null) {
            throw new IllegalArgumentException("A reading belongs to a node");
        }
        if (sampledAt == null) {
            throw new IllegalArgumentException("A reading needs the instant it was taken");
        }
        // The CHECK constraints on stat_sample refuse a non-positive window and a negative
        // counter. Clamping here means a node with a wrong clock or a wrapped counter costs
        // one flat reading rather than a rejected batch and a gap in the chart.
        windowSeconds = Math.max(1, windowSeconds);
        cpuMillicores = Math.max(0, cpuMillicores);
        memoryBytes = Math.max(0, memoryBytes);
        diskBytes = Math.max(0, diskBytes);
        networkRxBytes = Math.max(0, networkRxBytes);
        networkTxBytes = Math.max(0, networkTxBytes);
        diskReadBytes = Math.max(0, diskReadBytes);
        diskWriteBytes = Math.max(0, diskWriteBytes);
        restartCount = Math.max(0, restartCount);
    }

    /** Whether this describes the machine rather than one workload on it. */
    public boolean isNodeLevel() {
        return serviceId == null;
    }

    /** The subject a live chart is keyed by: the service if there is one, else the node. */
    public UUID subjectId() {
        return serviceId == null ? nodeId : serviceId;
    }
}
