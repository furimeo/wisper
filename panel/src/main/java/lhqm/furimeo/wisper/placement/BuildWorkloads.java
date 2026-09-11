package lhqm.furimeo.wisper.placement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.proto.v1.ContainerRuntime;
import lhqm.furimeo.wisper.proto.v1.EnvVar;
import lhqm.furimeo.wisper.proto.v1.HealthCheck;
import lhqm.furimeo.wisper.proto.v1.Mount;
import lhqm.furimeo.wisper.proto.v1.MountKind;
import lhqm.furimeo.wisper.proto.v1.PortBinding;
import lhqm.furimeo.wisper.proto.v1.PortProtocol;
import lhqm.furimeo.wisper.proto.v1.ResourceLimits;
import lhqm.furimeo.wisper.proto.v1.RestartPolicy;
import lhqm.furimeo.wisper.proto.v1.RestartPolicyMode;
import lhqm.furimeo.wisper.proto.v1.SiteOptions;
import lhqm.furimeo.wisper.proto.v1.Workload;
import lhqm.furimeo.wisper.proto.v1.WorkloadKind;
import lhqm.furimeo.wisper.service.Service;

/**
 * Turns placed services into the {@code workloads} half of a {@code NodeSpec}.
 *
 * <p>Pure: rows in, protobuf out, no database. That is what lets a test assert the one
 * property this has to have - the same rows produce the same bytes - without a PostgreSQL
 * behind it. Everything that could vary run to run is fixed here: the list is in the order
 * it arrived (service id), mounts are in mount-path order, and environment entries are
 * sorted by name by the loader.
 *
 * <p>A site is a workload too, with no image, no limits worth setting except a disk quota
 * and no process at all. Being in the spec is what makes the node's Caddy serve its current
 * release; leaving it out is how a site goes offline without anything being deleted
 * (design §5.5).
 */
@Component
public class BuildWorkloads {

    /**
     * How many times a failing container is brought back before the node stops and reports
     * it as crash-looping. Beyond this the customer is told, instead of being left with a
     * service that restarts forever and never works.
     */
    private static final int ON_FAILURE_RETRIES = 5;

    private final PlacementSettings settings;

    public BuildWorkloads(PlacementSettings settings) {
        this.settings = settings;
    }

    /**
     * One {@link Workload} per placed service, in the order given.
     *
     * @param volumes    storage per service id, from {@link LoadServiceVolumes}
     * @param environment variables per service id, already sorted and unsealed
     */
    public List<Workload> from(List<PlacedService> placed,
                               Map<UUID, List<MountedVolume>> volumes,
                               Map<UUID, List<EnvVar>> environment) {
        List<Workload> workloads = new ArrayList<>(placed.size());
        for (PlacedService entry : placed) {
            Service service = entry.service();
            List<MountedVolume> storage = volumes.getOrDefault(service.id(), List.of());
            Workload.Builder workload = Workload.newBuilder()
                    .setId(entry.workloadId())
                    .setKind(entry.isApp() ? WorkloadKind.WORKLOAD_KIND_APP
                            : WorkloadKind.WORKLOAD_KIND_SITE)
                    .setName(service.slug())
                    .setDesiredState(entry.desiredState())
                    .setRuntime(runtimeOf(service))
                    .setLimits(limitsOf(service, entry.isApp()))
                    .addAllEnv(environment.getOrDefault(service.id(), List.of()))
                    .setReleaseId(entry.releaseId());

            if (entry.isApp()) {
                appendApp(workload, entry, storage);
            } else {
                workload.setSite(siteOptions());
            }
            workloads.add(workload.build());
        }
        return List.copyOf(workloads);
    }

    /** Everything only a container needs: an image, argv, mounts, a port and a restart rule. */
    private void appendApp(Workload.Builder workload, PlacedService entry,
                           List<MountedVolume> storage) {
        Service service = entry.service();
        workload.setImage(nullToEmpty(service.image()))
                .setImageDigest(entry.imageDigest())
                .addAllEntrypoint(argv(service.entrypoint()))
                .addAllCommand(argv(service.command()))
                .setWorkingDir(nullToEmpty(service.workingDir()))
                .addAllMounts(mountsOf(storage))
                .setRestart(restartOf(service))
                .setStopGraceSeconds(settings.stopGrace().toSeconds())
                .setTenantNetwork(settings.tenantNetworkFor(entry.organizationId()));
        if (service.containerPort() != null) {
            // host_port stays 0: HTTP arrives through the node's Caddy over the tenant
            // network, so publishing on the host would put a customer's application on the
            // public interface without anybody asking for it. The binding is here so the
            // node knows which port to send a route to.
            workload.addPorts(PortBinding.newBuilder()
                    .setContainerPort(service.containerPort())
                    .setHostPort(0)
                    .setProtocol(PortProtocol.PORT_PROTOCOL_TCP)
                    .build());
        }
        healthCheckOf(service).ifPresent(workload::setHealthCheck);
    }

