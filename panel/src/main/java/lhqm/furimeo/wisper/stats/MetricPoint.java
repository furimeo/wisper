package lhqm.furimeo.wisper.stats;

import java.time.Instant;

/**
 * One point on a chart, whether it came from a raw sample or from a bucket.
 *
 * <p>One record for both so the browser has one shape to render and the panel has one
 * place that decides what a point means. A raw sample is a bucket of one: its average and
 * its maximum are the same number and its {@code sampleCount} is one, which is true rather
 * than a convenience.
 *
 * @param at                the start of the interval this point describes. For a raw
 *                          sample that is when the node measured; for a bucket it is the
 *                          bucket's aligned start
 * @param cpuMillicores     average over the interval
 * @param cpuMillicoresMax  the highest single reading in it. The pair is what separates a
 *                          workload that is busy from one that is spiking, and an average
 *                          alone hides exactly the second case
 * @param memoryBytesMax    peak, which is the number that explains an OOM kill after the
 *                          fact
 * @param memoryLimitBytes  the ceiling in effect, or null for a bucket - a limit that
 *                          changed mid-window has no single value, and drawing the last one
 *                          across the whole hour would be a line that was never true
 * @param diskBytes         the highest reading in the interval, because disk use is a level
 *                          and not a flow
 * @param networkRxBytes    total in the interval
 * @param sampleCount       how many readings went in. The weight for any further
 *                          aggregation, and the way to tell a quiet hour from a missing one
 */
public record MetricPoint(
        Instant at,
        long cpuMillicores,
        long cpuMillicoresMax,
        long memoryBytes,
        long memoryBytesMax,
        Long memoryLimitBytes,
        long diskBytes,
        long networkRxBytes,
        long networkTxBytes,
        long diskReadBytes,
        long diskWriteBytes,
        int restartCount,
        int sampleCount) {

    /** A raw reading as a point: a bucket of one, where the average is the maximum. */
    public static MetricPoint of(SampleReading reading) {
        return new MetricPoint(
                reading.sampledAt(),
                reading.cpuMillicores(),
                reading.cpuMillicores(),
                reading.memoryBytes(),
                reading.memoryBytes(),
                reading.memoryLimitBytes(),
                reading.diskBytes(),
                reading.networkRxBytes(),
                reading.networkTxBytes(),
                reading.diskReadBytes(),
                reading.diskWriteBytes(),
                reading.restartCount(),
                1);
    }

    /**
     * How full the memory limit was, in percent, or -1 when there is no limit to be full
     * of.
     */
    public int memoryPercentOfLimit() {
        if (memoryLimitBytes == null || memoryLimitBytes <= 0) {
            return -1;
        }
        return (int) Math.min(100, memoryBytesMax * 100 / memoryLimitBytes);
    }
}
