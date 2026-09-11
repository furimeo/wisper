package lhqm.furimeo.wisper.placement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import lhqm.furimeo.wisper.proto.v1.ContainerRuntime;
import lhqm.furimeo.wisper.proto.v1.DesiredState;
import lhqm.furimeo.wisper.proto.v1.EnvVar;
import lhqm.furimeo.wisper.proto.v1.MountKind;
import lhqm.furimeo.wisper.proto.v1.RestartPolicyMode;
import lhqm.furimeo.wisper.proto.v1.Workload;
import lhqm.furimeo.wisper.proto.v1.WorkloadKind;
import lhqm.furimeo.wisper.service.Service;

/** Turning rows into workloads: the units, the two kinds, and byte-for-byte repeatability. */
class BuildWorkloadsTest {

    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();

    private final BuildWorkloads workloads = new BuildWorkloads(PlacementFixture.settings());

    @Test
    void anAppCarriesItsImageArgvLimitsAndPort() {
        Service service = PlacementFixture.runningApp(UUID.randomUUID(), PROJECT);
        PlacedService placed = PlacementFixture.placed(service, NODE, PlacementState.ACTIVE,
                ORGANIZATION, "");

        Workload workload = only(workloads.from(List.of(placed), Map.of(), Map.of()));

        assertThat(workload.getId()).isEqualTo(service.id().toString());
        assertThat(workload.getKind()).isEqualTo(WorkloadKind.WORKLOAD_KIND_APP);
        assertThat(workload.getName()).isEqualTo("api");
        assertThat(workload.getImage()).isEqualTo("node:22");
        assertThat(workload.getImageDigest()).isEqualTo("sha256:cafe");
        assertThat(workload.getEntrypointList()).containsExactly("docker-entrypoint.sh");
        assertThat(workload.getCommandList()).containsExactly("node", "server.js");
        assertThat(workload.getWorkingDir()).isEqualTo("/srv");
        assertThat(workload.getDesiredState()).isEqualTo(DesiredState.DESIRED_STATE_RUNNING);
        assertThat(workload.getRuntime()).isEqualTo(ContainerRuntime.CONTAINER_RUNTIME_RUNSC);
        assertThat(workload.getRestart().getMode())
                .isEqualTo(RestartPolicyMode.RESTART_POLICY_MODE_ALWAYS);
        assertThat(workload.getTenantNetwork()).isEqualTo("wisper-tenant-" + ORGANIZATION);
        assertThat(workload.getStopGraceSeconds()).isEqualTo(30L);
        assertThat(workload.getPortsCount()).isEqualTo(1);
        assertThat(workload.getPorts(0).getContainerPort()).isEqualTo(8080);
        // Not published on the host: HTTP arrives through the node's own Caddy.
        assertThat(workload.getPorts(0).getHostPort()).isZero();
    }

    @Test
    void cpuIsNanoCpusAndSwapIsPinnedToMemory() {
        Service service = PlacementFixture.runningApp(UUID.randomUUID(), PROJECT);
        PlacedService placed = PlacementFixture.placed(service, NODE, PlacementState.ACTIVE,
                ORGANIZATION, "");

        Workload workload = only(workloads.from(List.of(placed), Map.of(), Map.of()));

        // 500 millicores is half a core, and a core is 1000000000 nano-CPUs. It is a hard
        // ceiling, not CPUShares * 1000.
        assertThat(workload.getLimits().getNanoCpus()).isEqualTo(500_000_000L);
        assertThat(workload.getLimits().getMemoryBytes()).isEqualTo(256 * PlacementFixture.MEGABYTE);
        assertThat(workload.getLimits().getMemorySwapBytes())
                .isEqualTo(workload.getLimits().getMemoryBytes());
        assertThat(workload.getLimits().getPidsLimit()).isEqualTo(256L);
        assertThat(workload.getLimits().getNofileLimit()).isEqualTo(65_536L);
    }

    @Test
    void aSiteHasNoImageNoPortAndOnlyADiskCeiling() {
        Service service = PlacementFixture.runningSite(UUID.randomUUID(), PROJECT);
        PlacedService placed = PlacementFixture.placed(service, NODE, PlacementState.ACTIVE,
                ORGANIZATION, "release-7");

        Workload workload = only(workloads.from(List.of(placed), Map.of(), Map.of()));

        assertThat(workload.getKind()).isEqualTo(WorkloadKind.WORKLOAD_KIND_SITE);
        assertThat(workload.getImage()).isEmpty();
        assertThat(workload.getPortsCount()).isZero();
        assertThat(workload.getLimits().getDiskBytes()).isEqualTo(PlacementFixture.GIGABYTE);
        assertThat(workload.getLimits().getMemoryBytes()).isZero();
        assertThat(workload.getReleaseId()).isEqualTo("release-7");
        assertThat(workload.hasSite()).isTrue();
        assertThat(workload.getSite().getDirectoryListing()).isFalse();
    }