    /**
     * The ceilings, in the units the wire uses.
     *
     * <p>{@code nano_cpus} is millicores times a million: one full core is
     * {@code 1000000000}. It is a hard ceiling and <strong>not</strong> {@code CPUShares}
     * multiplied by a thousand, which is a relative scheduling weight. Conflating the two
     * is what let one busy workload take a whole machine in the predecessor while the panel
     * showed it politely limited (design §9).
     *
     * <p>A site gets a disk figure and nothing else. There is no process to limit, and
     * sending a memory ceiling for one would suggest the node should enforce something
     * about a directory of files.
     *
     * <p>{@code nofile_limit} comes from {@code wisper.placement.nofile-limit} rather than
     * from a constant here. The number that ships is the same 65536 either way; what a
     * setting buys is that an operator who raises it sees it change, instead of editing a
     * key the spec builder never reads.
     */
    private ResourceLimits limitsOf(Service service, boolean isApp) {
        ResourceLimits.Builder limits = ResourceLimits.newBuilder()
                .setDiskBytes(service.diskBytes());
        if (!isApp) {
            return limits.build();
        }
        return limits
                .setNanoCpus(service.cpuMillicores() * 1_000_000L)
                .setMemoryBytes(service.memoryBytes())
                // Equal to memory disables swap. A container that swaps makes the whole
                // machine grind; one that is killed fails a single workload.
                .setMemorySwapBytes(service.memoryBytes())
                .setPidsLimit(service.pidsLimit())
                .setNofileLimit(settings.nofileLimit())
                .build();
    }

    private static List<Mount> mountsOf(List<MountedVolume> storage) {
        List<Mount> mounts = new ArrayList<>(storage.size());
        for (MountedVolume volume : storage) {
            mounts.add(Mount.newBuilder()
                    .setVolumeId(volume.rootId())
                    .setKind(MountKind.MOUNT_KIND_VOLUME)
                    .setTarget(volume.mountPath())
                    .setReadOnly(volume.readOnly())
                    .setQuotaBytes(volume.quotaBytes())
                    .build());
        }
        return mounts;
    }

    private static RestartPolicy restartOf(Service service) {
        RestartPolicyMode mode = switch (service.restartPolicy()) {
            case ALWAYS -> RestartPolicyMode.RESTART_POLICY_MODE_ALWAYS;
            case ON_FAILURE -> RestartPolicyMode.RESTART_POLICY_MODE_ON_FAILURE;
            case NEVER -> RestartPolicyMode.RESTART_POLICY_MODE_NEVER;
        };
        RestartPolicy.Builder policy = RestartPolicy.newBuilder().setMode(mode);
        if (mode == RestartPolicyMode.RESTART_POLICY_MODE_ON_FAILURE) {
            policy.setMaxRetries(ON_FAILURE_RETRIES);
        }
        return policy.build();
    }

    private static ContainerRuntime runtimeOf(Service service) {
        return switch (service.runtimeIsolation()) {
            case RUNSC -> ContainerRuntime.CONTAINER_RUNTIME_RUNSC;
            case RUNC -> ContainerRuntime.CONTAINER_RUNTIME_RUNC;
        };
    }

    /**
     * The customer's health check, as an argument vector.
     *
     * <p>The schema stores an HTTP path, because that is the only kind of check a customer
     * can describe without knowing what is inside their image. Turning it into argv is this
     * package's job, and the argv is plain: {@code HealthCheck.test} is "argv run inside the
     * container", so the node adds Docker's own {@code CMD} marker and this does not.
     *
     * <p>Only produced when the customer typed a path <em>and</em> the service has a port to
     * probe. A check nobody asked for that fails because the image has no {@code wget}
     * would mark a perfectly healthy service unhealthy, which is worse than no check.
     */
    private Optional<HealthCheck> healthCheckOf(Service service) {
        String path = service.healthCheckPath();
        if (path == null || path.isBlank() || service.containerPort() == null) {
            return Optional.empty();
        }
        String url = "http://127.0.0.1:" + service.containerPort()
                + (path.startsWith("/") ? path : "/" + path);
        return Optional.of(HealthCheck.newBuilder()
                .addAllTest(List.of("wget", "--quiet", "--tries=1", "--spider", url))
                .setIntervalSeconds(service.healthCheckIntervalSeconds())
                .setTimeoutSeconds(settings.healthCheckTimeout().toSeconds())
                .setRetries(settings.healthCheckRetries())
                .setStartPeriodSeconds(settings.healthCheckStartPeriod().toSeconds())
                .build());
    }

    /**
     * How the edge serves a static site.
     *
     * <p>Every value is the safe default, because the schema has no columns for these yet:
     * no SPA fallback, the node's own {@code index.html}, and directory listing off. An
     * accidental directory listing is a data leak, so that one is not a default anybody
     * gets by omission - it is a default this file states out loud.
     */
    private static SiteOptions siteOptions() {
        return SiteOptions.newBuilder()
                .setSpaFallback(false)
                .setDirectoryListing(false)
                .build();
    }

    private static List<String> argv(String[] values) {
        return values == null ? List.of() : Arrays.asList(values);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
