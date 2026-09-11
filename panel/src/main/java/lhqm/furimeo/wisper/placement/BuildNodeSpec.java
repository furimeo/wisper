package lhqm.furimeo.wisper.placement;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.Timestamp;

import lhqm.furimeo.wisper.node.NodeRepository;
import lhqm.furimeo.wisper.node.NodeSettings;
import lhqm.furimeo.wisper.node.NodeSpecSource;
import lhqm.furimeo.wisper.proto.v1.EnvVar;
import lhqm.furimeo.wisper.proto.v1.NodeSpec;
import lhqm.furimeo.wisper.proto.v1.RetentionPolicy;
import lhqm.furimeo.wisper.proto.v1.Workload;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Assembles the whole desired state of one node.
 *
 * <p>The implementation of {@code node.NodeSpecSource} (panel-ports.md §2.2). {@code node}
 * owns the generation counter and the delivery; this owns the content, because
 * {@code placement} is the one package allowed to read across {@code service},
 * {@code volume}, {@code domain}, {@code env_var}, {@code secret}, {@code cron_task} and
 * {@code managed_database} to put a document together.
 *
 * <p>Every spec is complete. There is no delta anywhere in this contract: a node that has
 * been unreachable for a week is caught up by one document, and a node that reconnects
 * twice in a second is handed the same document twice and converges to the same place
 * (design §5.2). That is only true if the document is a pure function of the rows, so this
 * class introduces nothing of its own - not a clock reading, not a random value, not a map
 * iteration order. {@code generation} and {@code issuedAt} arrive from the caller for
 * exactly that reason: the value written to {@code node.desired_generation} and the value
 * on the wire are the same value, and a spec is never built with a generation that was not
 * recorded first.
 *
 * <p>The composition is deliberately thin. Each piece is loaded or built by one class next
 * door, so that this file says what a spec <em>is</em> and nothing about how any part of it
 * is fetched.
 */
@Component
public class BuildNodeSpec implements NodeSpecSource {

    private final NodeRepository nodes;
    private final PlacementRepository placements;
    private final LoadPlacedServices placedServices;
    private final LoadServiceVolumes serviceVolumes;
    private final LoadServiceEnvironment serviceEnvironment;
    private final BuildWorkloads workloads;
    private final BuildRoutes routes;
    private final BuildCronEntries cronEntries;
    private final BuildDatabaseSpecs databases;
    private final BuildFileRoots fileRoots;
    private final NodeSettings nodeSettings;
    private final PlacementSettings settings;

    public BuildNodeSpec(NodeRepository nodes, PlacementRepository placements,
                         LoadPlacedServices placedServices, LoadServiceVolumes serviceVolumes,
                         LoadServiceEnvironment serviceEnvironment, BuildWorkloads workloads,
                         BuildRoutes routes, BuildCronEntries cronEntries,
                         BuildDatabaseSpecs databases, BuildFileRoots fileRoots,
                         NodeSettings nodeSettings, PlacementSettings settings) {
        this.nodes = nodes;
        this.placements = placements;
        this.placedServices = placedServices;
        this.serviceVolumes = serviceVolumes;
        this.serviceEnvironment = serviceEnvironment;
        this.workloads = workloads;
        this.routes = routes;
        this.cronEntries = cronEntries;
        this.databases = databases;
        this.fileRoots = fileRoots;
        this.nodeSettings = nodeSettings;
        this.settings = settings;
    }

    /**
     * The complete document node {@code nodeId} must converge to.
     *
     * <p>An empty spec is a legitimate instruction and means "run nothing", which is
     * exactly what a drained node should be told - so a node holding nothing produces
     * empty lists rather than an exception.
     *
     * @throws NotFoundException if there is no such node
     */
    @Override
    @Transactional(readOnly = true)
    public NodeSpec buildSpec(UUID nodeId, long generation, Instant issuedAt) {
        if (!nodes.existsById(nodeId)) {
            throw NotFoundException.of("node", nodeId);
        }
        List<PlacedService> placed = placedServices.onNode(nodeId);
        List<UUID> serviceIds = placed.stream().map(entry -> entry.service().id()).toList();

        Map<UUID, List<MountedVolume>> volumes = serviceVolumes.forServices(serviceIds);
        Map<UUID, List<EnvVar>> environment = serviceEnvironment.forServices(serviceIds);
        List<Workload> containers = workloads.from(placed, volumes, environment);

        return NodeSpec.newBuilder()
                .setGeneration(generation)
                .setIssuedAt(timestampOf(issuedAt))
                .addAllWorkloads(containers)
                .addAllRoutes(routes.from(placed))
                .addAllEngines(databases.enginesOn(nodeId))
                .addAllDatabases(databases.grantsOn(nodeId))
                .addAllCron(cronEntries.from(placed))
                .addAllFileRoots(fileRoots.from(placed, volumes))
                .setRetention(retentionFor(placed))
                .setReconcileIntervalSeconds(nodeSettings.reconcileInterval().toSeconds())
                .build();
    }

    /**
     * The nodes whose spec would change if this service changed.
     *
     * <p>At most two in v1: a draining binding and its replacement, and both machines need
     * telling. Empty for a service that has never been placed, which is the ordinary state
     * of one created a minute ago and not an error.
     */
    @Override
    @Transactional(readOnly = true)
    public List<UUID> nodesHosting(UUID serviceId) {
        if (serviceId == null) {
            return List.of();
        }
        List<UUID> hosts = new ArrayList<>(2);
        for (Placement placement : placements.findLiveFor(serviceId)) {
            if (!hosts.contains(placement.nodeId())) {
                hosts.add(placement.nodeId());
            }
        }
        return List.copyOf(hosts);
    }

    /**
     * What the node keeps and what it sweeps.
     *
     * <p>One policy for the whole machine, and the wire has no room for a second, so
     * {@code keep_releases} is the largest number any site on this node is entitled to. The
     * alternative - the smallest, or a fixed default - would silently delete releases a
     * customer was promised they could roll back to. Keeping too many costs disk; keeping
     * too few costs a rollback that cannot happen.
     *
     * <p>Panel-side pruning in {@code deploy} still trims the rows. This is the node's own
     * floor underneath it, for the releases it has on disk when nobody has told it
     * anything.
     */
    private RetentionPolicy retentionFor(List<PlacedService> placed) {
        int keepReleases = settings.keepReleases();
        for (PlacedService entry : placed) {
            if (entry.isSite()) {
                keepReleases = Math.max(keepReleases, entry.service().keepReleases());
            }
        }
        return RetentionPolicy.newBuilder()
                .setKeepReleases(keepReleases)
                .setKeepBuildWorkspaces(settings.keepBuildWorkspaces())
                .setContainerLogMaxBytes(settings.containerLogMaxBytes().toBytes())
                .setContainerLogMaxFiles(settings.containerLogMaxFiles())
                .setOrphanUploadTtlSeconds(settings.orphanUploadTtl().toSeconds())
                .build();
    }

    /**
     * The instant the caller recorded, on the wire.
     *
     * <p>Never {@code Instant.now()}. The node compares this with its own clock to spot
     * skew, and a spec whose timestamp is a moment later than the one stored on the row
     * makes that comparison measure the panel's own latency instead (design §7.2).
     */
    private static Timestamp timestampOf(Instant issuedAt) {
        Instant at = issuedAt == null ? Instant.EPOCH : issuedAt;
        return Timestamp.newBuilder()
                .setSeconds(at.getEpochSecond())
                .setNanos(at.getNano())
                .build();
    }
}
