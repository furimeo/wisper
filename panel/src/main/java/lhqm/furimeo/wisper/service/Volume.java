package lhqm.furimeo.wisper.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * Persistent storage attached to a service, and the thing that pins it to one machine.
 *
 * <p>The bytes live at {@code /var/lib/wisper/volumes/<service-id>/<volume-id>} on the
 * node. That layout is fixed - ids and not slugs, so renaming a service never moves a
 * directory - and changing it later would mean moving customer data (design §11.1). The
 * panel never builds that path: it sends ids, the node resolves them, and
 * {@code host_path} comes back for display only.
 *
 * <p>This row has two owners, split down the middle. The panel writes {@code name},
 * {@code mount_path}, {@code size_bytes}, {@code read_only} and {@code backup_enabled};
 * everything from {@code usedBytes} down is written by the node from a status report and
 * by nothing else (schema.md §2). The withers below therefore touch only the panel's half.
 *
 * @param sizeBytes the XFS project quota the node applies. Where a node reports
 *                  {@code quota_enforceable = false} - ext4, for instance - the limit is
 *                  advisory, and the panel says so rather than implying an enforcement
 *                  that is not happening.
 * @param usedBytes what the node last measured, or null before it has reported once.
 *                  Null is "not measured yet", which is not the same as zero.
 */
public record Volume(
        @Id UUID id,
        UUID serviceId,
        String name,
        String mountPath,
        long sizeBytes,
        boolean readOnly,
        boolean backupEnabled,

        Long usedBytes,
        Instant usedBytesMeasuredAt,
        Long inodeCount,
        String hostPath,
        String reportedState,
        String lastError,

        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    /**
     * Mount points that would break the container or hide the platform's own files.
     * Mirrors {@code volume_mount_path_not_system}.
     */
    public static final List<String> FORBIDDEN_MOUNT_PATHS =
            List.of("/", "/etc", "/proc", "/sys", "/dev", "/usr", "/bin", "/sbin", "/lib");

    /** A new volume, before any node has reported anything about it. */
    public static Volume of(UUID id, UUID serviceId, String name, String mountPath, long sizeBytes,
                            boolean readOnly, boolean backupEnabled) {
        return new Volume(id, serviceId, name, mountPath, sizeBytes, readOnly, backupEnabled,
                null, null, null, null, null, null, null, null, null);
    }

    /** A new quota. Everything the node reported is left exactly as the node left it. */
    public Volume resizedTo(long newSizeBytes) {
        return new Volume(id, serviceId, name, mountPath, newSizeBytes, readOnly, backupEnabled,
                usedBytes, usedBytesMeasuredAt, inodeCount, hostPath, reportedState, lastError,
                createdAt, updatedAt, version);
    }

    /** How full it is, or -1 when the node has not measured it yet. */
    public int percentUsed() {
        if (usedBytes == null || sizeBytes <= 0) {
            return -1;
        }
        long percent = usedBytes * 100 / sizeBytes;
        return (int) Math.min(percent, 100);
    }
}
