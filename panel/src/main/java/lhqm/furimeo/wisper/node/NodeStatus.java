package lhqm.furimeo.wisper.node;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * What a node says about itself. Exactly one row per node, keyed by the foreign key so the
 * one-to-one cannot drift into a one-to-many.
 *
 * <p>Every column is written by a gRPC handler from something sasayaki reported, and by
 * nothing else - no screen, no use-case, no admin action (schema.md §2). Those handlers
 * write with targeted {@code INSERT ... ON CONFLICT} statements rather than through
 * {@code save()}: a heartbeat carries capacity and a handshake carries machine facts, and
 * a whole-row write from either would blank whatever the other had just recorded.
 *
 * <p>This record is therefore the read side. It is what the admin screens render and what
 * the placement filter reads, and its accessors below answer the three questions those
 * two actually ask.
 *
 * @param appliedGeneration what the node has converged to; {@code -1} means it has never
 *                          said. Compared with {@code node.desired_generation}: equal is
 *                          converged, lower is in flight, higher is impossible and means
 *                          two panels are driving one machine.
 * @param runscAvailable    false means the node falls back to {@code runc}. The panel
 *                          shows it as less isolated rather than refusing to use it
 *                          (design §7.2), so this is surfaced and never quietly ignored.
 * @param quotaEnforceable  false on anything that is not XFS, where a disk limit is
 *                          advisory and must be labelled as such
 * @param doctorReport      the whole preflight report as a JSON document; see
 *                          {@link NodeDoctorReport}
 */
public record NodeStatus(
        @Id UUID nodeId,
        NodeConnectionState connectionState,
        Instant lastHeartbeatAt,
        Instant lastConnectedAt,
        Instant lastDisconnectedAt,
        String remoteAddress,
        String agentVersion,
        Integer protocolVersion,
        long appliedGeneration,
        Instant lastReconcileAt,
        String reconcileError,
        Integer cpuCores,
        Long cpuMillicoresCapacity,
        Long cpuMillicoresUsed,
        Long memoryBytesCapacity,
        Long memoryBytesUsed,
        Long diskBytesCapacity,
        Long diskBytesUsed,
        int workloadCount,
        int runningWorkloadCount,
        Boolean dockerHealthy,
        String dockerVersion,
        Boolean runscAvailable,
        String kernelVersion,
        String osDescription,
        Long clockSkewMillis,
        String volumeFilesystem,
        Boolean quotaEnforceable,
        String doctorReport,
        Instant doctorReportedAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    /** The value the column starts at, meaning the node has never reported. */
    public static final long NEVER_REPORTED = -1L;

    public boolean isConnected() {
        return connectionState != NodeConnectionState.DISCONNECTED;
    }

    /** Whether the node has caught up with a published generation. */
    public boolean hasConverged(long desiredGeneration) {
        return appliedGeneration == desiredGeneration;
    }

    /**
     * The impossible case: the node claims a generation this panel never published.
     *
     * <p>It means a second panel is pointed at the same machine, and the answer is to
     * suspend rather than to reconcile - two panels publishing different specs to one node
     * will fight forever, and each will see the other's work as drift (panel-ports.md §3).
     */
    public boolean isAheadOf(long desiredGeneration) {
        return appliedGeneration > desiredGeneration;
    }

    /** Millicores still uncommitted, or zero when the node has not reported capacity. */
    public long freeMillicores() {
        if (cpuMillicoresCapacity == null) {
            return 0L;
        }
        long used = cpuMillicoresUsed == null ? 0L : cpuMillicoresUsed;
        return Math.max(0L, cpuMillicoresCapacity - used);
    }

    /** Bytes of memory still uncommitted, or zero when nothing has been reported. */
    public long freeMemoryBytes() {
        if (memoryBytesCapacity == null) {
            return 0L;
        }
        long used = memoryBytesUsed == null ? 0L : memoryBytesUsed;
        return Math.max(0L, memoryBytesCapacity - used);
    }

    /** Bytes of disk still uncommitted, or zero when nothing has been reported. */
    public long freeDiskBytes() {
        if (diskBytesCapacity == null) {
            return 0L;
        }
        long used = diskBytesUsed == null ? 0L : diskBytesUsed;
        return Math.max(0L, diskBytesCapacity - used);
    }
}
