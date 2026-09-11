package lhqm.furimeo.wisper.stats;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Turns the cumulative counters a node reports into the per-window amounts the panel
 * stores.
 *
 * <p>{@code stats.proto} sends counters, not rates, on purpose: the node reads a total out
 * of a cgroup and does not have to remember anything, and two samples that land in the
 * same bucket then add up exactly. The column
 * {@code stat_sample.network_rx_bytes} is the amount in that window, because that is what
 * a rollup sums and what a chart draws. Subtracting the two is this class's whole job.
 *
 * <h2>Three cases, and what each of them means</h2>
 *
 * <ul>
 * <li><strong>A previous total for this subject.</strong> The delta is the difference.</li>
 * <li><strong>No previous total</strong> - the panel restarted, or this workload has only
 *     just appeared. The delta is zero: the first reading is a baseline, not a spike of
 *     everything the container has ever transferred. One flat point at the start of a
 *     chart is correct; a bar the height of a month is not.</li>
 * <li><strong>The total went backwards.</strong> The counter was reset, which for a
 *     container means it restarted. The delta is the new total, because that is genuinely
 *     how much has happened since the reset. Treating it as a negative would fail the
 *     {@code stat_sample_counters_not_negative} CHECK; treating it as zero would lose the
 *     traffic.</li>
 * </ul>
 *
 * <p>In memory, per panel process, and that is the honest cost of not adding a column for
 * it. Losing the map costs one flat sample per subject after a restart, which is fifteen
 * seconds of a chart. The alternative - reading the previous row back on every sample -
 * would be one extra query per workload per node every fifteen seconds, to recover a value
 * the panel had in hand a moment ago.
 *
 * <p>Thread-safe: {@code PushStats} is one gRPC stream per node and there are many nodes.
 */
@Component
public class CounterDeltas {

    private final Map<Subject, Totals> lastSeen = new ConcurrentHashMap<>();

    /**
     * The amounts for this reading, and remembers the totals for the next one.
     *
     * @param nodeId    which machine
     * @param serviceId which workload, or null for the machine itself
     * @param takenAt   when the node measured, used to expire subjects that stopped
     *                  reporting
     * @param totals    the cumulative counters as the node sent them
     */
    public Totals since(UUID nodeId, UUID serviceId, Instant takenAt, Totals totals) {
        Subject subject = new Subject(nodeId, serviceId);
        Totals previous = lastSeen.put(subject, totals.at(takenAt));
        if (previous == null) {
            return Totals.NONE;
        }
        return totals.minus(previous);
    }

    /**
     * Forgets subjects that have not reported since a cut-off.
     *
     * <p>Called by the retention sweep. Without it, every workload that has ever existed on
     * every node keeps four longs alive for as long as the panel runs - small, but
     * unbounded, and unbounded is the property that matters.
     *
     * @return how many were forgotten
     */
    public int forget(Instant before) {
        int[] removed = {0};
        lastSeen.entrySet().removeIf(entry -> {
            boolean stale = entry.getValue().at() != null && entry.getValue().at().isBefore(before);
            if (stale) {
                removed[0]++;
            }
            return stale;
        });
        return removed[0];
    }

    /** How many subjects are being tracked. For the sweep's log line and for tests. */
    public int trackedSubjects() {
        return lastSeen.size();
    }

    /** Whether anything has been seen for this subject since the panel started. */
    boolean knows(UUID nodeId, UUID serviceId) {
        return lastSeen.containsKey(new Subject(nodeId, serviceId));
    }

    /** One machine, or one workload on one machine. */
    private record Subject(UUID nodeId, UUID serviceId) {

        private Subject {
            Objects.requireNonNull(nodeId, "nodeId");
        }
    }

    /**
     * The four cumulative counters, and when they were read.
     *
     * @param at null on a difference, which describes an interval rather than an instant
     */
    public record Totals(long networkRxBytes, long networkTxBytes, long diskReadBytes,
                         long diskWriteBytes, Instant at) {

        /** The answer for a subject nothing is known about yet. */
        public static final Totals NONE = new Totals(0, 0, 0, 0, null);

        /** The counters as a node sent them, with no timestamp attached yet. */
        public static Totals of(long networkRxBytes, long networkTxBytes, long diskReadBytes,
                                long diskWriteBytes) {
            return new Totals(networkRxBytes, networkTxBytes, diskReadBytes, diskWriteBytes, null);
        }

        /** The same counters, stamped with when they were read. */
        Totals at(Instant instant) {
            return new Totals(networkRxBytes, networkTxBytes, diskReadBytes, diskWriteBytes,
                    instant);
        }

        /** This reading minus an earlier one, with a counter reset read as "since the reset". */
        Totals minus(Totals previous) {
            return new Totals(
                    difference(networkRxBytes, previous.networkRxBytes),
                    difference(networkTxBytes, previous.networkTxBytes),
                    difference(diskReadBytes, previous.diskReadBytes),
                    difference(diskWriteBytes, previous.diskWriteBytes),
                    null);
        }

        private static long difference(long current, long previous) {
            // Backwards means the counter was reset - the container restarted - so the
            // amount since the reset is the current value itself.
            return current < previous ? Math.max(current, 0) : current - previous;
        }
    }
}
