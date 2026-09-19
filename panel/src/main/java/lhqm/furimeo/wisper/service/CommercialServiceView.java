package lhqm.furimeo.wisper.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Commercial view of a service tailored for billing engines and reseller dashboards.
 *
 * <p>Contains precise hardware metrics (CPU, RAM, total storage) and operational status
 * so that billing workers can calculate hourly usage or enforce suspensions without
 * inspecting multiple separate endpoints.
 */
public record CommercialServiceView(
        UUID serviceId,
        String name,
        String slug,
        ServiceKind kind,
        String status,
        DesiredState desiredState,
        String reportedState,
        String health,
        long cpuMillicores,
        long memoryBytes,
        long diskBytes,
        List<VolumeInfo> volumes,
        Integer containerPort,
        String image,
        UUID nodeId,
        long uptimeSeconds,
        Instant createdAt,
        Instant updatedAt,
        UUID organizationId,
        UUID projectId,
        Map<String, String> urls) {

    public record VolumeInfo(
            UUID id,
            String name,
            String mountPath,
            long sizeBytes) {

        public static VolumeInfo from(Volume volume) {
            return new VolumeInfo(volume.id(), volume.name(), volume.mountPath(), volume.sizeBytes());
        }
    }

    public static CommercialServiceView from(
            Service service,
            ServiceSummary summary,
            UUID organizationId,
            List<Volume> attachedVolumes) {

        List<VolumeInfo> volumeList = attachedVolumes == null ? List.of()
                : attachedVolumes.stream().map(VolumeInfo::from).toList();

        long totalVolumeBytes = volumeList.stream().mapToLong(VolumeInfo::sizeBytes).sum();
        long effectiveDiskBytes = Math.max(service.diskBytes(), totalVolumeBytes);

        String reported = summary != null ? summary.reportedState() : null;
        String reportedHealth = summary != null ? summary.health() : null;
        UUID activeNodeId = summary != null ? summary.nodeId() : null;

        String effectiveStatus = resolveStatus(service.desiredState(), reported);
        long uptime = calculateUptime(effectiveStatus, summary != null ? summary.reportedAt() : null, service.createdAt());

        Map<String, String> directUrls = Map.of(
                "panel", "/services/" + service.id(),
                "terminal", "/services/" + service.id() + "/terminal",
                "files", "/services/" + service.id() + "/files",
                "metrics", "/services/" + service.id() + "/metrics");

        return new CommercialServiceView(
                service.id(),
                service.name(),
                service.slug(),
                service.kind(),
                effectiveStatus,
                service.desiredState(),
                reported,
                reportedHealth,
                service.cpuMillicores(),
                service.memoryBytes(),
                effectiveDiskBytes,
                volumeList,
                service.containerPort(),
                service.image(),
                activeNodeId,
                uptime,
                service.createdAt(),
                service.updatedAt(),
                organizationId,
                service.projectId(),
                directUrls);
    }

    private static String resolveStatus(DesiredState desired, String reported) {
        if (desired == DesiredState.STOPPED) {
            return "STOPPED";
        }
        if ("RUNNING".equalsIgnoreCase(reported)) {
            return "RUNNING";
        }
        if ("STOPPED".equalsIgnoreCase(reported) || "FAILED".equalsIgnoreCase(reported)) {
            return "FAILED";
        }
        return "STARTING";
    }

    private static long calculateUptime(String status, Instant reportedAt, Instant createdAt) {
        if (!"RUNNING".equalsIgnoreCase(status)) {
            return 0L;
        }
        Instant reference = reportedAt != null ? reportedAt : createdAt;
        if (reference == null) {
            return 0L;
        }
        long seconds = Duration.between(reference, Instant.now()).toSeconds();
        return Math.max(0L, seconds);
    }
}
