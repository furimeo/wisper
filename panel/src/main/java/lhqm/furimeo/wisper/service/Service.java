package lhqm.furimeo.wisper.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

/**
 * The deployable unit a customer owns: an app the platform runs, or a static site it
 * serves. Maps the {@code service} table, column for column.
 *
 * <p><strong>Every field here is intent.</strong> Nothing a node reports is written back
 * into this row - the running copy's facts live on {@code placement}, because they belong
 * to a workload on a machine and die with that binding. That split is the rule the
 * predecessor broke, and keeping it means "the customer stopped it" and "it crashed" never
 * overwrite each other.
 *
 * <p>The record is wide because the table is, and the table is wide because a service
 * genuinely has this many knobs. What keeps it from becoming a god object is that it has
 * no behaviour beyond the four transitions below and one overlay: everything that decides
 * something is a use-case in its own file.
 *
 * <p>The kind-specific columns are nullable and their rules are CHECK constraints
 * ({@code service_app_needs_image}, {@code service_site_needs_preset},
 * {@code service_site_has_no_container}). {@link CreateService} and
 * {@link UpdateServiceRuntime} check the same rules first, so a customer gets a sentence
 * naming the field instead of a constraint violation.
 *
 * @param webhookSecret an encrypted envelope, never plaintext. The panel needs to read it
 *                      back to verify an incoming Git signature, which is why it is
 *                      encrypted rather than hashed.
 * @param version       null means new; Spring Data JDBC needs this to tell an insert of an
 *                      application-assigned id from an update
 */
