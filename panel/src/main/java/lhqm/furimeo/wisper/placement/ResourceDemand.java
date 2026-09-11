package lhqm.furimeo.wisper.placement;

import java.util.Collection;

import lhqm.furimeo.wisper.service.Service;
import lhqm.furimeo.wisper.service.Volume;

/**
 * What one service asks a node for, in the smallest unit of each resource.
 *
 * <p>This is <strong>committed</strong> demand, not measured usage. An idle workload still
 * holds its ceilings, and a scheduler that packs against what a container happens to be
 * using right now fills a machine that then dies at the first spike (design §7.6). The
 * figures therefore come from the service's own limits and from the size of the volumes it
 * has been promised, never from a statistics sample.
 *
 * @param cpuMillicores the ceiling from {@code service.cpu_millicores}. Converted to
 *                      Docker's nano-CPUs only at the wire, and never treated as
 *                      {@code CPUShares}: they are a share, this is a ceiling, and
 *                      conflating them is how the predecessor let one workload take a
 *                      whole node while the panel showed it politely limited (design §9).
 * @param memoryBytes   {@code service.memory_bytes}
 * @param diskBytes     {@code service.disk_bytes} plus every volume's quota. The volumes
 *                      are counted because they are bytes the node has to be able to give
 *                      out, and because they are the reason the service can never be moved
 *                      off that node afterwards.
 */
public record ResourceDemand(long cpuMillicores, long memoryBytes, long diskBytes) {

    /** A service that asks for nothing. Useful when checking whether a node has headroom at all. */
    public static final ResourceDemand NONE = new ResourceDemand(0L, 0L, 0L);

    public ResourceDemand {
        if (cpuMillicores < 0 || memoryBytes < 0 || diskBytes < 0) {
            throw new IllegalArgumentException(
                    "A resource demand is never negative: " + cpuMillicores + " millicores, "
                            + memoryBytes + " bytes of memory, " + diskBytes + " bytes of disk");
        }
    }

    /** The demand a service and its storage put on whichever node holds them. */
    public static ResourceDemand of(Service service, Collection<Volume> volumes) {
        long volumeBytes = 0L;
        for (Volume volume : volumes) {
            volumeBytes += volume.sizeBytes();
        }
        return new ResourceDemand(service.cpuMillicores(), service.memoryBytes(),
                service.diskBytes() + volumeBytes);
    }

    /** A sentence for the message a customer reads when nothing has room. */
    public String describe() {
        return cpuMillicores + " millicores of CPU, " + megabytes(memoryBytes)
                + " of memory and " + megabytes(diskBytes) + " of disk";
    }

    private static String megabytes(long bytes) {
        long mib = bytes / (1024L * 1024L);
        if (mib >= 1024L) {
            return (mib / 1024L) + " GB";
        }
        return mib + " MB";
    }
}
