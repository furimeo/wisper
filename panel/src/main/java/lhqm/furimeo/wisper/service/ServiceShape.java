package lhqm.furimeo.wisper.service;

import java.util.Locale;

import lhqm.furimeo.wisper.org.RequestRejected;

/**
 * The rules that decide whether a draft describes a service that can exist, and the
 * single place the app/site divergence is written down.
 *
 * <p>Three CHECK constraints in V14 say the same things - {@code service_app_needs_image},
 * {@code service_site_needs_preset}, {@code service_site_has_no_container} - and the
 * database is the authority. This class exists so that a customer gets a sentence naming
 * the input they got wrong instead of a constraint violation naming a constraint, and so
 * that the two write paths into this table, the form and a future API route, cannot
 * enforce two different sets of rules.
 *
 * <p>It also fills in what can be inferred: a site with a preset and no output directory
 * gets the preset's usual one, so the common case is one decision instead of two.
 *
 * <p>Everything here throws {@link RequestRejected} with the {@code name} attribute of the
 * offending input, which is what puts the message under the right box rather than in a
 * banner above a form the customer then has to re-read.
 */
public final class ServiceShape {

    /** Longest a container port may be. Mirrors {@code service_container_port_range}. */
    private static final int MAX_PORT = 65_535;

    /** 64 GiB. Beyond this a typo has been made; the quota decides what is affordable. */
    private static final long MAX_MEMORY_BYTES = 68_719_476_736L;

    /** 64 cores. */
    private static final long MAX_CPU_MILLICORES = 64_000L;

    /** 2 TiB. */
    private static final long MAX_DISK_BYTES = 2_199_023_255_552L;

    private ServiceShape() {
    }

    /**
     * Checks a draft and fills in what follows from it.
     *
     * @return the draft to store, which may differ from the one submitted where a default
     *         was inferred
     * @throws RequestRejected naming the field that is wrong
     */
    public static ServiceDraft validated(ServiceDraft draft) {
        if (draft.kind() == null) {
            throw new RequestRejected("kind", "Choose an app or a static site.");
        }
        if (draft.name() == null) {
            throw new RequestRejected("name", "Give the service a name.");
        }
        checkLimits(draft);
        checkIsolation(draft);
        checkRepository(draft);

        return draft.kind() == ServiceKind.APP ? validatedApp(draft) : validatedSite(draft);
    }

    /**
     * An app runs a container, so it needs an image and may have everything a container
     * has.
     */
    private static ServiceDraft validatedApp(ServiceDraft draft) {
        if (draft.image() == null) {
            throw new RequestRejected("image",
                    "An app needs a container image, for example ghcr.io/acme/api:1.4.");
        }
        if (draft.containerPort() != null
                && (draft.containerPort() < 1 || draft.containerPort() > MAX_PORT)) {
            throw new RequestRejected("containerPort", "A port is between 1 and " + MAX_PORT + ".");
        }
        if (draft.healthCheckPath() != null && !draft.healthCheckPath().startsWith("/")) {
            throw new RequestRejected("healthCheckPath",
                    "A health check path starts with a slash, for example /healthz.");
        }
        if (draft.healthCheckPath() != null && draft.containerPort() == null) {
            throw new RequestRejected("containerPort",
                    "A health check needs a port to probe. Set the port the app listens on.");
        }
        if (draft.workingDir() != null && !draft.workingDir().startsWith("/")) {
            throw new RequestRejected("workingDir",
                    "The working directory is a path inside the container, so it starts with a "
                            + "slash.");
        }
        // An app may still be built from a repository - that is how a language runtime
        // image gets the customer's code - so the build fields are left as submitted, with
        // the same output-directory rules a site gets.
        return draft.buildPreset() == null ? draft : withOutputDir(draft, checkedOutputDir(draft));
    }

    /**
     * A site is files on disk. It has no image, no port and no argv, because there is
     * nothing to exec; what it has is a build that produces a directory.
     */
    private static ServiceDraft validatedSite(ServiceDraft draft) {
        if (draft.image() != null) {
            throw new RequestRejected("image",
                    "A static site has no container, so it has no image. Pick an app if you need "
                            + "one running.");
        }
        if (draft.containerPort() != null) {
            throw new RequestRejected("containerPort",
                    "A static site has no process listening on a port; the node serves its files "
                            + "directly.");
        }
        if (!draft.command().isEmpty() || !draft.entrypoint().isEmpty()) {
            throw new RequestRejected("command",
                    "A static site runs nothing. Put what builds it in the build command.");
        }
        if (draft.healthCheckPath() != null) {
            throw new RequestRejected("healthCheckPath",
                    "There is no process to health-check on a static site.");
        }
        if (draft.buildPreset() == null) {
            throw new RequestRejected("buildPreset", "Choose how the site is built.");
        }
        if (draft.buildPreset().needsCommand() && draft.buildCommand() == null) {
            throw new RequestRejected("buildCommand",
                    "A custom build needs the command that produces the site.");
        }
        if (draft.keepReleases() < 1 || draft.keepReleases() > 50) {
            throw new RequestRejected("keepReleases",
                    "Keep between 1 and 50 releases on disk for rollback.");
        }
        return withOutputDir(draft, checkedOutputDir(draft));
    }

