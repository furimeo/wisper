package lhqm.furimeo.wisper.placement;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.audit.AuditEntry;
import lhqm.furimeo.wisper.audit.AuditTarget;
import lhqm.furimeo.wisper.audit.AuditTrail;
import lhqm.furimeo.wisper.node.PublishNodeSpec;
import lhqm.furimeo.wisper.org.RequestRejected;
import lhqm.furimeo.wisper.service.LocateService;
import lhqm.furimeo.wisper.service.Service;
import lhqm.furimeo.wisper.service.ServiceLocation;
import lhqm.furimeo.wisper.service.ServiceRepository;
import lhqm.furimeo.wisper.service.Volume;
import lhqm.furimeo.wisper.service.VolumeRepository;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Moves a service from the machine it is on to one an operator has named.
 *
 * <p>Deliberate, never automatic (design §7.8). Nothing in this package reschedules on its
 * own: a workload that moves while nobody is looking loses whatever was on its disk, and a
 * platform that does that once is a platform nobody keeps data on.
 *
 * <p>The move is two rows in one transaction. The old binding becomes
 * {@link PlacementState#DRAINING} and stays in the old node's spec, so that machine keeps
 * serving; the new binding is {@link PlacementState#ACTIVE} on the target, so every panel
 * question about "where does this run" answers with the destination from this instant on.
 * Both nodes are republished. The partial unique index
 * {@code placement_one_active_per_service_idx} is partial for exactly this pair of rows.
 *
 * <h2>Volumes do not follow</h2>
 *
 * <p>The bytes are on the old machine and this transaction does not copy them: there is no
 * live migration here and pretending otherwise would be the worst kind of lie a platform
 * can tell. Moving a service that has storage therefore needs the caller to say so
 * explicitly, and the honest way to bring the data across is to restore a snapshot into
 * the new volume afterwards.
 */
@Component
public class MigrateService {

    private final ServiceRepository services;
    private final VolumeRepository volumes;
    private final LocateService locations;
    private final PlacementRepository placements;
    private final ReserveCapacity reserve;
    private final PlaceService placeService;
    private final PublishNodeSpec specs;
    private final AuditTrail audit;

    public MigrateService(ServiceRepository services, VolumeRepository volumes,
                          LocateService locations, PlacementRepository placements,
                          ReserveCapacity reserve, PlaceService placeService,
                          PublishNodeSpec specs, AuditTrail audit) {
        this.services = services;
        this.volumes = volumes;
        this.locations = locations;
        this.placements = placements;
        this.reserve = reserve;
        this.placeService = placeService;
        this.specs = specs;
        this.audit = audit;
    }

    /**
     * Rebinds a service to {@code targetNodeId}.
     *
     * @param moveDespiteVolumes required to be true when the service has storage. It is a
     *                           parameter and not a warning in the UI because the caller
     *                           has to have decided, and a default that says yes is a
     *                           default that deletes data.
     * @return the new binding on the target node
     * @throws NotFoundException if there is no such service
     * @throws RequestRejected   if it is not placed, is already there, is mid-migration, or
     *                           has storage and the caller did not accept losing it
     * @throws NoNodeFits        if the named node cannot take it
     */
    @Transactional
    public Placement toNode(AuditActor actor, UUID serviceId, UUID targetNodeId,
                            boolean moveDespiteVolumes, String reason) {
        Service service = services.findById(serviceId)
                .orElseThrow(() -> NotFoundException.of("service", serviceId));
        ServiceLocation location = locations.byId(serviceId);

        Instant now = Instant.now();
        placements.refreshPinningFor(serviceId, now);

        List<Placement> live = placements.findLiveFor(serviceId);
        Placement source = currentOf(live, service.name());
        rejectPointlessOrUnsafeMoves(live, source, service.name(), targetNodeId,
                moveDespiteVolumes);

        List<Volume> storage = volumes.findByServiceIdOrderByName(serviceId);
        reserve.requireRoomOn(targetNodeId, service, ResourceDemand.of(service, storage));

        String note = reason == null || reason.isBlank()
                ? "migrated to node " + targetNodeId
                : reason;
        placements.markDraining(source.id(), "migrating to node " + targetNodeId, now);
        Placement replacement = placeService.onNode(serviceId, targetNodeId, source.pinned(), note);

        // Both machines, and the old one first: it is the one that has to let go, and a
        // spec it never receives is resent whole when it reconnects anyway.
        specs.toNode(source.nodeId(), note);
        specs.toNode(targetNodeId, note);

        audit.record(AuditEntry.succeeded(actor, "service.update",
                AuditTarget.of("service", serviceId, service.name()),
                location.organizationId(),
                "Migrated from node " + source.nodeId() + " to node " + targetNodeId
                        + (storage.isEmpty()
                                ? ". It has no volumes, so nothing was left behind."
                                : ". " + storage.size() + " volume(s) stayed on the old node and "
                                        + "must be restored from a snapshot.")));
        return replacement;
    }

    private static Placement currentOf(List<Placement> live, String name) {
        Optional<Placement> current = live.stream().filter(Placement::isCurrent).findFirst();
        return current.orElseThrow(() -> new RequestRejected(null,
                name + " is not on a node, so there is nothing to migrate. Start it instead."));
    }

    private static void rejectPointlessOrUnsafeMoves(List<Placement> live, Placement source,
                                                     String name, UUID targetNodeId,
                                                     boolean moveDespiteVolumes) {
        if (targetNodeId == null) {
            throw new RequestRejected("targetNodeId", "Choose a node to move " + name + " to.");
        }
        if (targetNodeId.equals(source.nodeId())) {
            throw new RequestRejected("targetNodeId", name + " is already on that node.");
        }
        boolean alreadyMoving = live.stream()
                .anyMatch(placement -> placement.state() == PlacementState.DRAINING);
        if (alreadyMoving) {
            throw new RequestRejected(null, name + " is already being moved. Wait for the old "
                    + "node to report the workload gone before moving it again.");
        }
        if (source.pinned() && !moveDespiteVolumes) {
            throw new RequestRejected(null, name + " has storage on node " + source.nodeId()
                    + ". Moving it does not move the data: the volumes stay where they are and "
                    + "have to be restored from a snapshot on the new node. Confirm that before "
                    + "continuing.");
        }
    }
}
