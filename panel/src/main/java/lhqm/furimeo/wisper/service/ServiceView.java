package lhqm.furimeo.wisper.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A service as a screen may see it.
 *
 * <p>{@link Service} carries two encrypted envelopes - the webhook secret and the
 * repository credential - and every model attribute a controller adds is serialised into
 * the page's props. Handing the record straight to a view would put both of them in the
 * HTML of the settings screen, base64 and all. This record has neither field, so that
 * cannot happen by forgetting: what a page can leak is decided here, once, and not in
 * every controller that renders a service.
 *
 * <p>{@link #hasRepositoryCredential} is the only thing said about the credential, and it
 * is what the form needs: an empty password box that means "unchanged" has to be able to
 * say whether there is something to leave unchanged.
 *
 * <p>{@code command} and {@code entrypoint} come back as the line a person types rather
 * than as argv, because the settings form is a text input and
 * {@link CommandLine#parse(String)} of what is shown is the array that was stored.
 */
public record ServiceView(
        UUID id,
        UUID projectId,
        String name,
        String slug,
        ServiceKind kind,
        DesiredState desiredState,
        RuntimeIsolation runtimeIsolation,
        String isolationReason,

        String image,
        String imageDigest,
        String command,
        String entrypoint,
        String workingDir,
        Integer containerPort,
        String healthCheckPath,
        int healthCheckIntervalSeconds,
        RestartPolicy restartPolicy,

        BuildPreset buildPreset,
        String buildCommand,
        String buildOutputDir,
        int keepReleases,

        String repositoryUrl,
        String repositoryBranch,
        boolean hasRepositoryCredential,
        boolean autoDeploy,

        long cpuMillicores,
        long memoryBytes,
        long diskBytes,
        int pidsLimit,

        List<String> requiredTags,
        Instant archivedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static ServiceView of(Service service) {
        return new ServiceView(service.id(), service.projectId(), service.name(), service.slug(),
                service.kind(), service.desiredState(), service.runtimeIsolation(),
                service.isolationReason(), service.image(), service.imageDigest(),
                CommandLine.format(service.command()), CommandLine.format(service.entrypoint()),
                service.workingDir(), service.containerPort(), service.healthCheckPath(),
                service.healthCheckIntervalSeconds(), service.restartPolicy(),
                service.buildPreset(), service.buildCommand(), service.buildOutputDir(),
                service.keepReleases(), service.repositoryUrl(), service.repositoryBranch(),
                service.repositoryCredential() != null, service.autoDeploy(),
                service.cpuMillicores(), service.memoryBytes(), service.diskBytes(),
                service.pidsLimit(),
                service.requiredTags() == null ? List.of() : List.of(service.requiredTags()),
                service.archivedAt(), service.createdAt(), service.updatedAt());
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public boolean isApp() {
        return kind == ServiceKind.APP;
    }

    public boolean isSite() {
        return kind == ServiceKind.SITE;
    }

    /** The warning the panel shows next to a service running without gVisor. */
    public String isolationWarning() {
        return runtimeIsolation.warning();
    }
}
