package lhqm.furimeo.wisper.placement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import lhqm.furimeo.wisper.proto.v1.FileRoot;
import lhqm.furimeo.wisper.proto.v1.FileRootKind;
import lhqm.furimeo.wisper.service.Service;

/**
 * The entire surface a customer has on a node's filesystem (node-spec.md §3.10).
 *
 * <p>There is no SSH, no SFTP and no WebDAV, so a directory that is missing from this list
 * is a directory nobody can reach and a directory that should not be on it is the whole
 * attack surface of v1. Both failures are silent, which is why the list is asserted here
 * rather than left to the one place it is assembled.
 */
class BuildFileRootsTest {

    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();
    private static final UUID ORGANIZATION = UUID.randomUUID();

    private final BuildFileRoots roots = new BuildFileRoots(PlacementFixture.settings());

    private final Service app = PlacementFixture.runningApp(UUID.randomUUID(), PROJECT);
    private final Service site = PlacementFixture.runningSite(UUID.randomUUID(), PROJECT);

    @Test
    void aNodeHoldingNothingStillGetsTheStagingRoot() {
        // A spec is published when a service is placed and deploy pushes the archive
        // immediately afterwards, so a staging directory that only appeared once there was
        // something to build would not be there when the first build needs it.
        List<FileRoot> only = roots.from(List.of(), Map.of());

        assertThat(only).hasSize(1);
        assertThat(only.getFirst().getKind()).isEqualTo(FileRootKind.FILE_ROOT_KIND_UPLOAD_STAGING);
        assertThat(only.getFirst().getId()).isEqualTo("upload-staging");
        assertThat(only.getFirst().getWritable()).isTrue();
    }

    @Test
    void aVolumeBecomesAWritableRootLabelledForTheBreadcrumb() {
        UUID volumeId = UUID.randomUUID();
        List<FileRoot> all = roots.from(placed(app), Map.of(app.id(), List.of(
                new MountedVolume(volumeId, app.id(), "data", "/data",
                        4 * PlacementFixture.GIGABYTE, false))));

        FileRoot volume = all.get(1);
        assertThat(volume.getKind()).isEqualTo(FileRootKind.FILE_ROOT_KIND_VOLUME);
        assertThat(volume.getId()).isEqualTo(volumeId.toString());
        assertThat(volume.getVolumeId()).isEqualTo(volumeId.toString());
        assertThat(volume.getWorkloadId()).isEqualTo(app.id().toString());
        assertThat(volume.getLabel()).isEqualTo("api / data");
        assertThat(volume.getWritable()).isTrue();
        assertThat(volume.getQuotaBytes()).isEqualTo(4 * PlacementFixture.GIGABYTE);
    }

    @Test
    void aReadOnlyVolumeIsAReadOnlyRoot() {
        assertThat(roots.from(placed(app), Map.of(app.id(), List.of(
                        new MountedVolume(UUID.randomUUID(), app.id(), "assets", "/assets",
                                PlacementFixture.GIGABYTE, true))))
                .get(1).getWritable()).isFalse();
    }

    @Test
    void aSitesReleasesTreeIsAReadOnlyRoot() {
        FileRoot releases = roots.from(placed(site), Map.of()).get(1);

        assertThat(releases.getKind()).isEqualTo(FileRootKind.FILE_ROOT_KIND_SITE);
        assertThat(releases.getId()).isEqualTo(site.id().toString());
        assertThat(releases.getLabel()).isEqualTo("docs releases");
        assertThat(releases.getWritable()).isFalse();
        assertThat(releases.getQuotaBytes()).isEqualTo(site.diskBytes());
    }

    @Test
    void anAppHasNoReleasesRootAndASiteHasNoVolumeRoot() {
        assertThat(roots.from(placed(app), Map.of())).hasSize(1);
        assertThat(roots.from(placed(site), Map.of())).hasSize(2);
    }

    @Test
    void theOrderIsStagingThenEachServicesVolumesByMountPath() {
        // The order is part of the contract: the node hashes the encoded spec to decide
        // whether anything changed, so a list that came back shuffled would make every
        // reconcile pass look like drift.
        List<MountedVolume> storage = List.of(
                new MountedVolume(UUID.randomUUID(), app.id(), "cache", "/cache",
                        PlacementFixture.GIGABYTE, false),
                new MountedVolume(UUID.randomUUID(), app.id(), "data", "/srv/data",
                        PlacementFixture.GIGABYTE, false));

        List<FileRoot> all = roots.from(placed(app), Map.of(app.id(), storage));

        assertThat(all).extracting(FileRoot::getLabel)
                .containsExactly("Upload staging", "api / cache", "api / data");
    }

    private List<PlacedService> placed(Service service) {
        return List.of(PlacementFixture.placed(service, NODE, PlacementState.ACTIVE,
                ORGANIZATION, ""));
    }
}