public record Service(
        @Id UUID id,
        UUID projectId,
        String name,
        String slug,
        ServiceKind kind,
        DesiredState desiredState,
        RuntimeIsolation runtimeIsolation,
        String isolationReason,

        String image,
        String imageDigest,
        String[] command,
        String[] entrypoint,
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
        String repositoryCredential,
        boolean autoDeploy,
        String webhookSecret,

        long cpuMillicores,
        long memoryBytes,
        long diskBytes,
        int pidsLimit,

        String[] requiredTags,
        Instant archivedAt,
        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt,
        @Version Long version) {

    /**
     * A new service, stopped, from a validated draft.
     *
     * <p>It starts {@link DesiredState#STOPPED} because there is nothing to run yet: an
     * app has no deployment and a site has no release. Creating a service that
     * immediately fails to start would be the panel reporting a problem it caused.
     *
     * @param webhookSecret an already-encrypted envelope
     */
    public static Service from(UUID id, UUID projectId, String slug, String webhookSecret,
                               ServiceDraft draft) {
        return new Service(id, projectId, draft.name(), slug, draft.kind(), DesiredState.STOPPED,
                draft.runtimeIsolation(), draft.isolationReason(),
                draft.image(), null, draft.commandArray(), draft.entrypointArray(),
                draft.workingDir(), draft.containerPort(), draft.healthCheckPath(),
                draft.healthCheckIntervalSeconds(), draft.restartPolicy(),
                draft.buildPreset(), draft.buildCommand(), draft.buildOutputDir(),
                draft.keepReleases(),
                draft.repositoryUrl(), draft.repositoryBranch(), draft.repositoryCredential(),
                draft.autoDeploy(), webhookSecret,
                draft.cpuMillicores(), draft.memoryBytes(), draft.diskBytes(), draft.pidsLimit(),
                draft.requiredTagsArray(), null, null, null, null);
    }

    /**
     * This service with the draft's settings laid over it.
     *
     * <p>Identity, kind, slug, the webhook secret, the pinned image digest, the desired
     * state and the archive flag are not the settings form's to change, so they survive
     * untouched. Changing a kind would mean changing which CHECK constraints apply to a
     * row that already has data under the old ones; changing a slug would break every URL
     * pointing at it.
     *
     * @param credentialEnvelope the encrypted repository credential to store, or the
     *                           existing one when the customer left the box empty
     */
    public Service applying(ServiceDraft draft, String credentialEnvelope) {
        return new Service(id, projectId, draft.name(), slug, kind, desiredState,
                draft.runtimeIsolation(), draft.isolationReason(),
                draft.image(), imageDigest, draft.commandArray(), draft.entrypointArray(),
                draft.workingDir(), draft.containerPort(), draft.healthCheckPath(),
                draft.healthCheckIntervalSeconds(), draft.restartPolicy(),
                draft.buildPreset(), draft.buildCommand(), draft.buildOutputDir(),
                draft.keepReleases(),
                draft.repositoryUrl(), draft.repositoryBranch(), credentialEnvelope,
                draft.autoDeploy(), webhookSecret,
                draft.cpuMillicores(), draft.memoryBytes(), draft.diskBytes(), draft.pidsLimit(),
                draft.requiredTagsArray(), archivedAt, createdAt, updatedAt, version);
    }

    /** What the customer wants it to be doing. */
    public Service desiring(DesiredState state) {
        return new Service(id, projectId, name, slug, kind, state, runtimeIsolation,
                isolationReason, image, imageDigest, command, entrypoint, workingDir,
                containerPort, healthCheckPath, healthCheckIntervalSeconds, restartPolicy,
                buildPreset, buildCommand, buildOutputDir, keepReleases, repositoryUrl,
                repositoryBranch, repositoryCredential, autoDeploy, webhookSecret,
                cpuMillicores, memoryBytes, diskBytes, pidsLimit, requiredTags, archivedAt,
                createdAt, updatedAt, version);
    }

    /**
     * Archived and stopped in one step.
     *
     * <p>The two go together and are not offered apart. An archived service that is still
     * in a node's spec is a service the customer believes is put away and is still paying
     * for.
     */
    public Service archived(Instant at) {
        return new Service(id, projectId, name, slug, kind, DesiredState.STOPPED,
                runtimeIsolation, isolationReason, image, imageDigest, command, entrypoint,
                workingDir, containerPort, healthCheckPath, healthCheckIntervalSeconds,
                restartPolicy, buildPreset, buildCommand, buildOutputDir, keepReleases,
                repositoryUrl, repositoryBranch, repositoryCredential, autoDeploy, webhookSecret,
                cpuMillicores, memoryBytes, diskBytes, pidsLimit, requiredTags, at, createdAt,
                updatedAt, version);
    }

    /**
     * A new webhook secret, already encrypted.
     *
     * <p>Nothing else moves. Rotation is what a customer reaches for when the old secret
     * has leaked, and it has to be safe to do in the middle of an incident: it must not
     * quietly stop the service, change its desired state, or disturb the deployment that
     * is running while they do it. The only visible consequence is that deliveries signed
     * with the old secret stop verifying, which is the point.
     *
     * @param envelope the encrypted secret to store, never plaintext
     */
    public Service rotatedTo(String envelope) {
        return new Service(id, projectId, name, slug, kind, desiredState, runtimeIsolation,
                isolationReason, image, imageDigest, command, entrypoint, workingDir,
                containerPort, healthCheckPath, healthCheckIntervalSeconds, restartPolicy,
                buildPreset, buildCommand, buildOutputDir, keepReleases, repositoryUrl,
                repositoryBranch, repositoryCredential, autoDeploy, envelope,
                cpuMillicores, memoryBytes, diskBytes, pidsLimit, requiredTags, archivedAt,
                createdAt, updatedAt, version);
    }

    /** Out of the archive, still stopped. */
    public Service restored() {
        return new Service(id, projectId, name, slug, kind, desiredState, runtimeIsolation,
                isolationReason, image, imageDigest, command, entrypoint, workingDir,
                containerPort, healthCheckPath, healthCheckIntervalSeconds, restartPolicy,
                buildPreset, buildCommand, buildOutputDir, keepReleases, repositoryUrl,
                repositoryBranch, repositoryCredential, autoDeploy, webhookSecret,
                cpuMillicores, memoryBytes, diskBytes, pidsLimit, requiredTags, null, createdAt,
                updatedAt, version);
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

    /** Whether a Git push can reach it: there is a repository and auto-deploy is on. */
    public boolean deploysFromGit() {
        return repositoryUrl != null && autoDeploy;
    }
}
