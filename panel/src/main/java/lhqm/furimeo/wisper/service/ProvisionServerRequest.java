package lhqm.furimeo.wisper.service;

import java.util.Map;

/**
 * Payload sent by billing dashboards or resellers to provision a server in one step.
 *
 * <p>Everything an end-user needs to be running immediately: account, organization,
 * project, service, persistent volume storage, and initial execution state.
 */
public record ProvisionServerRequest(
        String email,
        String displayName,
        String password,
        String organizationName,
        String organizationSlug,
        String projectName,
        String projectSlug,
        String serviceName,
        String serviceSlug,
        ServiceKind kind,
        String image,
        String command,
        String workingDir,
        Integer port,
        Long cpuMillicores,
        Long memoryBytes,
        Boolean attachVolume,
        String volumeName,
        String volumeMountPath,
        Long volumeSizeBytes,
        Map<String, String> environment,
        Boolean autoStart) {

    public ProvisionServerRequest {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Customer email is required for provisioning.");
        }
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("Service name is required.");
        }
        kind = kind == null ? ServiceKind.APP : kind;
        attachVolume = attachVolume == null ? (kind == ServiceKind.APP) : attachVolume;
        autoStart = autoStart == null ? Boolean.TRUE : autoStart;
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }
}
