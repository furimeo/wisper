package lhqm.furimeo.wisper.stats;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A chart's worth of data: the points, the window they cover and which table they came
 * from.
 *
 * <p>The window is echoed back because the request's window and the answer's are not
 * always the same. Asking for a year of a service that was created last week produces
 * points from last week, and a chart that draws them across a year of empty axis looks
 * broken.
 *
 * @param subjectId the service, or the node for a machine-level chart
 * @param source    which table answered, so the axis can say "hourly average" rather than
 *                  implying a resolution it does not have
 * @param points    oldest first, which is the order a chart draws in and therefore the
 *                  order that saves the client sorting
 */
public record MetricSeries(
        UUID subjectId,
        MetricSource source,
        Instant from,
        Instant to,
        List<MetricPoint> points) {

    public MetricSeries {
        points = points == null ? List.of() : List.copyOf(points);
    }

    /** Nothing was measured in this window. A real answer, not a failure. */
    public boolean isEmpty() {
        return points.isEmpty();
    }

    /** The most recent point, or null when there is none. */
    public MetricPoint latest() {
        return points.isEmpty() ? null : points.get(points.size() - 1);
    }

    /** The highest CPU reading anywhere in the window, for the axis. */
    public long peakCpuMillicores() {
        return points.stream().mapToLong(MetricPoint::cpuMillicoresMax).max().orElse(0);
    }

    /** The highest memory reading anywhere in the window, for the axis. */
    public long peakMemoryBytes() {
        return points.stream().mapToLong(MetricPoint::memoryBytesMax).max().orElse(0);
    }
}