    @Test
    void aDrainingBindingIsPublishedStoppedRatherThanLeftOut() {
        Service service = PlacementFixture.runningApp(UUID.randomUUID(), PROJECT);
        PlacedService placed = PlacementFixture.placed(service, NODE, PlacementState.DRAINING,
                ORGANIZATION, "");

        Workload workload = only(workloads.from(List.of(placed), Map.of(), Map.of()));

        // Out of the spec would mean "remove this", which takes the container and its logs
        // while the replacement is still coming up.
        assertThat(workload.getDesiredState()).isEqualTo(DesiredState.DESIRED_STATE_STOPPED);
    }

    @Test
    void volumesBecomeMountsThatCarryTheirOwnQuota() {
        Service service = PlacementFixture.runningApp(UUID.randomUUID(), PROJECT);
        UUID volumeId = UUID.randomUUID();
        MountedVolume data = new MountedVolume(volumeId, service.id(), "data", "/data",
                4 * PlacementFixture.GIGABYTE, false);
        PlacedService placed = PlacementFixture.placed(service, NODE, PlacementState.ACTIVE,
                ORGANIZATION, "");

        Workload workload = only(workloads.from(List.of(placed),
                Map.of(service.id(), List.of(data)), Map.of()));

        assertThat(workload.getMountsCount()).isEqualTo(1);
        assertThat(workload.getMounts(0).getVolumeId()).isEqualTo(volumeId.toString());
        assertThat(workload.getMounts(0).getKind()).isEqualTo(MountKind.MOUNT_KIND_VOLUME);
        assertThat(workload.getMounts(0).getTarget()).isEqualTo("/data");
        assertThat(workload.getMounts(0).getQuotaBytes()).isEqualTo(4 * PlacementFixture.GIGABYTE);
    }

    @Test
    void aHealthPathBecomesArgvAgainstTheContainersOwnPort() {
        Service service = PlacementFixture.runningApp(UUID.randomUUID(), PROJECT);
        PlacedService placed = PlacementFixture.placed(service, NODE, PlacementState.ACTIVE,
                ORGANIZATION, "");

        Workload workload = only(workloads.from(List.of(placed), Map.of(), Map.of()));

        assertThat(workload.hasHealthCheck()).isTrue();
        assertThat(workload.getHealthCheck().getTestList().getLast())
                .isEqualTo("http://127.0.0.1:8080/healthz");
        assertThat(workload.getHealthCheck().getIntervalSeconds()).isEqualTo(30L);
        assertThat(workload.getHealthCheck().getStartPeriodSeconds()).isEqualTo(20L);
    }

    @Test
    void aSiteGetsNoHealthCheckBecauseThereIsNoProcessToProbe() {
        Service service = PlacementFixture.runningSite(UUID.randomUUID(), PROJECT);
        PlacedService placed = PlacementFixture.placed(service, NODE, PlacementState.ACTIVE,
                ORGANIZATION, "");

        assertThat(only(workloads.from(List.of(placed), Map.of(), Map.of())).hasHealthCheck())
                .isFalse();
    }

    @Test
    void theSameRowsProduceTheSameBytesEveryTime() {
        Service app = PlacementFixture.runningApp(UUID.randomUUID(), PROJECT);
        Service site = PlacementFixture.runningSite(UUID.randomUUID(), PROJECT);
        List<PlacedService> placed = List.of(
                PlacementFixture.placed(app, NODE, PlacementState.ACTIVE, ORGANIZATION, ""),
                PlacementFixture.placed(site, NODE, PlacementState.ACTIVE, ORGANIZATION, "r1"));
        Map<UUID, List<MountedVolume>> volumes = Map.of(app.id(), List.of(
                new MountedVolume(UUID.randomUUID(), app.id(), "data", "/data",
                        PlacementFixture.GIGABYTE, false)));
        Map<UUID, List<EnvVar>> environment = Map.of(app.id(), List.of(
                EnvVar.newBuilder().setName("A").setValue("1").build(),
                EnvVar.newBuilder().setName("B").setValue("2").setSecret(true).build()));

        List<Workload> first = workloads.from(placed, volumes, environment);
        List<Workload> second = workloads.from(placed, volumes, environment);

        assertThat(bytesOf(second)).isEqualTo(bytesOf(first));
    }

    private static Workload only(List<Workload> built) {
        assertThat(built).hasSize(1);
        return built.getFirst();
    }

    private static byte[] bytesOf(List<Workload> built) {
        int size = 0;
        for (Workload workload : built) {
            size += workload.getSerializedSize();
        }
        byte[] all = new byte[size];
        int offset = 0;
        for (Workload workload : built) {
            byte[] one = workload.toByteArray();
            System.arraycopy(one, 0, all, offset, one.length);
            offset += one.length;
        }
        return all;
    }
}
