package lhqm.furimeo.wisper.deploy;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.crypto.SecretCipher;
import lhqm.furimeo.wisper.proto.v1.ArchiveSource;
import lhqm.furimeo.wisper.proto.v1.BuildOutputKind;
import lhqm.furimeo.wisper.proto.v1.BuildSource;
import lhqm.furimeo.wisper.proto.v1.GitSource;
import lhqm.furimeo.wisper.proto.v1.ResourceLimits;
import lhqm.furimeo.wisper.proto.v1.StartBuild;
import lhqm.furimeo.wisper.service.Service;

/**
 * Assembles the one message that tells a node what to build.
 *
 * <p>Separate from {@link BuildArtifact} because the two answer different questions.
 * {@code BuildArtifact} decides <em>whether and where</em>; this decides <em>what</em>,
 * and it is the part with a dozen fields, four sources of truth and a set of resource
 * floors that need explaining.
 *
 * <h2>Ids</h2>
 *
 * <p>{@code build_id}, the release id and the log stream id are all the deployment id.
 * build.proto says the first two are the same thing on purpose - "one deployment produces
 * one release, and two names for the same thing is one join nobody needs" - and making
 * the log stream the same again means the customer's browser is already watching the
 * right stream before the first line exists.
 */
@Component
public class ComposeStartBuild {

    /**
     * A build gets at least one whole core.
     *
     * <p>A site's runtime limit is meaningless here - a static site has no process at all,
     * so its {@code cpu_millicores} describes nothing - and a webpack build throttled to
     * a tenth of a core takes twenty minutes and then hits the timeout.
     */
    private static final long MINIMUM_MILLICORES = 1_000;

    /** Below a gigabyte, a Node build is killed by the OOM killer rather than finishing. */
    private static final long MINIMUM_MEMORY_BYTES = 1024L * 1024 * 1024;

    /** {@code node_modules} on a real project is several gigabytes before anything is built. */
    private static final long MINIMUM_DISK_BYTES = 4L * 1024 * 1024 * 1024;

    /** Package managers open a lot of files at once; the default limit looks like a bug. */
    private static final long NOFILE_LIMIT = 65_536;

    /** Enough processes for a package manager's worker pool, few enough to stop a fork bomb. */
    private static final long MINIMUM_PIDS = 512;

    /** History is not needed to build a commit, and a shallow clone turns minutes into seconds. */
    private static final int CLONE_DEPTH = 1;

    private final ResolveBuildPlan plans;
    private final CollectBuildEnvironment environment;
    private final SecretCipher cipher;
    private final DeploySettings settings;

    public ComposeStartBuild(ResolveBuildPlan plans, CollectBuildEnvironment environment,
                             SecretCipher cipher, DeploySettings settings) {
        this.plans = plans;
        this.environment = environment;
        this.cipher = cipher;
        this.settings = settings;
    }

    /**
     * The command to put on the node's control stream.
     *
     * @param workloadId  the id the node knows this service's workload by, which is what
     *                    a release directory hangs off
     * @param uploadedSha the checksum of the archive already pushed into the node's
     *                    staging root, or null for a Git build
     * @param useCache    false re-fetches everything. See {@link BuildArtifact} for when
     *                    that is chosen and why it is not a button
     */
    public StartBuild forDeployment(Deployment deployment, Service service, String workloadId,
                                    String uploadedSha, boolean useCache) {
        return StartBuild.newBuilder()
                .setBuildId(deployment.id().toString())
                .setWorkloadId(workloadId)
                .setSource(sourceOf(deployment, service, uploadedSha))
                .setPlan(plans.forService(service))
                // v1 builds only static releases: no build_preset in the schema produces
                // a container image, and service_app_needs_image means an app already has
                // one somebody else built.
                .setOutput(BuildOutputKind.BUILD_OUTPUT_KIND_STATIC_RELEASE)
                .addAllBuildEnv(environment.forService(service.id()))
                .setLimits(limitsFor(service))
                .setTimeoutSeconds(settings.buildTimeoutSeconds())
                .setLogStreamId(deployment.id().toString())
                .setUseCache(useCache)
                .build();
    }

    private BuildSource sourceOf(Deployment deployment, Service service, String uploadedSha) {
        if (deployment.source() == DeploymentSource.ARCHIVE) {
            return BuildSource.newBuilder()
                    .setArchive(ArchiveSource.newBuilder()
                            .setUploadSessionId(deployment.id().toString())
                            .setSha256(uploadedSha == null ? "" : uploadedSha))
                    .build();
        }
        GitSource.Builder git = GitSource.newBuilder()
                .setRepositoryUrl(service.repositoryUrl() == null ? "" : service.repositoryUrl())
                .setRef(deployment.gitRef() == null ? "" : deployment.gitRef())
                .setDepth(CLONE_DEPTH)
                .setSubmodules(false);
        if (deployment.commitSha() != null) {
            // Pinned, so the build is of what triggered it rather than of whatever the
            // branch moved to while the job sat in the queue.
            git.setCommit(deployment.commitSha());
        }
        if (service.repositoryCredential() != null) {
            // Decrypted here and sent for this one build. The node holds no credential it
            // was not handed for a single job and never writes this to disk.
            git.setAccessToken(cipher.decrypt(service.repositoryCredential()));
        }
        return BuildSource.newBuilder().setGit(git).build();
    }

    /**
     * The ceiling the build container runs under.
     *
     * <p>A build with no limit takes the node down with it, and it is the neighbours who
     * notice. The service's own figures are the starting point, raised to the floors
     * above: they describe what the finished thing needs to run, and a build needs more
     * of everything than the site it produces - which needs none of it at all.
     */
    private static ResourceLimits limitsFor(Service service) {
        long millicores = Math.max(service.cpuMillicores(), MINIMUM_MILLICORES);
        long memory = Math.max(service.memoryBytes(), MINIMUM_MEMORY_BYTES);
        return ResourceLimits.newBuilder()
                // Docker's NanoCPUs: a hard ceiling, not CPUShares * 1000. One full core
                // is 1000000000, so a millicore is a million of them.
                .setNanoCpus(millicores * 1_000_000L)
                .setMemoryBytes(memory)
                // Equal to memory disables swap. A build that swaps makes the whole
                // machine grind; a build that is killed fails one deployment.
                .setMemorySwapBytes(memory)
                .setPidsLimit(Math.max(service.pidsLimit(), MINIMUM_PIDS))
                .setDiskBytes(Math.max(service.diskBytes(), MINIMUM_DISK_BYTES))
                .setNofileLimit(NOFILE_LIMIT)
                .build();
    }
}
