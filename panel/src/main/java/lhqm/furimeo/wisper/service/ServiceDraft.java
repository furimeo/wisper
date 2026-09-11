package lhqm.furimeo.wisper.service;

import java.util.List;

/**
 * Everything a customer can decide about a service, in one value.
 *
 * <p>The same shape creates a service and edits one, because the two forms ask the same
 * questions and the divergence checks - an app needs an image, a site must not have a
 * port - have to be identical on both paths. Two shapes would mean two sets of rules, and
 * the second set is the one nobody updates.
 *
 * <p>Defaults are applied here rather than left to the DDL. Spring Data JDBC writes every
 * mapped column on insert, nulls included, so a {@code DEFAULT 500} in the migration is
 * overwritten with NULL by the very first insert; the numbers below are the same ones the
 * migration declares, and they are what a form with an empty box means.
 *
 * <p>Blank text is null, everywhere. An empty input and an absent one are the same
 * intention, and storing {@code ""} in {@code image} would satisfy
 * {@code service_app_needs_image} while giving the node nothing to pull.
 *
 * @param repositoryCredential the deploy key or access token, <strong>in plaintext</strong>.
 *                             It is encrypted by the use-case, never by the caller. On an
 *                             edit, null means "leave the stored one alone": the form
 *                             cannot show a secret back, so an empty box has to mean
 *                             unchanged rather than erased.
 */
public record ServiceDraft(
        String name,
        String slug,
        ServiceKind kind,
        String image,
        List<String> command,
        List<String> entrypoint,
        String workingDir,
        Integer containerPort,
        String healthCheckPath,
        Integer healthCheckIntervalSeconds,
        RestartPolicy restartPolicy,
        BuildPreset buildPreset,
        String buildCommand,
        String buildOutputDir,
        Integer keepReleases,
        String repositoryUrl,
        String repositoryBranch,
        String repositoryCredential,
        boolean autoDeploy,
        Long cpuMillicores,
        Long memoryBytes,
        Long diskBytes,
        Integer pidsLimit,
        List<String> requiredTags,
        RuntimeIsolation runtimeIsolation,
        String isolationReason) {

    /** Half a core. Enough for a small web app, and the same figure the migration uses. */
    public static final long DEFAULT_CPU_MILLICORES = 500;

    /** 256 MiB. */
    public static final long DEFAULT_MEMORY_BYTES = 268_435_456L;

    /** 1 GiB. */
    public static final long DEFAULT_DISK_BYTES = 1_073_741_824L;

    /** Enough for a runtime and its workers, few enough to stop a fork bomb. */
    public static final int DEFAULT_PIDS_LIMIT = 256;

    /** Releases kept on disk for an instant rollback (design §5.5). */
    public static final int DEFAULT_KEEP_RELEASES = 5;

    /** Seconds between health probes. */
    public static final int DEFAULT_HEALTH_INTERVAL_SECONDS = 30;

    public ServiceDraft {
        name = blankToNull(name);
        slug = blankToNull(slug);
        image = blankToNull(image);
        workingDir = blankToNull(workingDir);
        healthCheckPath = blankToNull(healthCheckPath);
        buildCommand = blankToNull(buildCommand);
        buildOutputDir = blankToNull(buildOutputDir);
        repositoryUrl = blankToNull(repositoryUrl);
        repositoryBranch = blankToNull(repositoryBranch);
        repositoryCredential = blankToNull(repositoryCredential);
        isolationReason = blankToNull(isolationReason);

        command = command == null ? List.of() : List.copyOf(command);
        entrypoint = entrypoint == null ? List.of() : List.copyOf(entrypoint);
        requiredTags = requiredTags == null ? List.of() : List.copyOf(requiredTags);

        restartPolicy = restartPolicy == null ? RestartPolicy.ALWAYS : restartPolicy;
        runtimeIsolation = runtimeIsolation == null ? RuntimeIsolation.RUNSC : runtimeIsolation;
        healthCheckIntervalSeconds = healthCheckIntervalSeconds == null
                ? DEFAULT_HEALTH_INTERVAL_SECONDS : healthCheckIntervalSeconds;
        keepReleases = keepReleases == null ? DEFAULT_KEEP_RELEASES : keepReleases;
        cpuMillicores = cpuMillicores == null ? DEFAULT_CPU_MILLICORES : cpuMillicores;
        memoryBytes = memoryBytes == null ? DEFAULT_MEMORY_BYTES : memoryBytes;
        diskBytes = diskBytes == null ? DEFAULT_DISK_BYTES : diskBytes;
        pidsLimit = pidsLimit == null ? DEFAULT_PIDS_LIMIT : pidsLimit;
    }

    /**
     * An empty draft, which is every default and nothing else.
     *
     * <p>The new-service form renders from this, so what the customer sees in a box and
     * what an untouched box produces are the same number by construction.
     */
    public static ServiceDraft defaults() {
        return new ServiceDraft(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, true, null, null, null, null,
                null, null, null);
    }

    /** The command as an array, which is what the {@code text[]} column takes. */
    public String[] commandArray() {
        return command.toArray(String[]::new);
    }

    /** The entrypoint as an array. */
    public String[] entrypointArray() {
        return entrypoint.toArray(String[]::new);
    }

    /** The placement tags as an array. */
    public String[] requiredTagsArray() {
        return requiredTags.toArray(String[]::new);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