    /**
     * The directory the build leaves the site in, relative to the checkout.
     *
     * <p>Relative, and checked for {@code ..}: this string is handed to a node, which
     * resolves it under the build's own output tree. A path that can climb out of it is
     * the same class of bug as a file-manager traversal, and the cheapest place to refuse
     * it is here.
     */
    private static String checkedOutputDir(ServiceDraft draft) {
        String submitted = draft.buildOutputDir();
        String directory = submitted != null ? submitted : draft.buildPreset().defaultOutputDir();
        if (directory == null || directory.isBlank()) {
            throw new RequestRejected("buildOutputDir",
                    "Say which directory the build leaves the site in, for example dist.");
        }
        String tidy = directory.strip().replace('\\', '/');
        while (tidy.startsWith("./")) {
            tidy = tidy.substring(2);
        }
        while (tidy.endsWith("/")) {
            tidy = tidy.substring(0, tidy.length() - 1);
        }
        if (tidy.isEmpty()) {
            tidy = ".";
        }
        if (tidy.startsWith("/") || tidy.contains("..")) {
            throw new RequestRejected("buildOutputDir",
                    "The output directory is inside the repository, so it cannot start with a "
                            + "slash or contain \"..\".");
        }
        return tidy;
    }

    private static void checkLimits(ServiceDraft draft) {
        require(draft.cpuMillicores() > 0 && draft.cpuMillicores() <= MAX_CPU_MILLICORES,
                "cpuMillicores", "CPU is between 1 and " + MAX_CPU_MILLICORES + " millicores; "
                        + "1000 is one core.");
        require(draft.memoryBytes() > 0 && draft.memoryBytes() <= MAX_MEMORY_BYTES,
                "memoryBytes", "Memory is between 1 byte and 64 GiB.");
        require(draft.diskBytes() > 0 && draft.diskBytes() <= MAX_DISK_BYTES,
                "diskBytes", "Disk is between 1 byte and 2 TiB.");
        require(draft.pidsLimit() > 0 && draft.pidsLimit() <= 32_768,
                "pidsLimit", "The process limit is between 1 and 32768.");
        require(draft.healthCheckIntervalSeconds() > 0
                        && draft.healthCheckIntervalSeconds() <= 3_600,
                "healthCheckIntervalSeconds", "Check between every second and every hour.");
    }

    private static void checkIsolation(ServiceDraft draft) {
        if (draft.runtimeIsolation().needsReason() && draft.isolationReason() == null) {
            throw new RequestRejected("isolationReason",
                    "Choosing gVisor needs a reason, and the panel shows it next to the "
                            + "service. Say why this workload needs userspace syscall filtering.");
        }
    }

    /**
     * What a node is allowed to be told to clone.
     *
     * <p>An allow-list of schemes, because the alternative is a node running
     * {@code git clone} against {@code file:///etc} or a helper URL of somebody else's
     * choosing. HTTPS, SSH and the scp-like form people paste out of a Git host cover
     * every real case.
     */
    private static void checkRepository(ServiceDraft draft) {
        String url = draft.repositoryUrl();
        if (url == null) {
            if (draft.repositoryCredential() != null) {
                throw new RequestRejected("repositoryUrl",
                        "A credential with no repository to use it on. Add the repository, or "
                                + "clear the credential.");
            }
            return;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        boolean allowed = lower.startsWith("https://")
                || lower.startsWith("http://")
                || lower.startsWith("ssh://")
                || lower.matches("^[a-z0-9._~-]+@[a-z0-9.-]+:.+$");
        if (!allowed) {
            throw new RequestRejected("repositoryUrl",
                    "Use an https:// or ssh:// URL, or the git@host:owner/repo form.");
        }
        if (draft.repositoryBranch() != null && draft.repositoryBranch().contains(" ")) {
            throw new RequestRejected("repositoryBranch", "A branch name has no spaces in it.");
        }
    }

    private static ServiceDraft withOutputDir(ServiceDraft draft, String outputDir) {
        return new ServiceDraft(draft.name(), draft.slug(), draft.kind(), draft.image(),
                draft.command(), draft.entrypoint(), draft.workingDir(), draft.containerPort(),
                draft.healthCheckPath(), draft.healthCheckIntervalSeconds(),
                draft.restartPolicy(), draft.buildPreset(), draft.buildCommand(), outputDir,
                draft.keepReleases(), draft.repositoryUrl(), draft.repositoryBranch(),
                draft.repositoryCredential(), draft.autoDeploy(), draft.cpuMillicores(),
                draft.memoryBytes(), draft.diskBytes(), draft.pidsLimit(), draft.requiredTags(),
                draft.runtimeIsolation(), draft.isolationReason());
    }

    /**
     * The draft with its repository credential replaced by an envelope, or by the one
     * already stored when the customer left the box empty.
     *
     * <p>Here rather than in the use-case because both write paths need the same
     * three-way choice - new value, keep existing, or none - and getting it wrong in one
     * of them either wipes a working deploy key or writes plaintext into an encrypted
     * column.
     */
    public static ServiceDraft withCredential(ServiceDraft draft, String envelope) {
        return new ServiceDraft(draft.name(), draft.slug(), draft.kind(), draft.image(),
                draft.command(), draft.entrypoint(), draft.workingDir(), draft.containerPort(),
                draft.healthCheckPath(), draft.healthCheckIntervalSeconds(),
                draft.restartPolicy(), draft.buildPreset(), draft.buildCommand(),
                draft.buildOutputDir(), draft.keepReleases(), draft.repositoryUrl(),
                draft.repositoryBranch(), envelope, draft.autoDeploy(), draft.cpuMillicores(),
                draft.memoryBytes(), draft.diskBytes(), draft.pidsLimit(), draft.requiredTags(),
                draft.runtimeIsolation(), draft.isolationReason());
    }

    private static void require(boolean condition, String field, String message) {
        if (!condition) {
            throw new RequestRejected(field, message);
        }
    }
}
