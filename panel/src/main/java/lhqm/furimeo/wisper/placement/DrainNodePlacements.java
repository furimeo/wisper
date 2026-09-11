package lhqm.furimeo.wisper.placement;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.node.Node;
import lhqm.furimeo.wisper.node.NodeRepository;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.service.Service;
import lhqm.furimeo.wisper.service.ServiceRepository;
import lhqm.furimeo.wisper.service.Volume;
import lhqm.furimeo.wisper.service.VolumeRepository;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Empties a node of everything that can be moved, and lists what cannot.
 *
 * <p>The panel-side half of a drain (design §7.7). It rebinds every service that has no
 * storage onto another machine and leaves every service that does exactly where it is,
 * because moving a pinned service means moving bytes and this transaction cannot move
 * bytes. What is left is reported rather than forced, so an operator decides one service
 * at a time through {@link MigrateService}.
 *
 * <p>It does not change the node's own lifecycle. {@code node} owns that column and the
 * {@code DrainNode} command on the wire; this owns the {@code placement} table. The two
 * meet at {@link DrainOutcome}, which is deliberately the same three lists as
 * {@code DrainReport} in {@code node.proto}.
 *
 * <p>Survey mode - {@code evacuateStateless = false} - answers the same question and
 * writes nothing, which is what a confirmation screen needs and what the wire's own
 * {@code DrainNode.evacuate_stateless = false} means. It decides what is pinned by reading
 * the volume table rather than the {@code pinned} column, so a survey cannot be wrong
 * about the one thing it exists to tell an operator.
 */
@Component
public class DrainNodePlacements {

    private static final Logger log = LoggerFactory.getLogger(DrainNodePlacements.class);

    private final NodeRepository nodes;
    private final ServiceRepository services;
    private final VolumeRepository volumes;
    private final PlacementRepository placements;
    private final ReserveCapacity reserve;
    private final PlaceService placeService;
    private final PublishNodeSpec specs;
    private final AuditTrail audit;

    public DrainNodePlacements(NodeRepository nodes, ServiceRepository services,
                               VolumeRepository volumes, PlacementRepository placements,
                               ReserveCapacity reserve, PlaceService placeService,
                               PublishNodeSpec specs, AuditTrail audit) {
        this.nodes = nodes;
        this.services = services;
        this.volumes = volumes;
        this.placements = placements;
        this.reserve = reserve;
        this.placeService = placeService;
        this.specs = specs;
        this.audit = audit;
    }

    /**
     * Moves what can move off this node.
     *
     * @param evacuateStateless false surveys and changes nothing
     * @throws NotFoundException if there is no such node
     */
    @Transactional
    public DrainOutcome forNode(AuditActor actor, UUID nodeId, boolean evacuateStateless,
                                String reason) {
        Node node = nodes.findById(nodeId)
                .orElseThrow(() -> NotFoundException.of("node", nodeId));
        Instant now = Instant.now();
        String note = reason == null || reason.isBlank()
                ? "draining node " + node.name()
                : reason;

        List<Placement> held = placements.findLiveOn(nodeId).stream()
                .filter(Placement::isCurrent)
                .toList();

        List<UUID> pinned = new ArrayList<>();
        List<UUID> evacuated = new ArrayList<>();
        List<UUID> stranded = new ArrayList<>();
        Set<UUID> republish = new LinkedHashSet<>();

        for (Placement placement : held) {
            Service service = services.findById(placement.serviceId()).orElse(null);
            if (service == null) {
                // The service is being deleted under a cascade. There is nothing to move
                // and the placement row is going with it.
                continue;
            }
            List<Volume> storage = volumes.findByServiceIdOrderByName(service.id());
            if (!storage.isEmpty()) {
                pinned.add(service.id());
                continue;
            }
            Optional<UUID> target = destinationFor(service, nodeId);
            if (target.isEmpty()) {
                stranded.add(service.id());
                continue;
            }
            evacuated.add(service.id());
            if (evacuateStateless) {
                placements.markDraining(placement.id(), note, now);
                placeService.onNode(service.id(), target.get(), false, note);
                republish.add(target.get());
            }
        }

        DrainOutcome outcome = new DrainOutcome(nodeId, evacuated, pinned, stranded,
                !evacuateStateless, stranded.isEmpty());
        if (evacuateStateless) {
            // The column an operator reads on the placement screen, brought back in line
            // with the volume table this pass just consulted.
            placements.refreshPinningOn(nodeId, now);
            republish.add(nodeId);
            for (UUID affected : republish) {
                specs.toNode(affected, note);
            }
        }
        audit.record(AuditEntry.succeeded(actor, "node.drain",
                AuditTarget.of("node", nodeId, node.name()), null, outcome.describe()));
        return outcome;
    }

    /**
     * Somewhere else this service could run, or nothing.
     *
     * <p>A service with nowhere to go is recorded and skipped rather than thrown on. One
     * workload the fleet cannot take must not abandon a drain half-done: the operator needs
     * the whole list, and the services that could move should still move.
     */
    private Optional<UUID> destinationFor(Service service, UUID drainingNodeId) {
        try {
            return Optional.of(reserve.bestFit(service, ResourceDemand.of(service, List.of()),
                    List.of(drainingNodeId)));
        } catch (NoNodeFits noRoom) {
            log.info("Cannot evacuate service {} from node {}: {}", service.id(), drainingNodeId,
                    noRoom.getMessage());
            return Optional.empty();
        }
    }
}
