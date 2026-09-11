package lhqm.furimeo.wisper.placement;

import java.util.UUID;

/**
 * One candidate node, sized: what it has, what is already spoken for, and how much of it
 * placement is not allowed to touch.
 *
 * <p>The reserved figures are the headroom from {@code wisper.node.*-headroom-percent}. A
 * node that is filled to its last byte does not degrade, it dies, and it takes every
 * customer on it down rather than the one deployment that would not fit (design §7.6). So
 * the scheduler pretends the machine is smaller than it is and refuses the placement that
 * would cross the line - which is a deploy that fails with a sentence, instead of a node
 * that stops answering.
 *
 * <p>{@code committed} is the sum of the ceilings of everything already placed here,
 * including the shared database engines the node runs. It is not measured usage: see
 * {@link ResourceDemand}.
 *
 * <p>There is no tag list here. {@link LoadNodeCapacity} filters on tags in SQL, so every
 * candidate that reaches this record already carries the ones the service asked for, and a
 * copy of them travelling alongside would only be something a second filter could get
 * wrong.
 */
public record NodeCapacity(
        UUID nodeId,
        String name,
        long cpuMillicoresTotal,
        long cpuMillicoresReserved,
        long cpuMillicoresCommitted,
        long memoryBytesTotal,
        long memoryBytesReserved,
        long memoryBytesCommitted,
        long diskBytesTotal,
        long diskBytesReserved,
        long diskBytesCommitted) {

    /**
     * A candidate, with the headroom percentages applied to the reported totals.
     *
     * <p>The arithmetic is here rather than in the query so that it can be tested without
     * a database, and so that the one place that decides what "reserved" means is the one
     * place a reader has to look.
     */
    public static NodeCapacity of(UUID nodeId, String name,
                                  long cpuMillicoresTotal, long cpuMillicoresCommitted,
                                  long memoryBytesTotal, long memoryBytesCommitted,
                                  long diskBytesTotal, long diskBytesCommitted,
                                  int cpuHeadroomPercent, int memoryHeadroomPercent,
                                  int diskHeadroomPercent) {
        return new NodeCapacity(nodeId, name,
                cpuMillicoresTotal, reserve(cpuMillicoresTotal, cpuHeadroomPercent),
                cpuMillicoresCommitted,
                memoryBytesTotal, reserve(memoryBytesTotal, memoryHeadroomPercent),
                memoryBytesCommitted,
                diskBytesTotal, reserve(diskBytesTotal, diskHeadroomPercent),
                diskBytesCommitted);
    }

    /** CPU the scheduler is allowed to hand out at all. */
    public long usableCpuMillicores() {
        return Math.max(0L, cpuMillicoresTotal - cpuMillicoresReserved);
    }

    public long usableMemoryBytes() {
        return Math.max(0L, memoryBytesTotal - memoryBytesReserved);
    }

    public long usableDiskBytes() {
        return Math.max(0L, diskBytesTotal - diskBytesReserved);
    }

    /** CPU still uncommitted inside the headroom line. Never negative. */
    public long freeCpuMillicores() {
        return Math.max(0L, usableCpuMillicores() - cpuMillicoresCommitted);
    }

    public long freeMemoryBytes() {
        return Math.max(0L, usableMemoryBytes() - memoryBytesCommitted);
    }

    public long freeDiskBytes() {
        return Math.max(0L, usableDiskBytes() - diskBytesCommitted);
    }

    /** Whether this node has reported enough about itself to be sized at all. */
    public boolean isSized() {
        return cpuMillicoresTotal > 0 && memoryBytesTotal > 0 && diskBytesTotal > 0;
    }

    /** Whether the demand fits in all three dimensions without crossing the headroom line. */
    public boolean fits(ResourceDemand demand) {
        return isSized()
                && freeCpuMillicores() >= demand.cpuMillicores()
                && freeMemoryBytes() >= demand.memoryBytes()
                && freeDiskBytes() >= demand.diskBytes();
    }

    /**
     * How full this node would be with the demand on it, summed over the three dimensions.
     *
     * <p>Best-fit means the tightest node that still works, so the scheduler picks the
     * highest value this returns. Packing rather than spreading is the right default here:
     * a service pinned to a node by a volume can never be moved off it afterwards, so
     * leaving whole machines empty is how a fleet ends up unable to take a large workload
     * while every node is a third full.
     *
     * <p>Only meaningful for a node the demand {@link #fits}; a dimension with no usable
     * capacity would otherwise divide by zero.
     */
    public double pressureWith(ResourceDemand demand) {
        return ratio(cpuMillicoresCommitted + demand.cpuMillicores(), usableCpuMillicores())
                + ratio(memoryBytesCommitted + demand.memoryBytes(), usableMemoryBytes())
                + ratio(diskBytesCommitted + demand.diskBytes(), usableDiskBytes());
    }

    /** Which dimension is the one that refused, for the message a customer reads. */
    public String shortfallAgainst(ResourceDemand demand) {
        if (!isSized()) {
            return name + " has not reported its capacity yet";
        }
        if (freeCpuMillicores() < demand.cpuMillicores()) {
            return name + " has " + freeCpuMillicores() + " of " + demand.cpuMillicores()
                    + " millicores free";
        }
        if (freeMemoryBytes() < demand.memoryBytes()) {
            return name + " has " + (freeMemoryBytes() / (1024L * 1024L)) + " MB of the "
                    + (demand.memoryBytes() / (1024L * 1024L)) + " MB of memory needed";
        }
        if (freeDiskBytes() < demand.diskBytes()) {
            return name + " has " + (freeDiskBytes() / (1024L * 1024L)) + " MB of the "
                    + (demand.diskBytes() / (1024L * 1024L)) + " MB of disk needed";
        }
        return name + " has room";
    }

    private static long reserve(long total, int percent) {
        if (total <= 0L || percent <= 0) {
            return 0L;
        }
        return total * percent / 100L;
    }

    private static double ratio(long used, long usable) {
        return usable <= 0L ? 1.0d : (double) used / (double) usable;
    }
}
