package lhqm.furimeo.wisper.placement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import lhqm.furimeo.wisper.node.NodeRepository;
import lhqm.furimeo.wisper.proto.v1.EnvVar;
import lhqm.furimeo.wisper.proto.v1.FileRootKind;
import lhqm.furimeo.wisper.proto.v1.NodeSpec;
import lhqm.furimeo.wisper.service.Service;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The document itself: what a generation means, and the property everything else depends
 * on - that the same rows produce the same bytes.
 *
 * <p>The two builders that are pure functions are real here rather than mocked. A
 * determinism test against a mock proves the mock is deterministic.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BuildNodeSpecTest {

    private static final UUID NODE = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final Instant ISSUED = Instant.parse("2026-02-01T09:15:30.123456789Z");

    @Mock
    private NodeRepository nodes;

    @Mock
    private PlacementRepository placements;

    @Mock
    private LoadPlacedServices placedServices;

    @Mock
    private LoadServiceVolumes serviceVolumes;

    @Mock
    private LoadServiceEnvironment serviceEnvironment;

    @Mock
    private BuildRoutes routes;

    @Mock
    private BuildCronEntries cronEntries;

    @Mock
    private BuildDatabaseSpecs databases;

    private BuildNodeSpec specs;

    private final Service app = PlacementFixture.runningApp(UUID.randomUUID(), PROJECT);
    private final Service site = PlacementFixture.runningSite(UUID.randomUUID(), PROJECT);

    @BeforeEach
    void wireTheRealBuildersToFakeLoaders() {
        PlacementSettings settings = PlacementFixture.settings();
        specs = new BuildNodeSpec(nodes, placements, placedServices, serviceVolumes,
                serviceEnvironment, new BuildWorkloads(settings), routes, cronEntries, databases,
                new BuildFileRoots(settings), PlacementFixture.nodeSettings(15, 15, 20), settings);
        given(nodes.existsById(NODE)).willReturn(true);
        given(placedServices.onNode(NODE)).willReturn(List.of());
        given(serviceVolumes.forServices(anyCollection())).willReturn(Map.of());
        given(serviceEnvironment.forServices(anyCollection())).willReturn(Map.of());
        given(routes.from(any())).willReturn(List.of());
        given(cronEntries.from(any())).willReturn(List.of());
        given(databases.enginesOn(NODE)).willReturn(List.of());
        given(databases.grantsOn(NODE)).willReturn(List.of());
    }

    @Test
    void anUnknownNodeIsNotFoundRatherThanAnEmptySpec() {
        given(nodes.existsById(NODE)).willReturn(false);

        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> specs.buildSpec(NODE, 1L, ISSUED));
    }

    @Test
    void aNodeHoldingNothingGetsAValidSpecThatSaysRunNothing() {
        NodeSpec spec = specs.buildSpec(NODE, 4L, ISSUED);

        assertThat(spec.getWorkloadsList()).isEmpty();
        assertThat(spec.getRoutesList()).isEmpty();
        assertThat(spec.getCronList()).isEmpty();
        assertThat(spec.getGeneration()).isEqualTo(4L);
        assertThat(spec.getReconcileIntervalSeconds()).isEqualTo(15L);
        // The staging root is there even on an empty node: a spec is published when a
        // service is placed and the deploy archive is pushed immediately afterwards.
        assertThat(spec.getFileRootsList()).hasSize(1);
        assertThat(spec.getFileRoots(0).getKind())
                .isEqualTo(FileRootKind.FILE_ROOT_KIND_UPLOAD_STAGING);
        assertThat(spec.getFileRoots(0).getId()).isEqualTo("upload-staging");
    }

    @Test
    void theGenerationAndTheIssueInstantAreTheCallersAndNotThisClocks() {
        NodeSpec spec = specs.buildSpec(NODE, 47L, ISSUED);

        assertThat(spec.getGeneration()).isEqualTo(47L);
        assertThat(spec.getIssuedAt().getSeconds()).isEqualTo(ISSUED.getEpochSecond());
        assertThat(spec.getIssuedAt().getNanos()).isEqualTo(ISSUED.getNano());
    }

    @Test
    void aLaterGenerationChangesTheCounterAndNothingElse() {
        givenTheNodeHoldsAnAppAndASite();

        NodeSpec seven = specs.buildSpec(NODE, 7L, ISSUED);
        NodeSpec eight = specs.buildSpec(NODE, 8L, ISSUED);

        assertThat(eight.getGeneration()).isGreaterThan(seven.getGeneration());
        assertThat(eight.toBuilder().setGeneration(7L).build().toByteArray())
                .isEqualTo(seven.toByteArray());
    }

    @Test
    void unchangedRowsProduceAByteIdenticalSpec() {
        givenTheNodeHoldsAnAppAndASite();

        NodeSpec first = specs.buildSpec(NODE, 12L, ISSUED);
        NodeSpec second = specs.buildSpec(NODE, 12L, ISSUED);

        assertThat(second.toByteArray()).isEqualTo(first.toByteArray());
    }

    @Test
    void retentionKeepsTheMostGenerousPromiseAnySiteOnTheNodeWasGiven() {
        Service generous = PlacementFixture.runningSite(UUID.randomUUID(), PROJECT);
        given(placedServices.onNode(NODE)).willReturn(List.of(
                PlacementFixture.placed(generous, NODE, PlacementState.ACTIVE, ORGANIZATION, "r")));

        // The fixture site keeps five, and so does the platform default, so the node is
        // told five rather than something smaller that would delete a promised rollback.
        assertThat(specs.buildSpec(NODE, 1L, ISSUED).getRetention().getKeepReleases())
                .isEqualTo(5);
        assertThat(specs.buildSpec(NODE, 1L, ISSUED).getRetention().getOrphanUploadTtlSeconds())
                .isEqualTo(86_400L);
    }

    @Test
    void aSiteAddsItsReleasesTreeAsAReadOnlyFileRoot() {
        given(placedServices.onNode(NODE)).willReturn(List.of(
                PlacementFixture.placed(site, NODE, PlacementState.ACTIVE, ORGANIZATION, "r1")));

        NodeSpec spec = specs.buildSpec(NODE, 1L, ISSUED);

        assertThat(spec.getFileRootsList()).hasSize(2);
        assertThat(spec.getFileRoots(1).getKind()).isEqualTo(FileRootKind.FILE_ROOT_KIND_SITE);
        assertThat(spec.getFileRoots(1).getId()).isEqualTo(site.id().toString());
        // A build would silently revert anything edited in place.
        assertThat(spec.getFileRoots(1).getWritable()).isFalse();
    }

    @Test
    void nodesHostingReportsBothMachinesDuringAMove() {
        UUID serviceId = app.id();
        UUID other = UUID.randomUUID();
        given(placements.findLiveFor(serviceId)).willReturn(List.of(
                PlacementFixture.placement(serviceId, NODE, PlacementState.DRAINING, false),
                PlacementFixture.placement(serviceId, other, PlacementState.ACTIVE, false)));

        assertThat(specs.nodesHosting(serviceId)).containsExactly(NODE, other);
    }

    @Test
    void nodesHostingIsEmptyForSomethingNeverPlacedAndForNothingAtAll() {
        given(placements.findLiveFor(any())).willReturn(List.of());

        assertThat(specs.nodesHosting(UUID.randomUUID())).isEmpty();
        assertThat(specs.nodesHosting(null)).isEmpty();
    }

    private void givenTheNodeHoldsAnAppAndASite() {
        given(placedServices.onNode(NODE)).willReturn(List.of(
                PlacementFixture.placed(app, NODE, PlacementState.ACTIVE, ORGANIZATION, ""),
                PlacementFixture.placed(site, NODE, PlacementState.ACTIVE, ORGANIZATION, "r1")));
        given(serviceVolumes.forServices(anyCollection())).willReturn(Map.of(app.id(), List.of(
                new MountedVolume(UUID.randomUUID(), app.id(), "data", "/data",
                        PlacementFixture.GIGABYTE, false))));
        given(serviceEnvironment.forServices(anyCollection())).willReturn(Map.of(app.id(), List.of(
                EnvVar.newBuilder().setName("PORT").setValue("8080").build())));
    }
}
